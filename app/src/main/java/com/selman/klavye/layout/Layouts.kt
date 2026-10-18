package com.selman.klavye.layout

/**
 * SwiftKey "qwerty (turkish)" düzeninin ve ortak sembol sayfalarının olgularına (tuş, genişlik,
 * popup karakterleri) göre düzenler. Kaynak genişlikler klavye genişliğinin %'si; bu dosyada
 * birim = % x 0.12 (12 birim = %100). Geometri sabitleri tek yerde ve yüzde cinsinden okunur.
 */
object Layouts {

    // ---- Ölçek ----
    private const val U = 0.12f                       // 1 yüzde puanı = 0.12 birim
    private fun p(percent: Float): Float = percent * U

    private const val ROW_STEP_DP = 52.7f             // telefonda ölçüldü
    private const val NUMBER_ROW_RATIO = 0.85f        // dosyadaki satır ağırlığı

    // ---- Yardımcılar ----
    /** Boşlukla ayrılmış popup karakterlerini listeye çevirir. */
    private fun pop(s: String): List<String> =
        if (s.isEmpty()) emptyList() else s.split(' ')

    private fun ch(
        label: String,
        width: Float,
        longPress: List<String> = emptyList(),
        hint: String? = null
    ) = Key(KeyType.CHAR, label, label, width, longPress, hint)

    private fun special(type: KeyType, label: String, width: Float) =
        Key(type, label, label, width)

    /** Harf tuşu: popup LETTER_POPUP'tan (ipucu yok). */
    private fun letters(s: String, width: Float) = s.map { c ->
        val l = c.toString()
        ch(l, width, pop(LETTER_POPUP[l] ?: ""))
    }

    /** Sembol sayfası tuşları: tek karakterlik etiketler, popup SYM_POPUP'tan. */
    private fun syms(s: String, width: Float) = s.map { c ->
        val l = c.toString()
        ch(l, width, pop(SYM_POPUP[l] ?: ""))
    }

    // ---- Popup tabloları ----
    // Harf tuşları (sayı satırı açık varyantı): önce düzenin kendi popup'ı, sonra tuş olarak
    // bulunmayan aksanlar (a->â, i->î, u->û).
    private val LETTER_POPUP: Map<String, String> = mapOf(
        "q" to "%", "w" to "\\", "e" to "~", "r" to "|", "t" to "[",
        "y" to "]", "u" to "< û", "ı" to ">", "o" to "{", "p" to "}",
        "ğ" to "©", "ü" to "™",
        "a" to "@ â", "s" to "#", "d" to "&", "f" to "*", "g" to "-",
        "h" to "+", "j" to "=", "k" to "(", "l" to ")", "ş" to "×", "i" to "÷ î",
        "z" to "_", "x" to "₺ \$ £ € ¢ ¥", "c" to "\"", "v" to "'", "b" to ":",
        "n" to ";", "m" to "/", "ö" to "^", "ç" to "\$"
    )

    // Sembol sayfaları (Türkçe için boş olan label_* girdileri atlandı; ":" için ";" alındı).
    private val SYM_POPUP: Map<String, String> = mapOf(
        "1" to "¹ ½ ⅓ ¼ ⅛", "2" to "² ⅔", "3" to "³ ¾ ⅜", "4" to "⁴", "5" to "ⁿ ⅝", "7" to "⅞",
        "₺" to "\$ £ € ¢ ¥",
        "-" to "— ¯ –", "(" to "< { [", ")" to "> } ]", "=" to "≠ ≈", "%" to "‰",
        "\"" to "“ „ ” » «", "*" to "×", "'" to "` ʼ › ‹ † ‡", ":" to ";",
        "/" to "\\ ÷", "!" to "¡", "?" to "¿", "+" to "±", "." to "… •"
    )

    // ---- TURKISH_Q ----
    // Satır toplamları (birim):
    //   sayı satırı   10 x 1.2                               = 12.0
    //   1. harf sırası 12 x 0.984 + 0.096 + 0.096            = 12.0
    //   2. harf sırası 11 x 0.984 + 0.588 + 0.588            = 12.0
    //   3. harf sırası 1.572 + 9 x 0.984 + 1.572             = 12.0
    //   alt sıra      1.2 + 1.2 + 1.2 + 5.4 + 1.2 + 1.8      = 12.0
    val TURKISH_Q: KeyboardLayout = KeyboardLayout(
        id = "tr_qwerty",
        rowHeightDp = ROW_STEP_DP,
        rows = listOf(
            KeyRow(
                "1234567890".map { c -> ch(c.toString(), p(10f)) },
                heightRatio = NUMBER_ROW_RATIO
            ),
            KeyRow(letters("qwertyuıopğü", p(8.2f)), leftGap = p(0.8f), rightGap = p(0.8f)),
            KeyRow(letters("asdfghjklşi", p(8.2f)), leftGap = p(4.9f), rightGap = p(4.9f)),
            KeyRow(
                listOf(Key(KeyType.SHIFT, "", "", p(11f + 2.1f), visualPadRight = p(2.1f))) +
                    letters("zxcvbnmöç", p(8.2f)) +
                    listOf(Key(KeyType.DELETE, "", "", p(11f + 2.1f), visualPadLeft = p(2.1f)))
            ),
            KeyRow(
                listOf(
                    special(KeyType.MODE_SYMBOLS, "123", p(10f)),
                    special(KeyType.EMOJI, "☺", p(10f)),
                    ch(",", p(10f)),
                    special(KeyType.SPACE, "Türkçe", p(45f)),
                    ch(".", p(10f), pop("! ? ,"), hint = ",!?"),
                    special(KeyType.ENTER, "", p(15f))
                )
            )
        )
    )

    // ---- SYMBOLS (123 sayfası, "symbols_top_row") ----
    // Satır toplamları: 10 x 1.2 = 12.0 (x3 sıra) / 1.56 + 1.44 + 6.0 + 1.44 + 1.56 = 12.0
    val SYMBOLS: KeyboardLayout = KeyboardLayout(
        id = "symbols",
        rowHeightDp = ROW_STEP_DP,
        rows = listOf(
            KeyRow(syms("1234567890", p(10f))),
            KeyRow(syms("@#₺&_-()=%", p(10f))),
            KeyRow(
                listOf(special(KeyType.MODE_SYMBOLS_2, "{&=", p(10f))) +
                    syms("\"*':/!?+", p(10f)) +
                    listOf(special(KeyType.DELETE, "", p(10f)))
            ),
            bottomRow(KeyType.MODE_ALPHA, "abc")
        )
    )

    // ---- SYMBOLS_2 ("symbols_alt", {&= ile açılan sayfa) ----
    // Satır toplamları:
    //   1. 13 + 6 x 10.5 + 11 + 13 = 100 -> 12.0
    //   2. 13 + 6 x 10.5 + 11 + 13 = 100 -> 12.0
    //   3. 13 + 6 x 10.5 + 11 + 13 = 100 -> 12.0
    //   4. 13 + 31.5 + 3 x 10.5 + 11 + 13 = 100 -> 12.0
    val SYMBOLS_2: KeyboardLayout = KeyboardLayout(
        id = "symbols2",
        rowHeightDp = ROW_STEP_DP,
        rows = listOf(
            KeyRow(
                listOf(ch("£", p(13f))) +
                    syms("€¥¢©®™", p(10.5f)) +
                    listOf(ch("~", p(11f)), ch("¿", p(13f)))
            ),
            KeyRow(
                // Tab: Model'de TAB tuş tipi yok; CHAR olarak "\t" çıktısı veriyor.
                listOf(Key(KeyType.CHAR, "⇥", "\t", p(13f))) +
                    syms("[]{}<>", p(10.5f)) +
                    listOf(ch("^", p(11f)), ch("¡", p(13f)))
            ),
            KeyRow(
                listOf(special(KeyType.MODE_SYMBOLS, "123", p(13f))) +
                    syms("`;÷\\|¦", p(10.5f)) +
                    listOf(ch("¬", p(11f)), special(KeyType.DELETE, "", p(13f)))
            ),
            KeyRow(
                listOf(
                    special(KeyType.MODE_ALPHA, "abc", p(13f)),
                    special(KeyType.SPACE, "", p(31.5f))
                ) + syms("×§¶", p(10.5f)) +
                    listOf(ch("°", p(11f)), special(KeyType.ENTER, "", p(13f)))
            )
        )
    )

    /** 123 sayfasının alt sırası: ABC 13 · , 12 · boşluk 50 · . 12 · enter 13. */
    private fun bottomRow(modeType: KeyType, modeLabel: String) = KeyRow(
        listOf(
            special(modeType, modeLabel, p(13f)),
            ch(",", p(12f)),
            special(KeyType.SPACE, "", p(50f)),
            ch(".", p(12f), pop(SYM_POPUP["."] ?: "")),
            special(KeyType.ENTER, "", p(13f))
        )
    )
}
