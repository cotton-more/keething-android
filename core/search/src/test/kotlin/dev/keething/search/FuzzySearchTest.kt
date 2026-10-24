package dev.keething.search

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac `FuzzySearchTests` expectations. */
class FuzzySearchTest {
    private fun boundaryOffsets(text: String) = FuzzyText(text).boundaries.withIndex().filter { it.value }.map { it.index }

    private fun score(term: String, text: String) = FuzzyMatcher.score(term, FuzzyText(text))

    // FuzzyText

    @Test
    fun wordStartsAndCamelCaseHumpsAreBoundaries() {
        assertEquals(listOf(true, false, false, true, false, false), FuzzyText("GitHub").boundaries.toList())
        assertEquals(listOf(0, 8), boundaryOffsets("nalogia.ru"))
        assertEquals(listOf(0, 7), boundaryOffsets("ILAY - Continentale"))
    }

    @Test
    fun caseAndDiacriticsAreFoldedLikeTheExactSearch() {
        assertEquals("suwag", String(FuzzyText("Süwag").characters))
        assertEquals("пятерочка", String(FuzzyText("Пятёрочка").characters))
    }

    @Test
    fun aFoldingThatChangesTheLengthBreaksNothing() {
        val text = FuzzyText("Straße")
        assertEquals("strasse", String(text.characters))
        assertEquals(text.characters.size, text.boundaries.size)
        assertNotNull(FuzzyMatcher.score("sse", text))
    }

    @Test
    fun veryLongFieldsAreCapped() {
        assertTrue(FuzzyText("a".repeat(5000)).characters.size <= 200)
    }

    // FuzzyMatcher

    @Test
    fun lettersMustAppearInOrder() {
        assertNotNull(score("gh", "GitHub"))
        assertNull(score("hg", "GitHub"))
        assertNull(score("zq", "GitHub"))
    }

    @Test
    fun aTermNeedsTwoLettersAndCannotBeLongerThanTheText() {
        assertNull(score("g", "GitHub"), "single letters are the exact search's job")
        assertNull(score("", "GitHub"))
        assertNull(score("githubx", "GitHub"))
    }

    @Test
    fun wordStartsScoreHigherThanLettersInTheMiddleOfAWord() {
        val boundary = score("hub", "GitHub")!!
        val middle = score("hub", "shubert")!!
        assertTrue(boundary > middle, "$boundary vs $middle")
    }

    @Test
    fun twoLettersOnlyCountAsInitials() {
        assertNotNull(score("gh", "GitHub"), "G and H both start a word")
        assertNotNull(score("ic", "ILAY - Continentale"))
        assertNull(score("nl", "nalogia"), "two letters in the middle of a word say too little")
        assertNull(score("ah", "Alpha"))
        assertNotNull(score("nlg", "nalogia"), "three letters are enough")
    }

    @Test
    fun consecutiveLettersScoreHigherThanScatteredOnes() {
        val together = score("abc", "abcxyz")!!
        val apart = score("abc", "axbxc")!!
        assertTrue(together > apart, "$together vs $apart")
    }

    @Test
    fun lettersSpreadFarApartDoNotMatch() {
        assertNull(score("abz", "a very long title with b somewhere and finally z"))
        assertNotNull(score("abz", "a b z"))
    }

    @Test
    fun aLaterTighterClusterIsFound() {
        assertNotNull(score("gtb", "great stuff at github"))
    }

    // EntrySearch with fuzzy matching

    @Test
    fun initialsFindAnEntry() {
        assertEquals(listOf("GitHub"), search(listOf(entry("GitHub"), entry("Bank")), "gh"))
        assertEquals(listOf("ILAY - Continentale"), search(listOf(entry("ILAY - Continentale"), entry("Bank")), "ic"))
    }

    @Test
    fun lettersInOrderFindAnEntry() {
        assertEquals(listOf("nalogia.ru"), search(listOf(entry("nalogia.ru", url = "https://nalogia.ru"), entry("Bank")), "nlgi"))
    }

    @Test
    fun exactMatchesComeBeforeFuzzyOnes() {
        assertEquals(listOf("GitHub", "Git Hub Mirror"), search(listOf(entry("Git Hub Mirror"), entry("GitHub")), "gith"))
    }

    @Test
    fun betterAlignmentsComeFirstAmongFuzzyMatches() {
        assertEquals(listOf("abxc", "axbxc"), search(listOf(entry("axbxc"), entry("abxc")), "abc"))
    }

    @Test
    fun aTwoLetterQueryFindsInitialsOnly() {
        val es = listOf(entry("GitHub"), entry("Alpha"), entry("Nalogia"), entry("Gunhilda"))
        assertEquals(listOf("GitHub"), search(es, "gh"))
        assertTrue(search(es, "nl").isEmpty())
    }

    @Test
    fun hostUsernameGroupAndTagsAreFuzzyFields() {
        assertEquals(listOf("Site"), search(listOf(entry("Site", url = "https://www.continentale.de/login")), "cntnl"))
        assertEquals(listOf("Site"), search(listOf(entry("Site", username = "octocat")), "octct"))
        assertEquals(listOf("Site"), search(listOf(entry("Site", group = listOf("Versicherung"))), "vrsch"))
        assertEquals(listOf("Site"), search(listOf(entry("Site", tags = listOf("finance"))), "fnnc"))
    }

    @Test
    fun notesAndCustomValuesAreExactOnly() {
        val e = entry("Alpha", notes = "alphabet soup", custom = listOf(CustomField.plain("Note", "alphabet soup")))
        assertTrue(search(listOf(e), "abs").isEmpty())
        assertEquals(listOf("Alpha"), search(listOf(e), "soup"), "exact matches in notes still work")
    }

    @Test
    fun secretsCanNeverBeFound() {
        val e = Entry(
            "1", title = "Vault", password = Secret.of("correcthorsebattery"),
            customFields = listOf(CustomField.protected("Code", "abracadabra-secret")),
            totp = TotpConfig(Secret.of("12345678901234567890")),
        )
        for (q in listOf("chb", "correcthorse", "abra-sec", "abrcdbr", "GEZDGNBV", "gezdg")) {
            assertTrue(search(listOf(e), q).isEmpty(), "'$q' must not find a secret")
        }
    }

    @Test
    fun everyTermMustMatchExactlyOrFuzzily() {
        val e = entry("GitHub", url = "https://github.com/login")
        assertEquals(listOf("GitHub"), search(listOf(e), "gh login"))
        assertTrue(search(listOf(e), "gh bogus").isEmpty())
    }

    @Test
    fun aSingleLetterIsExactOnly() {
        assertEquals(listOf("Quick"), search(listOf(entry("Quick")), "q"))
        assertTrue(search(listOf(entry("Quick")), "z").isEmpty())
    }

    @Test
    fun caseDiacriticsAndYoAreFolded() {
        assertEquals(listOf("Süwag"), search(listOf(entry("Süwag")), "swg"))
        assertEquals(listOf("Пятёрочка"), search(listOf(entry("Пятёрочка")), "пятр"))
        assertEquals(listOf("Пятёрочка"), search(listOf(entry("Пятёрочка")), "ПЯТР"))
    }

    @Test
    fun unrelatedEntriesAreNotDraggedIn() {
        val es = listOf(entry("GitHub"), entry("Bank of Somewhere"), entry("Netflix"), entry("Süwag"))
        assertEquals(listOf("GitHub"), search(es, "gh"))
        assertTrue(search(es, "xq").isEmpty())
    }

    @Test
    fun anEmptyIndexFindsNothing() {
        assertTrue(search(emptyList(), "git").isEmpty())
        assertTrue(search(emptyList(), "").isEmpty())
    }
}
