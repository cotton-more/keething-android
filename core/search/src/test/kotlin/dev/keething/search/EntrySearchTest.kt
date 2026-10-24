package dev.keething.search

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac `EntrySearchTests` expectations. */
class EntrySearchTest {
    // Hand-built entries mirroring fixtures/sample.xml.
    private val vault = listOf(
        Entry(
            "1", title = "GitHub", username = "octocat", password = Secret.of("gh-secret-pw"),
            url = "https://github.com/login", notes = "Work account", groupPath = listOf("Work"), tags = listOf("dev", "work"),
            customFields = listOf(CustomField.protected("Recovery code", "abcd-efgh-ijkl"), CustomField.plain("Org", "Acme GmbH")),
            totp = TotpConfig(Secret.of("12345678901234567890")),
        ),
        Entry(
            "2", title = "ILAY - Continentale", username = "user@example.com", password = Secret.of("ilay-pw"),
            url = "https://continentale.de", groupPath = listOf("Versicherung"), tags = listOf("versicherung", "kind"),
            customFields = listOf(CustomField.plain("Versicherungsnummer", "VN-123456")),
        ),
        Entry(
            "3", title = "Пятёрочка", username = "ivan", password = Secret.of("pyat-pw"), url = "https://5ka.ru",
            notes = "Карта лояльности", groupPath = listOf("Shopping"), tags = listOf("магазин"),
        ),
        Entry("4", title = "Süwag", notes = "Vertragsnummer 4711 — nur Notiz", groupPath = listOf("Verträge")),
        Entry(
            "5", title = "Legacy 2FA", username = "legacy", password = Secret.of("legacy-pw"),
            url = "https://legacy.example", groupPath = listOf("Work", "Git"),
        ),
    )

    private fun hits(query: String) = search(vault, query)

    @Test
    fun titleSubstringCaseInsensitive() {
        val es = listOf(entry("GitHub"), entry("Bank"))
        assertEquals(listOf("GitHub"), search(es, "git"))
        assertEquals(listOf("GitHub"), search(es, "GITHUB"))
    }

    @Test
    fun diacriticsAndYoAreFolded() {
        assertEquals(listOf("Süwag"), hits("suwag"))
        assertEquals(listOf("Пятёрочка"), hits("пятерочка"))
        assertEquals(1, hits("vertrage").size, "group 'Verträge' is found without the umlaut")
    }

    @Test
    fun matchesUsernameUrlTagsGroupNotes() {
        assertEquals(listOf("GitHub"), hits("octocat"))
        assertEquals(listOf("Пятёрочка"), hits("5ka.ru"))
        assertEquals(listOf("ILAY - Continentale"), hits("kind"))
        assertEquals(listOf("Пятёрочка"), hits("shopping"))
        assertEquals(listOf("Süwag"), hits("4711"))
    }

    @Test
    fun matchesCustomFieldNamesAndPlainValues() {
        assertEquals(listOf("ILAY - Continentale"), hits("versicherungsnummer"))
        assertEquals(listOf("ILAY - Continentale"), hits("VN-123456"))
    }

    @Test
    fun neverMatchesSecrets() {
        assertTrue(hits("gh-secret-pw").isEmpty(), "passwords are not searchable")
        assertTrue(hits("abcd-efgh").isEmpty(), "protected custom values are not searchable")
        assertTrue(hits("GEZDGNBV").isEmpty(), "TOTP secrets are not searchable")
    }

    @Test
    fun rankingTitlePrefixThenTitleContainsThenOtherFields() {
        val es = listOf(entry("Foo", url = "https://git.example.com"), entry("My Git tool"), entry("Git"))
        assertEquals(listOf("Git", "My Git tool", "Foo"), search(es, "git"))
    }

    @Test
    fun allTermsMustMatchAcrossFields() {
        assertEquals(listOf("GitHub"), hits("git octocat"))
        assertTrue(hits("git shopping").isEmpty())
    }

    @Test
    fun everyGroupInANestedPathIsSearchable() {
        assertEquals(listOf("GitHub", "Legacy 2FA"), hits("git work"))
    }

    @Test
    fun anEmptyQueryReturnsAllByTitle() {
        val es = listOf(entry("banana"), entry("Apple"), entry("cherry"))
        assertEquals(listOf("Apple", "banana", "cherry"), search(es, ""))
        assertEquals(listOf("Apple", "banana", "cherry"), search(es, "   "))
    }

    @Test
    fun noMatch() {
        assertTrue(search(listOf(entry("GitHub")), "zzz").isEmpty())
    }

    // @group / #tag filters

    @Test
    fun groupFilterKeepsEntriesInTheGroupAndItsSubgroupsByNamePrefix() {
        assertEquals(listOf("GitHub", "Legacy 2FA"), hits("@work"))
        assertEquals(listOf("Legacy 2FA"), hits("@Git"), "a nested group works on its own")
        assertEquals(listOf("GitHub", "Legacy 2FA"), hits("@wo"), "a prefix is enough")
        assertEquals(listOf("Süwag"), hits("@vertrage"), "folded like the rest of the search")
        assertTrue(hits("@ork").isEmpty(), "the name must start with the filter")
    }

    @Test
    fun tagFilterKeepsEntriesWithATagStartingWithIt() {
        assertEquals(listOf("GitHub"), hits("#dev"))
        assertEquals(listOf("ILAY - Continentale"), hits("#versich"))
        assertEquals(listOf("Пятёрочка"), hits("#магазин"))
        assertTrue(hits("#work #kind").isEmpty(), "every filter must hold")
    }

    @Test
    fun filtersCombineWithTermsAndEachOther() {
        assertEquals(listOf("Legacy 2FA"), hits("@work legacy"))
        assertEquals(listOf("Legacy 2FA"), hits("legacy @work"), "order does not matter")
        assertEquals(listOf("GitHub"), hits("@work #dev"))
        assertTrue(hits("@shopping git").isEmpty())
        assertEquals(listOf("GitHub"), hits("@work gh"), "fuzzy terms still work inside a filter")
    }

    @Test
    fun aQuotedValueMayContainSpaces() {
        val es = listOf(entry("A", group = listOf("Family Shared")), entry("B", group = listOf("Family")))
        assertEquals(listOf("A"), search(es, "@\"Family Shared\""))
        assertEquals(listOf("A"), search(es, "@\"family sh"), "an unclosed quote runs to the end")
        assertEquals(listOf("A", "B"), search(es, "@Family"))
    }

    @Test
    fun aBareSigilFiltersNothingAndTheFilterTextIsNotSearched() {
        assertEquals(vault.size, hits("@").size)
        assertEquals(listOf("GitHub"), hits("# octocat"))
        assertEquals(hits("@work"), hits("@work "), "a trailing space changes nothing")
    }

    @Test
    fun rankingUsesTheTermsWithoutTheFilters() {
        val es = listOf(entry("Foo", url = "https://git.example", group = listOf("W")), entry("Git", group = listOf("W")))
        assertEquals(listOf("Git", "Foo"), search(es, "@w git"))
    }

    // +attachment filter

    @Test
    fun attachmentFilter() {
        val es = listOf(
            entry("Server", attachments = listOf("id_ed25519", "KeeAgent.settings")),
            entry("Passport", attachments = listOf("Scan Seite 1.pdf")),
            entry("Plain"),
        )
        assertEquals(listOf("Passport", "Server"), search(es, "+"))
        assertEquals(listOf("Passport", "Server"), search(es, "+ "))
        assertEquals(listOf("Server"), search(es, "+id_"))
        assertEquals(listOf("Server"), search(es, "+keeagent"), "folded like tags")
        assertEquals(listOf("Passport"), search(es, "+\"scan seite"), "quoted names with spaces")
        assertEquals(listOf("Passport"), search(es, "+ pass"), "combines with terms")
        assertTrue(search(es, "+zzz").isEmpty())
        assertTrue(search(es, "+pdf").isEmpty(), "the name must start with it")
    }

    @Test
    fun aPlusBeforeADigitIsATermNotAFilter() {
        val es = listOf(entry("Doctor", notes = "+49 30 1234"), entry("File", attachments = listOf("4.pdf")))
        assertEquals(listOf("Doctor"), search(es, "+49"))
    }

    // The recycle bin

    @Test
    fun aQueryStartingWithMinusSearchesTheRecycleBin() {
        assertEquals("", EntrySearch.recycleBinQuery("-"))
        assertEquals("shop", EntrySearch.recycleBinQuery(" -shop"))
        assertEquals(" @Work shop", EntrySearch.recycleBinQuery("- @Work shop"))
        assertNull(EntrySearch.recycleBinQuery("e-mail"))
        assertNull(EntrySearch.recycleBinQuery(""))
    }
}
