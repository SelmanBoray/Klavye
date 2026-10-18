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
