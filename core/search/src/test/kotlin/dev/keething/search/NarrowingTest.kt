package dev.keething.search

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Narrowing from the search (keething-spec search.narrowing): the last word typed against the names of the databases,
 * groups and tags. The Mac `NarrowingTests`' search cases (its window's cases are the app's `SearchChipsTest`).
 */
class NarrowingTest {
    private val personalEntries = listOf(
        entry("GitHub", group = listOf("Media"), tags = listOf("dev")),
        entry("Sparkasse", group = listOf("Finanzen", "Banken"), tags = listOf("bank")),
        entry("DKB", group = listOf("Finanzen", "Banken"), tags = listOf("bank", "finance")),
        entry("Steuer", group = listOf("Finanzen"), tags = listOf("finance")),
        entry("Netflix", group = listOf("Media"), notes = "finally cancelled"),
        entry("Router", group = listOf("Family Shared"), tags = listOf("GitHub")),
    )
    private val workEntries = listOf(
        entry("GitHub", group = listOf("Engineering"), tags = listOf("dev", "work")),
        entry("Firmenkonto", group = listOf("Finanzen"), tags = listOf("bank")),
        entry("Office", group = listOf("Office"), tags = listOf("work")),
    )
    private val both = EntrySearch.index(
        personalEntries + workEntries,
        englishCollator,
        databases = List(personalEntries.size) { "Personal" } + List(workEntries.size) { "Work" },
    )
    private val alone = EntrySearch.index(personalEntries, englishCollator, databases = List(personalEntries.size) { "Personal" })

    private fun names(index: SearchIndex, query: String): List<String> =
        EntrySearch.narrowing(index, query).map { "${it.kind.sigil}${it.name} ${it.count}" }

    private fun index(vararg entries: dev.keething.domain.vault.Entry) = EntrySearch.index(entries.toList(), englishCollator)

    @Test
    fun theStartOfANameCountedOverTheEntriesPassingTheFiltersNeverNotes() {
        assertEquals(listOf("@Finanzen 4", "#finance 2"), names(both, "fin"), "groups before tags; \"finally\" in notes is not a name")
        assertEquals(listOf("@Finanzen 4", "#finance 2"), names(both, "Fín"), "folded as the search folds")
        assertEquals(listOf("!Work 1", "#work 1"), names(both, "github wo"), "counted over what the other words keep too")
        assertEquals(emptyList(), names(both, "netflix fin"), "with the other words it would leave the list empty")
        assertEquals(listOf("@Finanzen 4", "#finance 2"), names(both, "fin "), "a space after it: still the last word")
    }

    @Test
    fun initialsAsTheSearchsEveryLetterAWordsStartFromTheNamesFirst() {
        assertEquals(listOf("@Family Shared 1"), names(both, "fs"))
        assertEquals(listOf("#GitHub 1"), names(both, "gh"), "a camelCase hump starts a word")
        assertEquals(emptyList(), names(alone, "sh"), "Shared is not the name's start, and its letters are not initials")
        assertEquals(emptyList(), names(both, "fnz"), "letters in order are not a good match: no loose fuzzy match")
        assertEquals(emptyList(), names(both, "nanzen"), "inside a name is not its start")
    }

    @Test
    fun oneLetterAFilterLastABareSignOrNothingTypedOfferNothing() {
        for (query in listOf("", " ", "f", "@fin", "fin @Work", "#", "fin !")) assertEquals(emptyList(), names(both, query), "“$query”")
        assertEquals(emptyList(), names(both, "zzz"), "a word matching no name")
    }

    @Test
    fun databasesFirstNoneWithOneDatabaseOrWhileOneIsChosen() {
        assertEquals(listOf("!Work 3", "#work 2"), names(both, "work"))
        assertEquals(emptyList(), names(alone, "pers"), "one database: narrowing to it narrows nothing")
        assertEquals(listOf("#work 2"), names(both, "!Work wo"), "Work's entries tagged work; no database")
        assertEquals(listOf("@Finanzen 1"), names(both, "!Work fin"), "only Work's groups, counted in Work")
        assertEquals(emptyList(), names(both, "!Wor pers"), "while a database is chosen no other is offered")
        assertEquals(listOf("!Personal 3"), names(both, "@Finanzen pers"), "the databases holding such entries")
        assertEquals(emptyList(), names(both, "@Engineering work"), "all of them in Work, and all tagged work: nothing narrows")
    }

    @Test
    fun noDatabaseWhileABangFilterIsTypedEvenOneThatWouldNarrow() {
        val index = EntrySearch.index(
            listOf(entry("A"), entry("B"), entry("C")),
            englishCollator,
            databases = listOf("Work", "Work", "Workshop"),
        )
        assertEquals(listOf("!Work 2", "!Workshop 1"), names(index, "work"))
        assertEquals(emptyList(), names(index, "!Wor work"), "Work would keep 2 of the 3 `!Wor` keeps: still not offered")
    }

    @Test
    fun whatIsOfferedFollowsTheFiltersTypedNothingThatLeavesNothingNothingThatKeepsAll() {
        assertEquals(listOf("#finance 1"), names(both, "#bank fin"), "every bank entry is in Finanzen: @Finanzen would narrow nothing")
        assertEquals(listOf("@Media 2"), names(both, "me"))
        assertEquals(emptyList(), names(both, "#bank me"), "Media holds no bank entries")
        assertEquals(listOf("@Engineering 1"), names(both, "#dev en"))
        assertEquals(listOf("@Banken 2", "#bank 3"), names(both, "@Finanzen ba"))
        assertEquals(listOf("#finance 2"), names(both, "@Finanzen fin"), "the filter already typed is not offered again")
    }

    @Test
    fun oneChipPerNameCountedAsTheFilterKeepsRankedByStartThenInitialsThenCount() {
        val index = index(
            entry("A", group = listOf("Bank")), entry("B", group = listOf("Banking")), entry("C", group = listOf("Old", "Banking")),
            entry("D", group = listOf("Bills Archive")), entry("E", group = listOf("Bills Archive")),
            entry("G", group = listOf("Bills Archive")), entry("H", group = listOf("Bills Archive")), entry("F", group = listOf("Other")),
        )
        assertEquals(
            listOf("@Bank 3", "@Banking 2", "@Bills Archive 4"),
            names(index, "ba"),
            "`@Bank` keeps Banking too; Banking at two places one chip; initials after starts, whatever their count",
        )
    }

    @Test
    fun atMostFourEveryKindWithItsBestBeforeASecondOfAnother() {
        val index = EntrySearch.index(
            listOf("Dev1", "Dev2", "Dev3", "Dev4", "Dev5").map { entry("T", group = listOf(it)) } +
                listOf(entry("T", tags = listOf("devops")), entry("U", group = listOf("Other"))),
            englishCollator,
        )
        val found = EntrySearch.narrowing(index, "dev")
        assertEquals(4, found.size)
        assertEquals(listOf(FilterKind.GROUP, FilterKind.GROUP, FilterKind.GROUP, FilterKind.TAG), found.map { it.kind })
        assertEquals("devops", found.last().name)
    }

    @Test
    fun aNameIsWhatItsFilterKeepsTrimmedAsTheFilterTrimsANameWithAQuoteIsNotOffered() {
        val index = index(
            entry("A", group = listOf("Bank ")), entry("B", group = listOf("Bankkonto")), entry("C", group = listOf(" Bausparen")),
            entry("D", group = listOf("Mom's \"Old\" Stuff")), entry("E", group = listOf("Other")),
        )
        assertEquals(
            listOf("@Bank 2", "@Bankkonto 1"),
            names(index, "ba"),
            "`@Bank` keeps Bankkonto too; no `@Bausparen`, which keeps nothing (\" Bausparen\" does not start so)",
        )
        assertEquals(emptyList(), names(index, "mo"), "its filter cannot be written back")
    }

    @Test
    fun eachChipCountsItsEntriesPerDatabase() {
        val finanzen = EntrySearch.narrowing(both, "fin").first()
        assertEquals(listOf(DatabaseCount("Personal", 3), DatabaseCount("Work", 1)), finanzen.databases)
        assertEquals(listOf(DatabaseCount("Personal", 2)), EntrySearch.narrowing(both, "fin")[1].databases, "#finance is Personal's alone")
        assertEquals(listOf(DatabaseCount("Work", 1)), EntrySearch.narrowing(both, "!Work fin").single().databases)
        assertEquals(emptyList(), EntrySearch.narrowing(index(entry("A", group = listOf("Finanzen")), entry("B")), "fin").single().databases, "one vault: none")
    }

    @Test
    fun applyingReplacesTheLastWordWithTheFilterQuotedWhenNeededAndASpace() {
        assertEquals("@Finanzen ", FilterCompletion.narrow("fin", FilterKind.GROUP, "Finanzen"))
        assertEquals("github @Finanzen ", FilterCompletion.narrow("github fin ", FilterKind.GROUP, "Finanzen"))
        assertEquals("#bank @\"Family Shared\" ", FilterCompletion.narrow("#bank fs", FilterKind.GROUP, "Family Shared"))
        assertEquals("!Work ", FilterCompletion.narrow("work", FilterKind.DATABASE, "Work"))
        assertEquals("work @Fin", FilterCompletion.narrow("work @Fin", FilterKind.DATABASE, "Work"), "no word last: unchanged")
    }
}
