package com.selman.klavye.suggest

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Suggester olusturur. create() hemen doner; sozluk ve kullanici verisi tek, kisa omurlu
 * bir arka plan thread'inde yuklenir. Motor yalniz ana thread'de kullanilir.
 */
object SuggesterFactory {
    fun create(context: Context): Suggester {
        val s = DeferredSuggester(context.applicationContext)
        s.start()
        return s
    }
}

private const val TAG = "Klavye"
private const val DICT_ASSET = "dict/tr.txt"
private const val USER_DATA_FILE = "suggest_user.dat"
private const val MAX_WORDS = 60000
private const val MAX_PENDING = 200

private class DeferredSuggester(private val appContext: Context) : Suggester {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Asagidaki alanlara yalniz ana thread'den dokunulur.
    private var engine: SuggestionEngine? = null
    private val pending = ArrayDeque<Pair<String, String?>>()
    private var dirty = false
    private var writeSeq = 0L

    // Yazma thread'leri arasi siralama.
    private val writeLock = Any()
    private var lastWrittenSeq = 0L

    fun start() {
        val t = Thread({ loadInBackground() }, "klavye-dict-load")
        t.isDaemon = true
        t.start()
    }

    private fun loadInBackground() {
        val t0 = SystemClock.elapsedRealtime()
        var built: SuggestionEngine? = null
        try {
            val words = readDictionary()
            val eng = try {
                SuggestionEngine(words)
            } catch (e: Throwable) {
                Log.w(TAG, "SuggestionEngine kurulamadi, bos sozlukle denenecek: $e")
                SuggestionEngine(emptyList())
            }
            try {
                val f = File(appContext.filesDir, USER_DATA_FILE)
                if (f.exists()) {
                    val data = AtomicFile(f).readFully().toString(Charsets.UTF_8)
                    if (data.isNotEmpty()) eng.importUserData(data)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Kullanici verisi okunamadi: $e")
            }
            built = eng
            Log.i(TAG, "Oneri motoru yuklendi: ${SystemClock.elapsedRealtime() - t0} ms, ${words.size} kelime")
        } catch (e: Throwable) {
            Log.w(TAG, "Motor yuklenemedi: $e")
        }
        val ready = built
        mainHandler.post { publish(ready) }
    }

    private fun readDictionary(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>(MAX_WORDS)
        try {
            appContext.assets.open(DICT_ASSET).use { ins ->
                val reader = BufferedReader(InputStreamReader(ins, Charsets.UTF_8), 64 * 1024)
                while (out.size < MAX_WORDS) {
                    val line = reader.readLine() ?: break
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val freq = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                    val word = line.substring(0, tab)
                    if (word.isEmpty()) continue
                    out.add(Pair(word, freq))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Sozluk okunamadi ($DICT_ASSET): $e")
        }
        return out
    }

    // Ana thread
    private fun publish(ready: SuggestionEngine?) {
        val eng = ready ?: try {
            SuggestionEngine(emptyList())
        } catch (e: Throwable) {
            Log.w(TAG, "Bos motor da kurulamadi: $e")
            pending.clear()
            return
        }
        engine = eng
        while (pending.isNotEmpty()) {
            val (w, p) = pending.removeFirst()
            try {
                eng.onWordCommitted(w, p)
                dirty = true
            } catch (e: Throwable) {
                Log.w(TAG, "Bekleyen kelime uygulanamadi: $e")
            }
        }
    }

    override fun suggest(composing: String, previousWord: String?, limit: Int): List<String> {
        val eng = engine ?: return emptyList()
        return eng.suggest(composing, previousWord, limit)
    }

    override fun onWordCommitted(word: String, previousWord: String?) {
        val eng = engine
        if (eng == null) {
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(Pair(word, previousWord))
            return
        }
        eng.onWordCommitted(word, previousWord)
        dirty = true
    }

    override fun flush() {
        val eng = engine ?: return
        if (!dirty) return
        val data = try {
            eng.exportUserData()
        } catch (e: Throwable) {
            Log.w(TAG, "exportUserData basarisiz: $e")
            return
        }
        dirty = false
        val seq = ++writeSeq
        val file = File(appContext.filesDir, USER_DATA_FILE)
        val t = Thread({
            var ok = true
            synchronized(writeLock) {
                if (seq > lastWrittenSeq) {
                    val af = AtomicFile(file)
                    var out: java.io.FileOutputStream? = null
                    try {
                        out = af.startWrite()
                        out.write(data.toByteArray(Charsets.UTF_8))
                        af.finishWrite(out)
                        lastWrittenSeq = seq
                    } catch (e: Throwable) {
                        ok = false
                        Log.w(TAG, "Kullanici verisi yazilamadi: $e")
                        if (out != null) af.failWrite(out)
                    }
                }
            }
            if (!ok) mainHandler.post { dirty = true }
        }, "klavye-flush")
        t.isDaemon = true
        t.start()
    }
}
