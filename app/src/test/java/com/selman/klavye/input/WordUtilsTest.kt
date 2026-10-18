package com.selman.klavye.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordUtilsTest {

    // ---- isWordChar ----

    @Test
    fun isWordChar_letters_true() {
        assertTrue(WordUtils.isWordChar('a'.code))
        assertTrue(WordUtils.isWordChar('ş'.code))
        assertTrue(WordUtils.isWordChar('İ'.code))
        assertTrue(WordUtils.isWordChar('ğ'.code))
    }

    @Test
    fun isWordChar_digit_true() {
        assertTrue(WordUtils.isWordChar('5'.code))
    }

    @Test
    fun isWordChar_separators_false() {
        assertFalse(WordUtils.isWordChar(' '.code))
        assertFalse(WordUtils.isWordChar('\n'.code))
    }

    @Test
    fun isWordChar_apostrophes_false() {
        assertFalse(WordUtils.isWordChar('\''.code))
        assertFalse(WordUtils.isWordChar('’'.code))
    }

    @Test
    fun isWordChar_hyphenAndPunctuation_false() {
        assertFalse(WordUtils.isWordChar('-'.code))
        assertFalse(WordUtils.isWordChar('.'.code))
        assertFalse(WordUtils.isWordChar(','.code))
        assertFalse(WordUtils.isWordChar('!'.code))
    }

    @Test
    fun isWordChar_emoji_false() {
        assertFalse(WordUtils.isWordChar(0x1F600))
    }

    // ---- currentWord ----

    @Test
    fun currentWord_simple() {
        assertEquals("nas", WordUtils.currentWord("merhaba nas"))
    }

    @Test
    fun currentWord_trailingSpace_empty() {
        assertEquals("", WordUtils.currentWord("merhaba "))
    }

    @Test
    fun currentWord_emptyInput_empty() {
        assertEquals("", WordUtils.currentWord(""))
    }

    @Test
    fun currentWord_apostropheSplitsWord() {
        assertEquals("da", WordUtils.currentWord("İstanbul'da"))
    }

    @Test
    fun currentWord_typographicApostropheSplitsWord() {
        assertEquals("da", WordUtils.currentWord("İstanbul’da"))
    }

    @Test
    fun currentWord_endsWithEmoji_empty() {
        assertEquals("", WordUtils.currentWord("abc😀"))
    }

    @Test
    fun currentWord_afterEmoji() {
        assertEquals("ab", WordUtils.currentWord("x 😀ab"))
    }

    @Test
    fun currentWord_turkishCharacters() {
        assertEquals("çğış", WordUtils.currentWord("çğış"))
    }

    @Test
    fun currentWord_wholeTextIsWord() {
        assertEquals("merhaba", WordUtils.currentWord("merhaba"))
    }

    @Test
    fun currentWord_emojiInMiddle() {
        assertEquals("def", WordUtils.currentWord("abc💩def"))
    }

    // ---- previousWord ----

    @Test
    fun previousWord_basic() {
        assertEquals("merhaba", WordUtils.previousWord("merhaba nas"))
    }

    @Test
    fun previousWord_trailingSpace() {
        assertEquals("merhaba", WordUtils.previousWord("merhaba "))
    }

    @Test
    fun previousWord_commaIsNotSentenceEnd() {
        assertEquals("merhaba", WordUtils.previousWord("merhaba, nas"))
    }

    @Test
    fun previousWord_periodIsSentenceEnd() {
        assertNull(WordUtils.previousWord("merhaba. nas"))
    }

    @Test
    fun previousWord_exclamationAndQuestionAndEllipsis() {
        assertNull(WordUtils.previousWord("merhaba! nas"))
        assertNull(WordUtils.previousWord("merhaba? nas"))
        assertNull(WordUtils.previousWord("merhaba… nas"))
    }

    @Test
    fun previousWord_newlineIsBoundary() {
        assertNull(WordUtils.previousWord("merhaba\nnas"))
    }

    @Test
    fun previousWord_singleWord_null() {
        assertNull(WordUtils.previousWord("nas"))
    }

    @Test
    fun previousWord_empty_null() {
        assertNull(WordUtils.previousWord(""))
    }

    @Test
    fun previousWord_multipleSpaces() {
        assertEquals("güzel", WordUtils.previousWord("  çok   güzel "))
    }

    @Test
    fun previousWord_threeWords() {
        assertEquals("hava", WordUtils.previousWord("bugün hava çok"))
    }

    @Test
    fun previousWord_apostropheSplitsWord() {
        assertEquals("da", WordUtils.previousWord("İstanbul'da hava"))
    }

    @Test
    fun previousWord_preservesCase() {
        assertEquals("Merhaba", WordUtils.previousWord("Merhaba Nas"))
        assertEquals("İSTANBUL", WordUtils.previousWord("İSTANBUL güzel"))
    }

    @Test
    fun previousWord_emojiIsInSentenceSeparator() {
        assertEquals("abc", WordUtils.previousWord("abc💩def"))
    }

    @Test
    fun previousWord_emojiWithSpaces() {
        assertEquals("abc", WordUtils.previousWord("abc 😀 def"))
    }
}
