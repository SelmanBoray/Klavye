package com.selman.klavye.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random

class SuggestionEngineTest {

    private val fixture: List<Pair<String, Int>> = listOf(
        "ve" to 5000,
        "bir" to 4800,
        "çok" to 3000,
        "gel" to 900,
        "geliyor" to 800,
        "gelecek" to 700,
        "merhaba" to 600,
        "naber" to 550,
        "nasılsın" to 500,
        "istanbul" to 400,
        "ışık" to 300,
        "şimdi" to 250,
        "güzel" to 200
    )

    private fun newEngine() = SuggestionEngine(fixture)

    private fun learnAll(e: SuggestionEngine) {
        // Test 8
        e.onWordCommitted("selmanboray", null)
        e.onWordCommitted("selmanboray", null)
        // Test 9
        e.onWordCommitted("nasılsın", "merhaba")
        e.onWordCommitted("nasılsın", "merhaba")
        // Test 10
        repeat(5) { e.onWordCommitted("güzel", null) }
    }

    private fun assertLearned(e: SuggestionEngine) {
        assertTrue(e.suggest("selm", null).contains("selmanboray"))
        assertTrue(e.suggest("", "merhaba").contains("nasılsın"))
        assertEquals("nasılsın", e.suggest("na", "merhaba")[0])
        assertEquals("güzel", e.suggest("g", null)[0])
    }

    @Test
    fun foldedPrefixMatchesTurkishCharacter_cokGivesCok() {
        assertEquals("çok", newEngine().suggest("cok", null)[0])
    }

    @Test
    fun gelReturnsThreeResultsAllStartingWithGelFirstIsGel() {
        val r = newEngine().suggest("gel", null, 3)
        assertEquals(3, r.size)
        assertTrue(r.all { it.startsWith("gel") })
        assertEquals("gel", r[0])
    }

    @Test
    fun capitalizedFirstLetterIsPreserved_MerGivesMerhaba() {
        assertEquals("Merhaba", newEngine().suggest("Mer", null)[0])
    }

    @Test
    fun allUppercaseComposingGivesTurkishUppercase_ISTGivesISTANBUL() {
        assertEquals("İSTANBUL", newEngine().suggest("İST", null)[0])
    }

    @Test
    fun dotlessAndDottedIFoldToSamePrefix_isContainsIstanbulAndIsik() {
        val r = newEngine().suggest("is", null)
        assertTrue(r.contains("istanbul"))
        assertTrue(r.contains("ışık"))
    }

    @Test
    fun sFoldsToSedilla_simGivesSimdi() {
        assertEquals("şimdi", newEngine().suggest("sim", null)[0])
    }

    @Test
    fun emptyComposingWithoutLearningGivesEmpty() {
        val e = newEngine()
        assertTrue(e.suggest("", null).isEmpty())
        assertTrue(e.suggest("", "merhaba").isEmpty())
    }

    @Test
    fun unknownWordSuggestedOnlyAfterTwoCommits() {
        val e = newEngine()
        e.onWordCommitted("selmanboray", null)
        assertFalse(e.suggest("selm", null).contains("selmanboray"))
        e.onWordCommitted("selmanboray", null)
        assertTrue(e.suggest("selm", null).contains("selmanboray"))
    }

    @Test
    fun bigramSuggestedWhenComposingEmptyAndBoostsPrefixMatchAfterTwoOccurrences() {
        val e = newEngine()
        assertEquals("naber", e.suggest("na", "merhaba")[0])
        e.onWordCommitted("nasılsın", "merhaba")
        e.onWordCommitted("nasılsın", "merhaba")
        assertTrue(e.suggest("", "merhaba").contains("nasılsın"))
        assertEquals("nasılsın", e.suggest("na", "merhaba")[0])
    }

    @Test
    fun userUsageDominatesBaseFrequency_guzelOvertakesGel() {
        val e = newEngine()
        assertEquals("gel", e.suggest("g", null)[0])
        repeat(5) { e.onWordCommitted("güzel", null) }
        assertEquals("güzel", e.suggest("g", null)[0])
    }

    @Test
    fun exportThenImportIntoFreshEngineRestoresLearning() {
        val e = newEngine()
        learnAll(e)
        assertLearned(e)
        val data = e.exportUserData()
        val e2 = newEngine()
        e2.importUserData(data)
        assertLearned(e2)
    }

    @Test
    fun importGarbageDoesNotThrowAndBaseStillWorks() {
        val e = newEngine()
        e.importUserData("çöp\n\t\t\nxx yy zz\n\u0000")
        assertEquals("çok", e.suggest("cok", null)[0])
    }

    @Test
    fun limitIsRespectedAndResultsHaveNoDuplicates() {
        val e = newEngine()
        assertEquals(2, e.suggest("g", null, 2).size)
        for (q in listOf("g", "gel", "i", "is", "n", "na", "m", "s", "b", "")) {
            for (prev in listOf<String?>(null, "merhaba")) {
                val r = e.suggest(q, prev, 5)
                assertEquals("duplicates for '$q'/$prev: $r", r.size, r.toSet().size)
                assertTrue(r.size <= 5)
            }
        }
    }

    @Test
    fun committingCapitalizedTurkishIIsMergedWithBaseEntry_noDuplicate() {
        val e = newEngine()
        repeat(3) { e.onWordCommitted("İstanbul", null) }
        val r = e.suggest("ist", null)
        assertEquals(r.toString(), 1, r.count { it == "istanbul" })
        assertEquals(r.toString(), r.size, r.toSet().size)
    }

    private val casedFixture: List<Pair<String, Int>> = listOf(
        "ve" to 5000,
        "İstanbul" to 400,
        "istasyon" to 300,
        "Ankara" to 200,
        "ankette" to 50
    )

    private fun newCasedEngine() = SuggestionEngine(casedFixture)

    @Test
    fun preferredSpellingKept_istGivesIstanbulWithDottedCapitalIAndIstasyonNotLowercaseIstanbul() {
        val r = newCasedEngine().suggest("ist", null)
        assertTrue(r.toString(), r.contains("İstanbul"))
        assertTrue(r.toString(), r.contains("istasyon"))
        assertFalse(r.toString(), r.contains("istanbul"))
    }

    @Test
    fun capitalIsPrefixUsesTurkishCasing_IstGivesIstanbulAndIstasyonWithDottedI() {
        val r = newCasedEngine().suggest("Ist", null)
        assertTrue(r.toString(), r.contains("İstanbul"))
        assertTrue(r.toString(), r.contains("İstasyon"))
        assertEquals(r.toString(), 2, r.size)
    }

    @Test
    fun allUppercasePrefixUppercasesPreferredSpelling_ANKGivesANKARAandANKETTE() {
        val r = newCasedEngine().suggest("ANK", null)
        assertTrue(r.toString(), r.contains("ANKARA"))
        assertTrue(r.toString(), r.contains("ANKETTE"))
    }

    @Test
    fun committingLowercaseVariantKeepsPreferredSpelling_istanbulStaysIstanbul() {
        val e = newCasedEngine()
        repeat(3) { e.onWordCommitted("istanbul", null) }
        val r = e.suggest("ist", null)
        assertEquals(r.toString(), "İstanbul", r[0])
        assertFalse(r.toString(), r.contains("istanbul"))
    }

    @Test
    fun lowercasePrefixKeepsInitialCapitalOfProperNoun_ankGivesAnkaraAndAnkette() {
        val r = newCasedEngine().suggest("ank", null)
        assertTrue(r.toString(), r.contains("Ankara"))
        assertTrue(r.toString(), r.contains("ankette"))
    }

    @Test
    fun performanceSmoke_60kWordsBuildAndQuery() {
        val letters = "abcçdefgğhıijklmnoöprsştuüvyz"
        val rnd = Random(12345)
        val seen = HashSet<String>()
        val words = ArrayList<Pair<String, Int>>(60_000)
        var freq = 1_000_000
        while (words.size < 60_000) {
            val len = 2 + rnd.nextInt(9)
            val sb = StringBuilder()
            repeat(len) { sb.append(letters[rnd.nextInt(letters.length)]) }
            val w = sb.toString()
            if (seen.add(w)) {
                words.add(w to freq)
                if (freq > 1) freq -= 16
                if (freq < 1) freq = 1
            }
        }

        val t0 = System.nanoTime()
        val engine = SuggestionEngine(words)
        val buildMs = (System.nanoTime() - t0) / 1_000_000.0
        System.out.println("SuggestionEngine build 60k: $buildMs ms")
        assertTrue("build too slow: $buildMs ms", buildMs < 1500.0)

        val qr = Random(777)
        val queries = List(1000) {
            val len = 1 + qr.nextInt(3)
            val sb = StringBuilder()
            repeat(len) { sb.append(letters[qr.nextInt(letters.length)]) }
            sb.toString()
        }
        engine.suggest("a", null) // warm-up
        val t1 = System.nanoTime()
        for (q in queries) engine.suggest(q, null)
        val avgMs = (System.nanoTime() - t1) / 1_000_000.0 / queries.size
        System.out.println("SuggestionEngine avg suggest: $avgMs ms over ${queries.size} queries")
        assertTrue("suggest too slow: $avgMs ms", avgMs < 5.0)
    }
}
