package com.selman.klavye.input

/**
 * İmleçten önceki metinden kelime çıkarma yardımcıları. Saf Kotlin, android.* yok.
 * Sondan geriye tarar; code point bazlıdır (surrogate çiftleri bölünmez).
 */
object WordUtils {

    fun isWordChar(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint)

    fun currentWord(textBeforeCursor: CharSequence): String {
        val start = wordStart(textBeforeCursor, textBeforeCursor.length)
        return textBeforeCursor.subSequence(start, textBeforeCursor.length).toString()
    }

    fun previousWord(textBeforeCursor: CharSequence): String? {
        val len = textBeforeCursor.length
        var i = wordStart(textBeforeCursor, len)
        // Mevcut kelimeden önceki ayırıcıları atla; cümle sonu / satır sonu varsa null.
        while (i > 0) {
            val cp = Character.codePointBefore(textBeforeCursor, i)
            if (isWordChar(cp)) break
            if (isSentenceBreak(cp)) return null
            i -= Character.charCount(cp)
        }
        if (i <= 0) return null
        val start = wordStart(textBeforeCursor, i)
        if (start >= i) return null
        return textBeforeCursor.subSequence(start, i).toString()
    }

    /** Kelime kelime silmede (silme tuşu basılı) imleçten geriye kaç UTF-16 karakter silineceği. */
    fun wordDeleteLength(textBeforeCursor: CharSequence): Int {
        val len = textBeforeCursor.length
        if (len == 0) return 0
        if (textBeforeCursor[len - 1] == '\n') return 1

        var i = len
        // Sondaki yatay boşlukları atla (satır sonu hariç).
        while (i > 0) {
            val cp = Character.codePointBefore(textBeforeCursor, i)
            if (cp == '\n'.code || !Character.isWhitespace(cp)) break
            i -= Character.charCount(cp)
        }
        if (i == 0) return len
        // Boşluklardan önce satır sonu varsa yalnızca boşluklar silinir.
        if (i < len && textBeforeCursor[i - 1] == '\n') return len - i

        val cp = Character.codePointBefore(textBeforeCursor, i)
        if (isWordChar(cp)) {
            i = wordStart(textBeforeCursor, i)
            // Kesme işaretli ekler tek seferde gider: "Ankara'ya"
            while (i > 1 && isApostrophe(textBeforeCursor[i - 1]) &&
                isWordChar(Character.codePointBefore(textBeforeCursor, i - 1))
            ) {
                i = wordStart(textBeforeCursor, i - 1)
            }
        } else {
            // Noktalama / emoji / sembol dizisi (ZWJ ve ten rengi dizileri dahil).
            while (i > 0) {
                val c = Character.codePointBefore(textBeforeCursor, i)
                if (isWordChar(c) || Character.isWhitespace(c)) break
                i -= Character.charCount(c)
            }
        }
        return len - i
    }

    private fun isApostrophe(c: Char): Boolean = c == '\'' || c == '’'

    private fun wordStart(text: CharSequence, end: Int): Int {
        var i = end
        while (i > 0) {
            val cp = Character.codePointBefore(text, i)
            if (!isWordChar(cp)) break
            i -= Character.charCount(cp)
        }
        return i
    }

    private fun isSentenceBreak(cp: Int): Boolean = when (cp) {
        '.'.code, '!'.code, '?'.code, '…'.code, '\n'.code, '\r'.code,
        ' '.code, ' '.code, '\u0085'.code -> true
        else -> false
    }
}
