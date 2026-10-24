package dev.keething.search

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac `SearchComponentTests` expectations. */
class SearchComponentTest {
    private fun matches(query: String, entry: Entry) = SearchQuery(query).match(SearchIndex.item(entry, englishCollator)) != null

    // Normalizer

    @Test
    fun normalizerLowercasesStripsDiacriticsFoldsYo() {
        assertEquals("suwag", SearchNormalizer.normalize("Süwag"))
        assertEquals("пятерочка", SearchNormalizer.normalize("Пятёрочка"))
        assertEquals("github", SearchNormalizer.normalize("GitHub"))
        assertEquals("cafe nandu", SearchNormalizer.normalize("Café Ñandú"))
        assertEquals("strasse", SearchNormalizer.normalize("Straße"))
    }

    @Test
    fun normalizerIsIdempotent() {
        for (s in listOf("Süwag", "ЁЖ", "plain")) {
            assertEquals(SearchNormalizer.normalize(s), SearchNormalizer.normalize(SearchNormalizer.normalize(s)))
        }
    }

    // Query

    @Test
    fun termsAreSplitOnAnyWhitespaceAndNormalised() {
        val q = SearchQuery("  Git \t WORK\nSüwag ")
        assertEquals(listOf("git", "work", "suwag"), q.terms)
        assertEquals("git \t work\nsuwag", q.phrase)
        assertFalse(q.isEmpty)
    }

    @Test
    fun blankInputIsEmpty() {
        assertTrue(SearchQuery("").isEmpty)
        assertTrue(SearchQuery(" \n\t ").isEmpty)
    }

    @Test
    fun everyTermMustMatchSomewhere() {
        val e = entry("GitHub", username = "octocat", group = listOf("Work"))
        assertTrue(matches("git octo", e))
        assertTrue(matches("work GITHUB", e))
        assertFalse(matches("git shopping", e))
    }

    @Test
    fun aTermCannotSpanTwoFields() {
        val e = entry("ab", username = "cd")
        assertFalse(matches("abcd", e))
        assertFalse(matches("bcd", e))
    }

    // Searchable text

    private val rich = Entry(
        "1", title = "T", username = "U", password = Secret.of("PWD-SECRET"), url = "https://u.example", notes = "N",
        groupPath = listOf("G1", "G2"), tags = listOf("tag1", "tag2"),
        customFields = listOf(CustomField.plain("Plain name", "plain value"), CustomField.protected("Hidden name", "HIDDEN-VALUE")),
        totp = TotpConfig(Secret.of("12345678901234567890")),
    )

    @Test
    fun searchableTextContainsThePublicFields() {
        val text = SearchableText.of(rich)
        for (part in listOf("T", "U", "https://u.example", "N", "tag1", "tag2", "G1", "G2", "Plain name", "plain value", "Hidden name")) {
            assertTrue(part in text, "missing '$part'")
        }
    }

    @Test
    fun searchableTextNeverContainsSecrets() {
        val text = SearchableText.of(rich)
        assertFalse("PWD-SECRET" in text, "password")
        assertFalse("HIDDEN-VALUE" in text, "protected custom value")
        assertFalse("12345678901234567890" in text, "TOTP secret (raw)")
        assertFalse("GEZDGNBVGY3TQOJQ" in text, "TOTP secret (base32)")
    }

    // Ranking

    @Test
    fun rankerTitlePrefixTitleContainsEverythingElse() {
        val q = SearchQuery("git")
        assertEquals(SearchRank.TITLE_PREFIX, SearchRank.of(SearchNormalizer.normalize("GitHub"), q))
        assertEquals(SearchRank.TITLE_CONTAINS, SearchRank.of(SearchNormalizer.normalize("My Git tool"), q))
        assertEquals(SearchRank.OTHER, SearchRank.of(SearchNormalizer.normalize("Foo"), q))
        assertTrue(SearchRank.TITLE_PREFIX < SearchRank.TITLE_CONTAINS && SearchRank.TITLE_CONTAINS < SearchRank.OTHER)
        assertTrue(SearchRank.OTHER < SearchRank.FUZZY)
    }

    @Test
    fun rankerUsesTheFullPhrase() {
        val q = SearchQuery("git hub")
        assertEquals(SearchRank.TITLE_PREFIX, SearchRank.of("git hub", q))
        assertEquals(SearchRank.OTHER, SearchRank.of("github", q))
        assertEquals(SearchRank.TITLE_PREFIX, SearchRank.of(SearchNormalizer.normalize("Süwag"), SearchQuery("suw")))
    }

    // Tokenizer and completion

    @Test
    fun tokenizerTermsFiltersAndQuotedValues() {
        assertEquals(
            listOf(
                Token.Content.Term("git"), Token.Content.Filter(FilterKind.GROUP, "Work"), Token.Content.Filter(FilterKind.TAG, "dev"),
                Token.Content.Filter(FilterKind.GROUP, "Family Shared"), Token.Content.Term("x"),
            ),
            QueryTokenizer.tokens(" git @Work #dev @\"Family Shared\" x").map { it.content },
        )
        assertEquals(listOf(Token.Content.Term("a@b.com")), QueryTokenizer.tokens("a@b.com").map { it.content })
        assertEquals(listOf(Token.Content.Filter(FilterKind.GROUP, "open quote")), QueryTokenizer.tokens("@\"open quote").map { it.content })
    }

    @Test
    fun filtersAreSeparatedFromTermsAndNormalised() {
        val q = SearchQuery("Git @Wörk #DEV @")
        assertEquals(listOf("git"), q.terms)
        assertEquals(listOf("work"), q.groupFilters)
        assertEquals(listOf("dev"), q.tagFilters)
        assertEquals("git", q.phrase)
        assertTrue(!SearchQuery("@w").isEmpty && SearchQuery("@").isEmpty)
    }

    @Test
    fun theFilterBeingTypedAtTheEndIsActive() {
        assertEquals("Wo", FilterCompletion.activeFilter("git @Wo")?.partial)
        assertEquals(FilterKind.GROUP, FilterCompletion.activeFilter("git @Wo")?.kind)
        assertEquals(FilterKind.TAG, FilterCompletion.activeFilter("#")?.kind)
        assertEquals("", FilterCompletion.activeFilter("#")?.partial)
        assertEquals("Family ", FilterCompletion.activeFilter("@\"Family ")?.partial, "inside an open quote")
        assertNull(FilterCompletion.activeFilter("@Work "), "finished by a space")
        assertNull(FilterCompletion.activeFilter("@\"Family Shared\""), "finished by the closing quote")
        assertNull(FilterCompletion.activeFilter("@Work git"))
        assertNull(FilterCompletion.activeFilter(""))
    }

    @Test
    fun completionReplacesTheTypedFilterQuotesNamesWithSpacesAddsASpace() {
        assertEquals("git @Work ", FilterCompletion.complete("git @wo", "Work"))
        assertEquals("@\"Family Shared\" ", FilterCompletion.complete("@fam", "Family Shared"))
        assertEquals("#dev ", FilterCompletion.complete("#", "dev"))
        assertEquals("git", FilterCompletion.complete("git", "dev"), "nothing active, nothing changes")
        assertEquals("+ ", FilterCompletion.complete("+", ""), "choosing any attachment keeps the bare +")
        assertEquals("+notes.txt ", FilterCompletion.complete("+no", "notes.txt"))
    }

    // Facets

    @Test
    fun groupFacetsInTreeOrderWithCountsIncludingSubgroups() {
        val facets = SearchFacets.of(
            listOf(
                entry("a", group = listOf("Work", "Git")), entry("b", group = listOf("Work")), entry("c", group = listOf("bank")),
                entry("d"), entry("e", group = listOf("Work", "Admin")),
            ),
            englishCollator,
        )
        assertEquals(listOf(listOf("bank"), listOf("Work"), listOf("Work", "Admin"), listOf("Work", "Git")), facets.groups.map { it.path })
        assertEquals(listOf(1, 3, 1, 1), facets.groups.map { it.count })
        assertEquals(listOf("Git"), facets.groups("gi").map { it.name })
        assertEquals(4, facets.groups("").size)
    }

    @Test
    fun namesStartingWithWhatIsTypedFirstThenFuzzyOnesByScoreNothingTypedNone() {
        assertEquals(listOf("Banking", "bank"), FuzzyNames.matching("ba", listOf("Travel", "Banking", "bank", "Shared")))
        assertEquals(listOf("Banking"), FuzzyNames.matching("bnk", listOf("Travel", "Banking", "Work")))
        assertEquals(emptyList(), FuzzyNames.matching(" ", listOf("a")))
    }

    @Test
    fun groupPathsGivenAddTheEmptyGroupsInTreeOrder() {
        val facets = SearchFacets.of(
            listOf(entry("a", group = listOf("Work", "Git"))),
            englishCollator,
            groupPaths = listOf(emptyList(), listOf("Work"), listOf("Work", "Git"), listOf("Work", "Archive"), listOf("bank")),
        )
        assertEquals(listOf(listOf("bank"), listOf("Work"), listOf("Work", "Archive"), listOf("Work", "Git")), facets.groups.map { it.path })
        assertEquals(listOf(0, 1, 0, 1), facets.groups.map { it.count }, "the root group's empty path is left out")
    }

    @Test
    fun tagFacetsWithCountsMergedAcrossCaseAndDiacritics() {
        val facets = SearchFacets.of(
            listOf(entry("a", tags = listOf("Dev", "work")), entry("b", tags = listOf("dev")), entry("c", tags = listOf("Café")), entry("d", tags = listOf("cafe", "CAFE"))),
            englishCollator,
        )
        assertEquals(listOf("Café", "Dev", "work"), facets.tags.map { it.name })
        assertEquals(listOf(2, 2, 1), facets.tags.map { it.count }, "an entry counts once per tag")
        assertEquals(listOf("Dev"), facets.tags("de").map { it.name })
    }

    @Test
    fun attachmentFacetsWithCountsAndHowManyEntriesHaveAny() {
        val facets = SearchFacets.of(
            listOf(entry("a", attachments = listOf("id_rsa", "notes.txt")), entry("b", attachments = listOf("ID_RSA")), entry("c")),
            englishCollator,
        )
        assertEquals(listOf("id_rsa", "notes.txt"), facets.attachments.map { it.name })
        assertEquals(listOf(2, 1), facets.attachments.map { it.count })
        assertEquals(2, facets.entriesWithAttachments)
        assertEquals(listOf("notes.txt"), facets.attachments("no").map { it.name })
    }
}
