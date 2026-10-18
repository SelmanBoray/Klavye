package com.selman.klavye.suggest

import java.util.Locale
import kotlin.math.ln
import kotlin.math.min

/**
 * Saf Kotlin öneri motoru (android.* bağımlılığı yok; JVM birim testleriyle test edilir).
 * Tek thread'den kullanılır; kurucu arka plan thread'inde çağrılabilir.
 *
 * Veri yapısı
 * - Temel sözlük kurucuda tek bir [CharArray] içine paketlenir: kelimeler (tr-TR küçük harf)
 *   KATLANMIŞ biçimlerine göre sıralı, art arda durur; sınırlar [baseOffsets]'te.
 *   Katlama (ç→c ğ→g ı→i ö→o ş→s ü→u â→a î→i û→u) küçük harfli metinde karakter başına
 *   bire bir eşleme olduğundan katlanmış anahtar ayrıca saklanmaz, karşılaştırmada anında
 *   hesaplanır. Kelime başına nesne yoktur (60k kelime ≈ 2 MB).
 * - Önek araması: katlanmış önek için iki ikili arama bitişik [lo, hi) aralığını verir;
 *   aralık taranır, en iyi `limit` aday küçük bir top-k dizisinde tutulur.
 * - Kullanıcı verisi: kelime → [UserWord], önceki → (sonraki → [Bigram]). Temel kelimelerin
 *   kullanıcı sayıları taramada arama/nesne maliyeti olmasın diye [baseUserCounts]'ta aynalanır.
 *
 * Skor (yüksek olan önde)
 *   base   = (ln(1 + sıklık) + (katlanmadan önek eşleşmesi ? ln 1.5 : 0)) / ln(2 + maxSıklık)
 *   user   = 2 · ln(1 + kullanıcı sayısı)
 *   bigram = 4 · ln(1 + previousWord→aday sayısı)
 *   skor   = base + user + bigram
 * base en çok ≈1.13 olur, tek bir kullanım bile 2·ln2 ≈ 1.39 ekler: kullanıcı sayısı temel
 * sıklıktan her zaman baskındır. Bigram bonusu composing doluyken ilk görülmeden itibaren
 * uygulanır; composing boşken bir bigram ancak en az 2 kez görülmüşse tahmin edilir.
 *
 * Yazım
 * - baseWords'teki kelime TERCİH EDİLEN yazımdır ("ve", "İstanbul", "TV"). Eşleme, tekrar
 *   kontrolü ve kullanıcı verisi tr-TR küçük harf (+katlama) üzerindendir: "İstanbul" ile
 *   öğrenilen "istanbul" aynı girdidir. Küçük harften farklı yazımlar kelime başına bir bayt
 *   işaretle (ilk harf büyük / tamamı büyük) ya da nadir karışık yazımlar için küçük bir
 *   eşlemle saklanır.
 * - Gösterim: composing tamamı büyük ve ≥2 karakterse tamamı büyük (tr-TR); yalnız ilk harfi
 *   büyükse tercih edilen yazımın ilk harfi büyütülür; aksi halde tercih edilen yazım.
 */
class SuggestionEngine(baseWords: List<Pair<String, Int>>) : Suggester {

    private class UserWord(val word: String, val folded: String, val baseIndex: Int) {
        var count = 0
        var lastUse = 0L

        /** Yalnız temel sözlükte olmayan kelimeler için: küçük harften farklı yazılan commit sayısı. */
        var capCount = 0

        /** Yalnız temel sözlükte olmayan kelimeler için: en son görülen küçük-harf-dışı yazım ("Selman"). */
        var form: String? = null
    }

    private class Bigram(val prev: String, val next: String, val nextBase: Int) {
        var count = 0
        var lastUse = 0L
    }

    /** En yüksek skorlu [capacity] adayı azalan sırada tutar (eşit skorda önce gelen kalır). */
    private class TopK(val capacity: Int) {
        val scores = DoubleArray(capacity)
        val baseIdx = IntArray(capacity)
        val users = arrayOfNulls<UserWord>(capacity)
        var size = 0

        fun offer(score: Double, base: Int, user: UserWord?) {
            var p: Int
            if (size < capacity) {
                p = size
                size++
            } else if (score > scores[capacity - 1]) {
                p = capacity - 1
            } else {
                return
            }
            while (p > 0 && scores[p - 1] < score) {
                scores[p] = scores[p - 1]
                baseIdx[p] = baseIdx[p - 1]
                users[p] = users[p - 1]
                p--
            }
            scores[p] = score
            baseIdx[p] = base
            users[p] = user
        }
    }

    // ---- Temel sözlük (kurucudan sonra değişmez) ----

    /** Tüm temel kelimeler art arda; i. kelime baseChars[baseOffsets[i] until baseOffsets[i + 1]]. */
    private val baseChars: CharArray
    private val baseOffsets: IntArray
    private val baseFreqs: IntArray
    private val baseCount: Int
    private val baseScale: Double

    /**
     * Tercih edilen yazım türü (PREF_*), kelime başına bir bayt; tüm sözlük küçük harfse null.
     * baseChars her zaman küçük harfi tutar (eşleme için), gösterim bu işaretten türetilir.
     */
    private val basePrefKinds: ByteArray?

    /** Yalnız PREF_MIXED kelimelerin ("iPhone" gibi, nadir) tam yazımı; yoksa null. */
    private val baseMixedPrefs: HashMap<Int, String>?

    /** Temel kelimelerin kullanıcı sayıları ([userWords] ile senkron); 0 = kullanılmadı/budandı. */
    private val baseUserCounts: IntArray

    // ---- Kullanıcı verisi ----

    private val userWords = HashMap<String, UserWord>()
    private val bigrams = HashMap<String, HashMap<String, Bigram>>()
    private var bigramTotal = 0
    private var tick = 0L

    private val wordsLeastUsedFirst = Comparator { a: UserWord, b: UserWord ->
        when {
            a.count != b.count -> a.count.compareTo(b.count)
            a.lastUse != b.lastUse -> a.lastUse.compareTo(b.lastUse)
            else -> a.word.compareTo(b.word)
        }
    }

    private val bigramsLeastUsedFirst = Comparator { a: Bigram, b: Bigram ->
        when {
            a.count != b.count -> a.count.compareTo(b.count)
            a.lastUse != b.lastUse -> a.lastUse.compareTo(b.lastUse)
            a.prev != b.prev -> a.prev.compareTo(b.prev)
            else -> a.next.compareTo(b.next)
        }
    }

    init {
        val total = baseWords.size
        val lowers = arrayOfNulls<String>(total)
        val prefs = arrayOfNulls<String>(total) // tercih edilen yazım (girdideki nesne, kopya yok)
        val freqs = IntArray(total)
        var m = 0
        for (pair in baseWords) {
            val pref = pair.first.trim()
            val lw = pref.lowercase(TR)
            if (lw.isEmpty()) continue
            lowers[m] = lw
            prefs[m] = pref
            freqs[m] = if (pair.second > 0) pair.second else 0
            m++
        }

        // Katlanmış anahtara, sonra küçük harfli kelimeye, sonra sıklığa (azalan) göre sırala:
        // aynı kelimenin (büyük/küçük farkı dahil: "İstanbul" ~ "istanbul") tekrarları yan yana
        // gelir, ilki (en sık olanı) ve onun tercih edilen yazımı tutulur.
        val order: Array<Int> = Array(m) { it }
        order.sortWith(Comparator { a: Int, b: Int ->
            val wa = lowers[a]!!
            val wb = lowers[b]!!
            var c = compareFolded(wa, wb)
            if (c == 0) c = wa.compareTo(wb)
            if (c == 0) c = freqs[b].compareTo(freqs[a])
            c
        })

        val keep = IntArray(m)
        var kept = 0
        var totalChars = 0
        var previous: String? = null
        for (j in 0 until m) {
            val idx = order[j]
            val w = lowers[idx]!!
            if (w == previous) continue
            previous = w
            keep[kept++] = idx
            totalChars += w.length
        }

        val chars = CharArray(totalChars)
        val offsets = IntArray(kept + 1)
        val fr = IntArray(kept)
        var pos = 0
        var maxFreq = 0
        var kinds: ByteArray? = null
        var mixed: HashMap<Int, String>? = null
        for (j in 0 until kept) {
            val idx = keep[j]
            val w = lowers[idx]!!
            offsets[j] = pos
            for (k in w.indices) chars[pos + k] = w[k]
            pos += w.length
            val f = freqs[idx]
            fr[j] = f
            if (f > maxFreq) maxFreq = f

            val pref = prefs[idx]!!
            if (pref != w) {
                val kind: Byte = when (pref) {
                    capitalizeTr(w) -> PREF_CAPITALIZED
                    w.uppercase(TR) -> PREF_UPPER
                    else -> PREF_MIXED
                }
                var ks = kinds
                if (ks == null) {
                    ks = ByteArray(kept)
                    kinds = ks
                }
                ks[j] = kind
                if (kind == PREF_MIXED) {
                    var mx = mixed
                    if (mx == null) {
                        mx = HashMap()
                        mixed = mx
                    }
                    mx[j] = pref
                }
            }
        }
        offsets[kept] = pos

        baseChars = chars
        baseOffsets = offsets
        baseFreqs = fr
        baseCount = kept
        baseScale = 1.0 / ln(2.0 + maxFreq)
        basePrefKinds = kinds
        baseMixedPrefs = mixed
        baseUserCounts = IntArray(kept)
    }

    // ---- Suggester ----

    override fun suggest(composing: String, previousWord: String?, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val prevKey = if (previousWord == null) null else normalizeWord(previousWord)
        if (composing.isEmpty()) return predictNext(prevKey, limit)

        val lower = composing.lowercase(TR)
        val key = foldLower(lower) // lower ile aynı uzunlukta
        val lo = lowerBound(key)
        val hi = prefixEnd(lo, key)
        val capacity = min(limit, (hi - lo) + userWords.size)
        if (capacity <= 0) return emptyList()

        val followers = if (prevKey == null) null else bigrams[prevKey]
        val top = TopK(capacity)

        // Temel sözlük adayları. previousWord'ün bu aralığa düşen takipçileri indekse göre
        // sıralı (indeks << 32 | sayı) dizisinde; tarama ile birlikte yürünür (kutulama yok).
        val follow = if (followers == null) null else baseFollowersIn(followers, lo, hi)
        var fi = 0
        for (i in lo until hi) {
            var bigramCount = 0
            if (follow != null) {
                while (fi < follow.size && (follow[fi] ushr 32).toInt() < i) fi++
                if (fi < follow.size && (follow[fi] ushr 32).toInt() == i) {
                    bigramCount = (follow[fi] and 0xFFFFFFFFL).toInt()
                }
            }
            top.offer(score(baseFreqs[i], baseUserCounts[i], bigramCount, rawPrefixAt(i, lower)), i, null)
        }

        // Öneri eşiğini geçmiş, temel sözlükte olmayan kullanıcı kelimeleri.
        for (uw in userWords.values) {
            if (uw.baseIndex >= 0 || uw.count < MIN_NEW_WORD_COUNT) continue
            if (!uw.folded.startsWith(key)) continue
            val bigramCount = followers?.get(uw.word)?.count ?: 0
            top.offer(score(0, uw.count, bigramCount, uw.word.startsWith(lower)), -1, uw)
        }

        val mode = caseModeOf(composing)
        val out = ArrayList<String>(top.size)
        for (j in 0 until top.size) {
            val u = top.users[j]
            val preferred = if (u != null) userPreferred(u) else basePreferredAt(top.baseIdx[j])
            val shown = applyCase(preferred, mode)
            if (!out.contains(shown)) out.add(shown)
        }
        return out
    }

    override fun onWordCommitted(word: String, previousWord: String?) {
        val w = normalizeWord(word) ?: return
        tick++
        val uw = obtainUserWord(w)
        uw.count = incrementCount(uw.count)
        uw.lastUse = tick
        if (uw.baseIndex >= 0) {
            baseUserCounts[uw.baseIndex] = uw.count
        } else {
            // Sözlük dışı kelimenin yazımını hatırla ("Selman"); gösterimde çoğunluk kuralı uygulanır.
            val raw = word.trim()
            if (raw != w) {
                uw.capCount = incrementCount(uw.capCount)
                uw.form = raw
            }
        }

        val p = if (previousWord == null) null else normalizeWord(previousWord)
        if (p != null) {
            val b = obtainBigram(p, w, uw.baseIndex)
            b.count = incrementCount(b.count)
            b.lastUse = tick
        }
    }

    /** Kalıcılık Android tarafında ayrı sınıfta; burada yapılacak bir şey yok. */
    override fun flush() {
        // no-op
    }

    // ---- Dışa / içe aktarma ----

    /**
     * Kullanıcı verisini satır tabanlı UTF-8 metin olarak döndürür (sekme ayraçlı):
     * ```
     * # klavye-userdata v2
     * w<TAB>kelime<TAB>sayı<TAB>sonKullanım[<TAB>yazımSayısı<TAB>yazım]
     * b<TAB>önceki<TAB>sonraki<TAB>sayı<TAB>sonKullanım
     * ```
     * Kelimeler tr-TR küçük harftir ve boşluk/kontrol karakteri içeremez (commit'te elenir).
     * Son iki "w" alanı yalnız yazımı hatırlanan sözlük dışı kelimelerde vardır; v1 dosyaları
     * (bu alanlar olmadan) aynen okunur. Satırlar sayıya göre azalan sıradadır (dosya kesilirse
     * en değerli veri korunur).
     */
    fun exportUserData(): String {
        val words = ArrayList<UserWord>(userWords.values)
        words.sortWith(wordsLeastUsedFirst.reversed())
        val pairs = ArrayList<Bigram>(bigramTotal)
        for (inner in bigrams.values) pairs.addAll(inner.values)
        pairs.sortWith(bigramsLeastUsedFirst.reversed())

        val sb = StringBuilder(32 + words.size * 24 + pairs.size * 32)
        sb.append(HEADER).append('\n')
        for (uw in words) {
            sb.append(TAG_WORD).append('\t').append(uw.word).append('\t')
                .append(uw.count).append('\t').append(uw.lastUse)
            val form = uw.form
            if (form != null && uw.capCount > 0) {
                sb.append('\t').append(uw.capCount).append('\t').append(form)
            }
            sb.append('\n')
        }
        for (b in pairs) {
            sb.append(TAG_BIGRAM).append('\t').append(b.prev).append('\t').append(b.next).append('\t')
                .append(b.count).append('\t').append(b.lastUse).append('\n')
        }
        return sb.toString()
    }

    /**
     * [exportUserData] çıktısını yükler. Mevcut veriyle birleştirirken her kayıt için sayı ve
     * sonKullanım'ın büyüğü alınır (aynı veriyi iki kez yüklemek sonucu değiştirmez).
     * Bozuk/tanınmayan satırlar sessizce atlanır; asla istisna fırlatmaz.
     */
    fun importUserData(data: String) {
        try {
            for (line in data.lineSequence()) {
                try {
                    importLine(line)
                } catch (e: Exception) {
                    // Bozuk satır: atla.
                }
            }
        } catch (e: Exception) {
            // Dışarı hiçbir istisna sızmasın.
        }
    }

    private fun importLine(line: String) {
        if (line.isEmpty() || line[0] == '#') return
        val f = line.split('\t')
        // Fazladan (bilinmeyen, ileriki sürüm) alanlar yok sayılır.
        when (f[0]) {
            TAG_WORD -> {
                if (f.size < 3) return
                val w = normalizeWord(f[1]) ?: return
                val count = parseCount(f[2]) ?: return
                val lastUse = if (f.size >= 4) (parseTick(f[3]) ?: return) else 0L
                val uw = obtainUserWord(w)
                if (count > uw.count) uw.count = count
                if (lastUse > uw.lastUse) uw.lastUse = lastUse
                if (uw.baseIndex >= 0) baseUserCounts[uw.baseIndex] = uw.count
                if (lastUse > tick) tick = lastUse
                if (f.size >= 6 && uw.baseIndex < 0) {
                    // Yazım bilgisi bozuksa yalnız o kısım atlanır, kelime yine yüklenir.
                    val capCount = parseCount(f[4])
                    val form = f[5].trim()
                    if (capCount != null && capCount > uw.capCount && normalizeWord(form) == w) {
                        uw.capCount = capCount
                        uw.form = form
                    }
                }
            }
            TAG_BIGRAM -> {
                if (f.size < 4) return
                val p = normalizeWord(f[1]) ?: return
                val n = normalizeWord(f[2]) ?: return
                val count = parseCount(f[3]) ?: return
                val lastUse = if (f.size >= 5) (parseTick(f[4]) ?: return) else 0L
                val nextBase = userWords[n]?.baseIndex ?: baseIndexOf(n)
                val b = obtainBigram(p, n, nextBase)
                if (count > b.count) b.count = count
                if (lastUse > b.lastUse) b.lastUse = lastUse
                if (lastUse > tick) tick = lastUse
            }
        }
    }

    // ---- Öneri yardımcıları ----

    private fun predictNext(prevKey: String?, limit: Int): List<String> {
        if (prevKey == null) return emptyList()
        val followers = bigrams[prevKey] ?: return emptyList()
        val list = ArrayList<Bigram>()
        for (b in followers.values) if (b.count >= MIN_BIGRAM_PREDICT_COUNT) list.add(b)
        if (list.isEmpty()) return emptyList()
        list.sortWith(bigramsLeastUsedFirst.reversed())
        val n = min(limit, list.size)
        val out = ArrayList<String>(n)
        for (j in 0 until n) {
            val b = list[j]
            val shown = if (b.nextBase >= 0) {
                basePreferredAt(b.nextBase)
            } else {
                val u = userWords[b.next]
                if (u != null) userPreferred(u) else b.next
            }
            if (!out.contains(shown)) out.add(shown)
        }
        return out
    }

    /** Temel kelimenin tercih edilen yazımı (sözlükteki biçim: "istanbul", "İstanbul", "TV"). */
    private fun basePreferredAt(i: Int): String {
        val lower = baseWordAt(i)
        val kinds = basePrefKinds ?: return lower
        return when (kinds[i]) {
            PREF_CAPITALIZED -> capitalizeTr(lower)
            PREF_UPPER -> lower.uppercase(TR)
            PREF_MIXED -> baseMixedPrefs?.get(i) ?: lower
            else -> lower
        }
    }

    /**
     * Kullanıcı kelimesinin gösterim biçimi: temel sözlükteyse sözlükteki yazım; değilse
     * commit'lerin yarısından fazlası küçük-harf-dışıysa en son görülen o yazım, aksi halde küçük.
     */
    private fun userPreferred(uw: UserWord): String {
        if (uw.baseIndex >= 0) return basePreferredAt(uw.baseIndex)
        val form = uw.form
        return if (form != null && uw.capCount.toLong() * 2 > uw.count) form else uw.word
    }

    /** [followers] içinden temel indeksi [lo, hi) aralığında olanlar: (indeks << 32 | sayı), sıralı. */
    private fun baseFollowersIn(followers: HashMap<String, Bigram>, lo: Int, hi: Int): LongArray? {
        var n = 0
        for (b in followers.values) if (b.nextBase >= lo && b.nextBase < hi) n++
        if (n == 0) return null
        val arr = LongArray(n)
        var j = 0
        for (b in followers.values) {
            if (b.nextBase >= lo && b.nextBase < hi) {
                arr[j++] = (b.nextBase.toLong() shl 32) or b.count.toLong()
            }
        }
        arr.sort()
        return arr
    }

    private fun score(freq: Int, userCount: Int, bigramCount: Int, exactPrefix: Boolean): Double {
        var s = ln(1.0 + freq)
        if (exactPrefix) s += EXACT_PREFIX_BONUS
        s *= baseScale
        if (userCount > 0) s += USER_WEIGHT * ln(1.0 + userCount)
        if (bigramCount > 0) s += BIGRAM_WEIGHT * ln(1.0 + bigramCount)
        return s
    }

    // ---- Kullanıcı verisi yardımcıları ----

    private fun obtainUserWord(w: String): UserWord {
        val existing = userWords[w]
        if (existing != null) return existing
        if (userWords.size >= MAX_USER_WORDS) pruneUserWords(PRUNE_USER_WORDS_TO)
        val created = UserWord(w, foldLower(w), baseIndexOf(w))
        userWords[w] = created
        return created
    }

    private fun obtainBigram(prev: String, next: String, nextBase: Int): Bigram {
        val existing = bigrams[prev]?.get(next)
        if (existing != null) return existing
        if (bigramTotal >= MAX_BIGRAMS) pruneBigrams(PRUNE_BIGRAMS_TO)
        val inner = bigrams.getOrPut(prev) { HashMap<String, Bigram>(4) }
        val created = Bigram(prev, next, nextBase)
        inner[next] = created
        bigramTotal++
        return created
    }

    /** En az kullanılanları (eşitlikte en eski) atarak kelime sayısını [target]'a indirir. */
    private fun pruneUserWords(target: Int) {
        val excess = userWords.size - target
        if (excess <= 0) return
        val all = ArrayList<UserWord>(userWords.values)
        all.sortWith(wordsLeastUsedFirst)
        for (j in 0 until excess) {
            val uw = all[j]
            userWords.remove(uw.word)
            if (uw.baseIndex >= 0) baseUserCounts[uw.baseIndex] = 0
        }
    }

    /** En az görülenleri (eşitlikte en eski) atarak bigram sayısını [target]'a indirir. */
    private fun pruneBigrams(target: Int) {
        val excess = bigramTotal - target
        if (excess <= 0) return
        val all = ArrayList<Bigram>(bigramTotal)
        for (inner in bigrams.values) all.addAll(inner.values)
        all.sortWith(bigramsLeastUsedFirst)
        val n = min(excess, all.size)
        for (j in 0 until n) {
            val b = all[j]
            val inner = bigrams[b.prev] ?: continue
            if (inner.remove(b.next) != null) {
                bigramTotal--
                if (inner.isEmpty()) bigrams.remove(b.prev)
            }
        }
    }

    // ---- Paketlenmiş temel sözlük üzerinde arama ----

    /** Katlanmış hali [key]'den küçük olmayan ilk temel kelimenin indeksi. */
    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = baseCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareFoldedAt(mid, key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** [from] = lowerBound(key) iken katlanmış hali [key] ile başlayan aralığın sonu (hariç). */
    private fun prefixEnd(from: Int, key: String): Int {
        var lo = from
        var hi = baseCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (startsWithFoldedAt(mid, key)) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** [w] (tr-TR küçük harf) temel sözlükteyse indeksi, değilse -1. */
    private fun baseIndexOf(w: String): Int {
        if (baseCount == 0 || w.isEmpty()) return -1
        val key = foldLower(w)
        var i = lowerBound(key)
        while (i < baseCount && compareFoldedAt(i, key) == 0) {
            if (baseOffsets[i + 1] - baseOffsets[i] == w.length && rawPrefixAt(i, w)) return i
            i++
        }
        return -1
    }

    /** i. kelimenin katlanmış hali ile katlanmış [key]'in sözlük sırası karşılaştırması. */
    private fun compareFoldedAt(i: Int, key: String): Int {
        val start = baseOffsets[i]
        val len = baseOffsets[i + 1] - start
        val n = if (len < key.length) len else key.length
        for (k in 0 until n) {
            val c = foldChar(baseChars[start + k])
            val d = key[k]
            if (c != d) return c - d
        }
        return len - key.length
    }

    private fun startsWithFoldedAt(i: Int, key: String): Boolean {
        val start = baseOffsets[i]
        if (baseOffsets[i + 1] - start < key.length) return false
        for (k in key.indices) {
            if (foldChar(baseChars[start + k]) != key[k]) return false
        }
        return true
    }

    /** i. kelime katlanmadan (yalnız tr-TR küçük harf) [prefix] ile başlıyor mu. */
    private fun rawPrefixAt(i: Int, prefix: String): Boolean {
        val start = baseOffsets[i]
        if (baseOffsets[i + 1] - start < prefix.length) return false
        for (k in prefix.indices) {
            if (baseChars[start + k] != prefix[k]) return false
        }
        return true
    }

    private fun baseWordAt(i: Int): String {
        val start = baseOffsets[i]
        return String(baseChars, start, baseOffsets[i + 1] - start)
    }

    companion object {
        private val TR: Locale = Locale.forLanguageTag("tr-TR") // Locale("tr", "TR") ile aynı

        private const val MAX_WORD_LENGTH = 32
        private const val MIN_NEW_WORD_COUNT = 2
        private const val MIN_BIGRAM_PREDICT_COUNT = 2
        private const val MAX_USER_WORDS = 5000
        private const val MAX_BIGRAMS = 10000
        private const val PRUNE_USER_WORDS_TO = 4500
        private const val PRUNE_BIGRAMS_TO = 9000
        private const val COUNT_CAP = 1_000_000_000
        private const val MAX_TICK = 1_000_000_000_000_000L

        private const val USER_WEIGHT = 2.0
        private const val BIGRAM_WEIGHT = 4.0
        private val EXACT_PREFIX_BONUS = ln(1.5)

        private const val CASE_AS_PREFERRED = 0
        private const val CASE_CAPITALIZED = 1
        private const val CASE_UPPER = 2

        private const val PREF_CAPITALIZED: Byte = 1 // "İstanbul" = ilk harf büyük (tr-TR)
        private const val PREF_UPPER: Byte = 2 // "TV" = tamamı büyük (tr-TR)
        private const val PREF_MIXED: Byte = 3 // diğer ("iPhone"), tam yazım baseMixedPrefs'te

        private const val HEADER = "# klavye-userdata v2"
        private const val TAG_WORD = "w"
        private const val TAG_BIGRAM = "b"

        private fun foldChar(c: Char): Char {
            if (c < 'â') return c
            return when (c) {
                'ç' -> 'c' // ç
                'ğ' -> 'g' // ğ
                'ı' -> 'i' // ı
                'ö' -> 'o' // ö
                'ş' -> 's' // ş
                'ü' -> 'u' // ü
                'â' -> 'a' // â
                'î' -> 'i' // î
                'û' -> 'u' // û
                else -> c
            }
        }

        /** Zaten tr-TR küçük harfli metni katlar; değişiklik yoksa aynı nesneyi döndürür. */
        private fun foldLower(s: String): String {
            var k = 0
            while (k < s.length && foldChar(s[k]) == s[k]) k++
            if (k == s.length) return s
            val arr = s.toCharArray()
            for (j in k until arr.size) arr[j] = foldChar(arr[j])
            return String(arr)
        }

        private fun compareFolded(a: String, b: String): Int {
            val n = min(a.length, b.length)
            for (k in 0 until n) {
                val ca = foldChar(a[k])
                val cb = foldChar(b[k])
                if (ca != cb) return ca - cb
            }
            return a.length - b.length
        }

        /**
         * Kelimeyi tr-TR küçük harfe normalleştirir. Boş, harf içermeyen, 32 karakterden uzun
         * ya da boşluk/kontrol karakteri içeren girdiler için null.
         */
        private fun normalizeWord(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty()) return null
            val w = t.lowercase(TR)
            if (w.length > MAX_WORD_LENGTH) return null
            var hasLetter = false
            for (c in w) {
                if (c.isWhitespace() || c.isISOControl()) return null
                if (c.isLetter()) hasLetter = true
            }
            return if (hasLetter) w else null
        }

        private fun caseModeOf(s: String): Int {
            var hasLetter = false
            var hasLower = false
            for (c in s) {
                if (c.isLetter()) hasLetter = true
                if (c.isLowerCase()) hasLower = true
            }
            if (s.length >= 2 && hasLetter && !hasLower) return CASE_UPPER
            if (s.isNotEmpty() && s[0].isUpperCase()) return CASE_CAPITALIZED
            return CASE_AS_PREFERRED
        }

        /** [w] tercih edilen yazımdır; composing'in büyük harf durumuna göre gösterim biçimi. */
        private fun applyCase(w: String, mode: Int): String = when (mode) {
            CASE_UPPER -> w.uppercase(TR)
            CASE_CAPITALIZED -> capitalizeTr(w)
            else -> w
        }

        /** Yalnız ilk karakteri tr-TR büyük harfe çevirir ("istanbul" → "İstanbul"). */
        private fun capitalizeTr(w: String): String =
            if (w.isEmpty()) w else w.substring(0, 1).uppercase(TR) + w.substring(1)

        private fun incrementCount(c: Int): Int = if (c < COUNT_CAP) c + 1 else c

        private fun parseCount(s: String): Int? {
            val v = s.trim().toLongOrNull() ?: return null
            if (v <= 0L) return null
            return if (v > COUNT_CAP) COUNT_CAP else v.toInt()
        }

        private fun parseTick(s: String): Long? {
            val v = s.trim().toLongOrNull() ?: return null
            return if (v in 0L..MAX_TICK) v else null
        }
    }
}
