package dev.keething.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `!database` filter and the facets counted per query (keething-spec search.database-filter, search.suggestions
 * AC6, AC8): the Mac `EntrySearchTests`' "!database filter" and "Facets per query" cases.
 */
class DatabaseFilterTest {
    // Two databases, as the list indexes them: each entry with its database's label.
    private val personal = listOf(entry("GitHub", group = listOf("Finanzen", "Banken"), tags = listOf("bank")), entry("Netflix", group = listOf("Media")))
    private val work = listOf(entry("GitHub", group = listOf("Engineering"), tags = listOf("dev")), entry("Sparkasse", group = listOf("Finanzen"), tags = listOf("bank")))
    private val both = EntrySearch.index(
        personal + work,
        englishCollator,
        databases = List(personal.size) { "Personal" } + List(work.size) { "Work" },
    )

    private fun found(query: String): List<String> =
        EntrySearch.filter(both, query).map { e -> (if (personal.any { it.id == e.id }) "P:" else "W:") + e.title }

    private fun titles(labels: List<String>, query: String): List<String> {
        val entries = labels.indices.map { entry(('A' + it).toString()) }
        return EntrySearch.filter(EntrySearch.index(entries, englishCollator, databases = labels), query).map { it.title }
    }

    @Test
    fun aDatabaseFilterKeepsTheEntriesOfTheDatabasesWhoseLabelStartsWithIt() {
        assertEquals(listOf("W:GitHub", "W:Sparkasse"), found("!work"))
        assertEquals(listOf("P:GitHub", "P:Netflix"), found("!Pers"), "a prefix, folded like the rest")
        assertEquals(listOf("W:GitHub"), found("!work git"), "with terms")
        assertEquals(listOf("P:GitHub", "W:GitHub"), found("git"), "without it, both databases are searched")
        assertEquals(emptyList(), found("!orK"), "the label must start with it")
        assertEquals(emptyList(), found("!Family"), "a database not open keeps nothing")
    }

    @Test
    fun aDatabaseFilterCombinesWithTheOthersInAnyOrderTwoDatabasesKeepNothing() {
        assertEquals(listOf("W:Sparkasse"), found("@Finanzen #bank !Work"))
        assertEquals(listOf("W:Sparkasse"), found("!Work @Finanzen #bank"))
        assertEquals(listOf("P:GitHub"), found("#bank !p"))
        assertEquals(emptyList(), found("!Work !Personal"), "an entry is in one database")
        assertEquals(listOf("P:GitHub", "P:Netflix"), found("!Per !Personal"), "every filter holds: both name Personal")
    }

    @Test
    fun aBareBangFiltersNothingABangInsideAWordIsATermAQuotedLabelMayHaveSpaces() {
        assertEquals(4, found("!").size)
        assertEquals(listOf("P:Netflix"), found("! net"))
        assertEquals(listOf("A"), titles(listOf("Family Shared", "Personal · Personal-2024.kdbx"), "!\"Family Shared\""))
        assertEquals(listOf("B"), titles(listOf("Family Shared", "Personal · Personal-2024.kdbx"), "!\"personal · personal-2"), "the whole label, as a suggestion puts it")
        assertEquals(listOf("hi!"), search(listOf(entry("hi!"), entry("x")), "hi!"))
    }

    @Test
    fun aWholeLabelIsThatDatabaseOnlyEvenWhenItStartsAnothersAPartKeepsEveryOneItStarts() {
        val labels = listOf("Work", "Workshop", "Personal · ~/Sync", "Personal · ~/Sync/Old")
        assertEquals(listOf("A"), titles(labels, "!Work"), "the suggestion's or the chip's whole label")
        assertEquals(listOf("A"), titles(labels, "!work "), "folded")
        assertEquals(listOf("A", "B"), titles(labels, "!Wor"))
        assertEquals(listOf("C"), titles(labels, "!\"Personal · ~/Sync\""), "names alike told apart by their folders")
        assertEquals(listOf("C", "D"), titles(labels, "!Personal"))
        val index = EntrySearch.index(labels.map { entry(it) }, englishCollator, databases = labels)
        assertEquals(listOf("Work"), EntrySearch.facets(index, "!Work ").databases.map { it.name }, "nothing else offered after it")
        val two = listOf("Work", "Workshop")
        assertTrue(EntrySearch.names("Work", "!Work ", two))
        assertFalse(EntrySearch.names("Workshop", "!Work ", two))
        assertTrue(EntrySearch.names("Workshop", "!Wor ", two))
        assertFalse(EntrySearch.names("Work", "work", two), "no ! filter: the search names no database")
    }

    @Test
    fun aWholeLabelOfAnOpenDatabaseWithoutEntriesHereStillKeepsThatDatabaseOnly() {
        // Work's bin is empty, Workshop's holds one: `- !Work` keeps nothing (AD10's review).
        val bin = EntrySearch.index(listOf(entry("X")), englishCollator, databases = listOf("Workshop"), labels = listOf("Work", "Workshop"))
        assertEquals(emptyList(), EntrySearch.filter(bin, "!Work").map { it.title })
        assertEquals(listOf("X"), EntrySearch.filter(bin, "!Wor").map { it.title })
        assertEquals(emptyList(), EntrySearch.facets(bin, "!Work").databases, "nothing offered after it either")
    }

    @Test
    fun withoutDatabasesOneVaultADatabaseFilterKeepsNothing() {
        val vault = listOf(entry("GitHub", group = listOf("Work")), entry("Bank"))
        assertEquals(emptyList(), search(vault, "!work"))
        assertEquals(2, search(vault, "!").size)
    }

    @Test
    fun bareSignsAloneAreNothingToSearchFor() {
        for (query in listOf("", "  ", "!", "@", "# ", "@ # !", "@\"\"")) assertTrue(EntrySearch.isEmpty(query), "“$query”")
        for (query in listOf("!w", "@w", "x", "hi!")) assertFalse(EntrySearch.isEmpty(query), "“$query”")
        assertFalse(EntrySearch.isEmpty("+"), "a bare + asks for any attachment: it filters (search.suggestions AC3)")
    }

    // Facets per query (keething-spec search.suggestions AC6, AC8)

    @Test
    fun facetsAreCountedOverTheEntriesPassingTheFiltersTheTermsAside() {
        val all = EntrySearch.facets(both, "")
        assertEquals(listOf("Personal", "Work"), all.databases.map { it.name })
        assertEquals(listOf(2, 2), all.databases.map { it.count })
        assertEquals(listOf("Personal", "Work"), EntrySearch.facets(both, "@Finanzen #bank").databases.map { it.name }, "both hold such entries")
        assertEquals(listOf("Work"), EntrySearch.facets(both, "@Engineering").databases.map { it.name })
        val inWork = EntrySearch.facets(both, "!Work")
        assertEquals(listOf("Engineering", "Finanzen"), inWork.groups.map { it.name }, "only Work's groups")
        assertEquals(listOf("bank", "dev"), inWork.tags.map { it.name })
        assertEquals(listOf(listOf("Finanzen"), listOf("Finanzen", "Banken")), EntrySearch.facets(both, "#bank").groups.map { it.path })
        assertEquals(listOf(2, 1), EntrySearch.facets(both, "#bank").groups.map { it.count })
        assertEquals(listOf(2, 2), EntrySearch.facets(both, "git").databases.map { it.count }, "terms do not narrow the facets")
        assertTrue(EntrySearch.facets(both, "!Family").groups.isEmpty(), "nothing passes: nothing is offered")
    }

    @Test
    fun aNameInTwoDatabasesIsOneWithACountPerDatabase() {
        val facets = EntrySearch.facets(both, "")
        val finanzen = facets.groups.single { it.path == listOf("Finanzen") }
        assertEquals(2, finanzen.count)
        assertEquals(listOf(DatabaseCount("Personal", 1), DatabaseCount("Work", 1)), finanzen.databases)
        assertEquals(listOf(1, 1), facets.tags.single { it.name == "bank" }.databases.map { it.count })
        assertEquals(listOf(DatabaseCount("Personal", 1)), facets.groups.single { it.path == listOf("Media") }.databases)
        assertTrue(SearchFacets.of(personal).groups.all { it.databases.isEmpty() }, "one vault: no counts per database")
    }

    @Test
    fun aGroupWithoutEntriesIsNotOfferedTheWholeVaultsFacetsStillListIt() {
        val index = EntrySearch.index(listOf(entry("A", group = listOf("Work"))), englishCollator)
        assertEquals(listOf("Work"), EntrySearch.facets(index, "").groups.map { it.name })
        assertEquals(
            listOf("Old", "Work"),
            SearchFacets.of(listOf(entry("A", group = listOf("Work"))), englishCollator, groupPaths = listOf(listOf("Work"), listOf("Old"))).groups.map { it.name },
        )
    }

    @Test
    fun theDatabasesAfterABangStartWithWhatIsTyped() {
        assertEquals(listOf("Work"), EntrySearch.facets(both, "").databases("wo").map { it.name })
        assertEquals(listOf("Personal", "Work"), EntrySearch.facets(both, "").databases("").map { it.name })
    }

    @Test
    fun theFilterBeingTypedIsLeftOutOfWhatTheSuggestionsAreCountedOver() {
        assertEquals("!Work ", FilterCompletion.withoutActiveFilter("!Work @Fi"))
        assertEquals("git !Work ", FilterCompletion.withoutActiveFilter("git !Work "), "nothing active: as it is")
        assertEquals("", FilterCompletion.withoutActiveFilter("!Wo"))
        val active = FilterCompletion.activeFilter("@Fin !Wo")
        assertEquals(FilterKind.DATABASE, active?.kind)
        assertEquals("Wo", active?.partial)
        assertEquals("@Fin !\"Family Shared\" ", FilterCompletion.complete("@Fin !Fam", "Family Shared"))
    }
}
