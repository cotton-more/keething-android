package dev.keething.android.list

import dev.keething.android.databases.DatabaseLabel
import dev.keething.android.databases.DatabaseMark
import dev.keething.android.ui.english
import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
import dev.keething.vault.databases.DatabaseId
import java.text.Collator
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchChipsTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val english = Collator.getInstance(Locale.ENGLISH)

    private val listing = VaultListing(
        Vault(
            entries = listOf(
                Entry(id = "gh", title = "GitHub", username = "octocat", password = Secret.of("pw"), groupPath = listOf("Work", "Dev"), tags = listOf("code"), attachments = listOf(Attachment("key.txt", 3))),
                Entry(id = "jira", title = "Jira", groupPath = listOf("Work"), tags = listOf("Code", "office")),
                Entry(id = "spk", title = "Sparkasse", groupPath = listOf("Family Finance")),
            ),
            recycleBin = listOf(Entry(id = "old", title = "Old Router", groupPath = listOf("Recycle Bin", "Home"))),
            recycleBinGroup = VaultGroup("BIN", "Recycle Bin", listOf("Recycle Bin")),
        ),
        now,
        english,
        unnamedCode = UNNAMED_CODE,
    )

    private fun chips(query: String, codesOnly: Boolean = false) = searchChips(query, listing, codesOnly).chips

    // Two databases open (keething-spec search.database-filter AC5, search.suggestions AC8, search.narrowing).
    private val personalMark = DatabaseMark(DatabaseId("P"), DatabaseLabel("Personal"), 0)
    private val workMark = DatabaseMark(DatabaseId("W"), DatabaseLabel("Work"), 1)
    private val personal = Vault(
        entries = listOf(
            Entry(id = "gh", title = "GitHub", groupPath = listOf("Media"), tags = listOf("dev")),
            Entry(id = "spk", title = "Sparkasse", groupPath = listOf("Finanzen", "Banken"), tags = listOf("bank")),
            Entry(id = "tv", title = "Netflix", groupPath = listOf("Media")),
            Entry(id = "rt", title = "Router", groupPath = listOf("Family Shared")),
        ),
        recycleBin = listOf(Entry(id = "old", title = "Old Finanzamt", groupPath = listOf("Recycle Bin", "Finanzen"))),
        recycleBinGroup = VaultGroup("BIN", "Recycle Bin", listOf("Recycle Bin")),
    )
    private val work = Vault(
        entries = listOf(
            Entry(id = "gh", title = "GitHub", groupPath = listOf("Engineering"), tags = listOf("dev", "work")),
            Entry(id = "fk", title = "Firmenkonto", groupPath = listOf("Finanzen"), tags = listOf("bank")),
            Entry(id = "of", title = "Office", groupPath = listOf("Office"), tags = listOf("work")),
        ),
    )
    private val both = VaultListing(
        listOf(ListedDatabase(personalMark.id, personal, personalMark), ListedDatabase(workMark.id, work, workMark)),
        now,
        english,
        unnamedCode = UNNAMED_CODE,
    )

    /** Personal alone, as the list indexes one open database: its label, no mark. */
    private val alone = VaultListing(listOf(ListedDatabase(personalMark.id, personal, label = "Personal")), now, english, unnamedCode = UNNAMED_CODE)

    private fun line(listing: VaultListing, query: String, codesOnly: Boolean = false) = searchChips(query, listing, codesOnly)

    @Test
    fun nothingTypedOffersTheFourFilters() {
        val chips = chips("")

        assertEquals(listOf("@", "#", "+", "-"), chips.map { it.query })
        assertEquals(listOf('@', '#', '+', '-'), chips.map { it.sigil })
        assertFalse(chips.any { it.selected })
    }

    @Test
    fun aFilterIsAddedAfterWhatIsTyped() {
        assertEquals("git @", chips("git").first().query)
        assertEquals("git @", chips("git ").first().query)
    }

    @Test
    fun theBinChipTogglesTheBinSearch() {
        assertEquals("- git", chips("git").last().query)
        val inBin = chips("-git").last()
        assertTrue(inBin.selected)
        assertEquals("git", inBin.query)
        assertEquals("", chips("-").last().query)
    }

    @Test
    fun theCodesListHasNoBin() {
        assertEquals(listOf("@", "#", "+"), chips("", codesOnly = true).map { it.query })
    }

    @Test
    fun typingAGroupOffersTheGroupsInTreeOrderWithTheirPaths() {
        val chips = chips("@")

        assertEquals(listOf("Family Finance", "Work", "Work › Dev"), chips.map { it.label.english() })
        assertEquals(listOf(1, 2, 1), chips.map { it.count })
        assertEquals("@\"Family Finance\" ", chips[0].query, "a name with a space is quoted")
        assertEquals(listOf("Work › Dev"), chips("gh @de").map { it.label.english() })
        assertEquals("gh @Dev ", chips("gh @de").single().query)
    }

    @Test
    fun tagsSpelledAlikeAreOne() {
        val chips = chips("#c")

        assertEquals(listOf("code"), chips.map { it.label.english() })
        assertEquals(2, chips.single().count)
    }

    @Test
    fun aBarePlusOffersAnyFileFirst() {
        val chips = chips("+")

        assertEquals(listOf("Any file", "key.txt"), chips.map { it.label.english() })
        assertEquals("+ ", chips[0].query)
        assertEquals("+key.txt ", chips[1].query)
    }

    @Test
    fun inTheBinTheBinsGroupsAreOffered() {
        assertEquals(listOf("Home"), chips("- @").map { it.label.english() })
    }

    // spec: search.suggestions/AC4
    @Test
    fun aNameWithAQuoteIsNotOfferedItCouldNotBeWrittenBack() {
        val quoted = VaultListing(
            Vault(
                entries = listOf(
                    Entry(id = "a", title = "Alpha", groupPath = listOf("\"Old\" accounts"), tags = listOf("say \"hi\""), attachments = listOf(Attachment("a\"b.txt", 1))),
                    Entry(id = "b", title = "Beta", groupPath = listOf("Old", "Mail"), tags = listOf("old"), attachments = listOf(Attachment("old.txt", 1))),
                ),
            ),
            now,
            english,
            unnamedCode = UNNAMED_CODE,
        )

        assertEquals(listOf("Old", "Old › Mail"), searchChips("@", quoted, false).chips.map { it.label.english() })
        assertEquals(listOf("old"), searchChips("#", quoted, false).chips.map { it.label.english() })
        assertEquals(listOf("Any file", "old.txt"), searchChips("+", quoted, false).chips.map { it.label.english() })
    }

    @Test
    fun noMatchOffersNothing() {
        assertEquals(emptyList(), chips("@zzz"))
    }

    @Test
    fun whatIsOfferedFollowsTheOtherFiltersAlsoWithOneDatabase() {
        assertEquals(listOf("Work", "Work › Dev"), chips("#code @").map { it.label.english() }, "Family Finance holds no entry tagged code")
        assertEquals(listOf(2, 1), chips("#code @").map { it.count })
        assertEquals(listOf("Work"), chips("#office @").map { it.label.english() })
        assertEquals(listOf("code", "office"), chips("@Work #").map { it.label.english() })
        assertEquals(emptyList(), chips("@Family #"), "Sparkasse has no tags: nothing would be left")
        assertEquals(listOf("Finanzen", "Finanzen › Banken"), line(alone, "#bank @").chips.map { it.label.english() }, "Media holds no bank entry")
    }

    // Several databases

    @Test
    fun withTwoOpenTheEmptySearchOffersTheDatabaseFilterBetweenTagAndFile() {
        val chips = line(both, "").chips

        assertEquals(listOf("@", "#", "!", "+", "-"), chips.map { it.query })
        assertEquals("database", chips[2].label.english())
        assertEquals(listOf("@", "#", "!", "+"), line(both, "", codesOnly = true).chips.map { it.query })
    }

    @Test
    fun withOneOpenThereIsNoDatabaseChipNorDatabaseSuggestion() {
        assertEquals(listOf("@", "#", "+", "-"), line(alone, "").chips.map { it.query })
        assertEquals(emptyList(), line(alone, "!").chips)
        assertEquals(emptyList(), line(alone, "!Pers").chips)
        assertTrue(line(alone, "@Fin").chips.all { it.shares.isEmpty() && it.database == null })
    }

    @Test
    fun aBangOffersTheOpenDatabasesWithTheirCountsAndPutsTheWholeLabelIn() {
        val chips = line(both, "!").chips

        assertEquals(listOf("Personal", "Work"), chips.map { it.label.english() })
        assertEquals(listOf(4, 3), chips.map { it.count })
        assertEquals(listOf(personalMark, workMark), chips.map { it.database })
        assertEquals(listOf("!Personal ", "!Work "), chips.map { it.query })
        assertEquals(listOf("Work"), line(both, "!w").chips.map { it.label.english() })
        assertEquals(listOf(1, 1), line(both, "@Finanzen !").chips.map { it.count }, "counted over the other filters")
        assertEquals(listOf("Work"), line(both, "#work !").chips.map { it.label.english() }, "only the databases holding such entries")
    }

    @Test
    fun aLabelWithSpacesGoesInQuoted() {
        val family = DatabaseMark(DatabaseId("F"), DatabaseLabel("Family Shared"), 2)
        val listing = VaultListing(
            listOf(ListedDatabase(personalMark.id, personal, personalMark), ListedDatabase(family.id, work, family)),
            now,
            english,
            unnamedCode = UNNAMED_CODE,
        )

        assertEquals("!\"Family Shared\" ", line(listing, "!fam").chips.single().query)
    }

    @Test
    fun aChosenDatabaseOffersItsOwnGroups() {
        assertEquals(listOf("Engineering", "Finanzen", "Office"), line(both, "!Work @").chips.map { it.label.english() })
        assertTrue(line(both, "!Work @").chips.all { it.shares.isEmpty() }, "one database's entries: a plain count")
    }

    @Test
    fun aNameInTwoDatabasesIsOneChipWithACountForEach() {
        val groups = line(both, "@").chips.associateBy { it.label.english() }

        assertEquals(listOf(DatabaseShare(personalMark, 1), DatabaseShare(workMark, 1)), groups.getValue("Finanzen").shares)
        assertEquals(emptyList(), groups.getValue("Finanzen › Banken").shares, "Banken is Personal's alone: its count only")
        assertEquals(1, groups.getValue("Finanzen › Banken").count)
        assertEquals(listOf("Finanzen"), line(both, "@Fin").chips.map { it.label.english() })
        val tags = line(both, "#").chips.associateBy { it.label.english() }
        assertEquals(listOf(DatabaseShare(personalMark, 1), DatabaseShare(workMark, 1)), tags.getValue("bank").shares)
        assertEquals(listOf(DatabaseShare(personalMark, 1), DatabaseShare(workMark, 1)), tags.getValue("dev").shares)
        assertEquals(emptyList(), tags.getValue("work").shares, "Work's alone")
        assertEquals(2, tags.getValue("work").count)
    }

    // Narrowing (keething-spec search.narrowing)

    @Test
    fun theLastWordOffersToNarrowToTheNamesItMatchesWell() {
        val line = line(both, "fin")

        assertTrue(line.narrowing)
        assertEquals(listOf("Finanzen"), line.chips.map { it.label.english() })
        assertEquals('@', line.chips.single().sigil)
        assertEquals("@Finanzen ", line.chips.single().query)
        assertEquals(listOf(DatabaseShare(personalMark, 1), DatabaseShare(workMark, 1)), line.chips.single().shares)
        assertEquals("@\"Family Shared\" ", line(both, "fs").chips.single().query)
    }

    @Test
    fun aDatabaseIsOfferedFirstWithItsDotThenGroupsThenTags() {
        val line = line(both, "work")

        assertTrue(line.narrowing)
        assertEquals(listOf("Work", "work"), line.chips.map { it.label.english() })
        assertEquals(workMark, line.chips[0].database)
        assertNull(line.chips[0].sigil)
        assertEquals("!Work ", line.chips[0].query)
        assertEquals('#', line.chips[1].sigil)
        assertEquals("#work ", line.chips[1].query)
        assertEquals(listOf("work"), line(both, "!Work wo").chips.map { it.label.english() }, "no database while one is chosen")
    }

    @Test
    fun withOneDatabaseNoDatabaseIsOfferedToNarrowTo() {
        assertFalse(line(alone, "pers").narrowing)
        assertEquals(listOf("pers @", "pers #", "pers +", "- pers"), line(alone, "pers").chips.map { it.query })
        assertEquals(listOf("Finanzen"), line(alone, "fin").chips.map { it.label.english() }, "narrowing is for one database too")
    }

    @Test
    fun narrowingIsNotOfferedForOneLetterWhileSuggestionsShowInTheBinOrInTheCodesList() {
        assertFalse(line(both, "f").narrowing)
        assertFalse(line(both, "@fin").narrowing, "the suggestions have it")
        assertEquals(listOf("Finanzen"), line(both, "@fin").chips.map { it.label.english() })
        assertFalse(line(both, "-fin").narrowing)
        assertTrue(line(both, "-fin").chips.last().selected, "the bin's own chips")
        assertFalse(line(both, "fin", codesOnly = true).narrowing)
        assertFalse(line(both, "qqq").narrowing, "a word matching no name")
        assertFalse(line(both, "netflix fin").narrowing, "it would leave the list empty")
    }
}
