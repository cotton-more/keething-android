package dev.keething.android.list

import dev.keething.android.databases.DatabaseLabel
import dev.keething.android.databases.DatabaseMark
import dev.keething.card.ExpiryHint
import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.CustomIcon
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import java.text.Collator
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultListingTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")

    private val github = Entry(
        id = "gh", title = "GitHub", username = "octocat", url = "https://github.com/login",
        totp = TotpConfig(Secret.of("12345678901234567890")), attachments = listOf(Attachment("key.txt", 3)),
    )
    private val bahn = Entry(id = "db", title = "Deutsche Bahn", username = "alex@example.org", url = "bahn.de")
    private val amazon = Entry(id = "az", title = "amazon", url = "https://www.amazon.de")
    private val passport = Entry(
        id = "pp", title = "Passport",
        customFields = listOf(CustomField.plain("expires", "2026-11-10")),
    )
    private val oldRouter = Entry(id = "r1", title = "Old Router", username = "admin")
    private val shop = Entry(id = "r2", title = "Shop", username = "me")

    private val listing = VaultListing(
        Vault(entries = listOf(github, bahn, amazon, passport), recycleBin = listOf(oldRouter, shop)),
        now,
        Collator.getInstance(Locale.ENGLISH),
    )

    @Test
    fun nothingTypedShowsTheRecentEntriesThenAllByTitle() {
        val sections = listing.sections("", recentIds = listOf("db", "gone", "gh"))

        assertEquals(listOf(ListSection.Kind.RECENT, ListSection.Kind.ALL), sections.map { it.kind })
        assertEquals(listOf("db", "gh"), sections[0].rows.map { it.id }, "ids no longer in the vault are skipped")
        assertEquals(listOf("az", "db", "gh", "pp"), sections[1].rows.map { it.id })
    }

    @Test
    fun withoutRecentEntriesOnlyAllIsShown() {
        val sections = listing.sections("  ", recentIds = emptyList())

        assertEquals(listOf(ListSection.Kind.ALL), sections.map { it.kind })
    }

    @Test
    fun typingSearchesEverythingBestFirst() {
        val sections = listing.sections("bahn", recentIds = listOf("gh"))

        assertEquals(1, sections.size)
        assertEquals(ListSection.Kind.RESULTS, sections[0].kind)
        assertEquals(listOf("db"), sections[0].rows.map { it.id })
    }

    @Test
    fun noMatchGivesNoSections() {
        assertTrue(listing.sections("zzzzqqq", emptyList()).isEmpty())
    }

    @Test
    fun aMinusListsTheRecycleBinLatestDeletedFirstOrSearchesIt() {
        val bin = listing.sections("-", emptyList())
        assertEquals(ListSection.Kind.RECYCLE_BIN, bin.single().kind)
        assertEquals(listOf("r2", "r1"), bin.single().rows.map { it.id })

        assertEquals(listOf("r1"), listing.sections("- router", emptyList()).single().rows.map { it.id })
    }

    @Test
    fun theCodesListHasOnlyEntriesWithACode() {
        val bank = Entry(id = "bk", title = "Bank", username = "alex", totp = TotpConfig(Secret.of("12345678901234567890")))
        val listing = VaultListing(Vault(entries = listOf(github, bahn, bank, amazon)), now, Collator.getInstance(Locale.ENGLISH))

        val all = listing.sections("", recentIds = listOf("db"), codesOnly = true)
        assertEquals(ListSection.Kind.CODES, all.single().kind, "no recent section")
        assertEquals(listOf("bk", "gh"), all.single().rows.map { it.id })

        val found = listing.sections("alex", emptyList(), codesOnly = true)
        assertEquals(ListSection.Kind.RESULTS, found.single().kind)
        assertEquals(listOf("bk"), found.single().rows.map { it.id }, "Deutsche Bahn matches but has no code")
        assertTrue(listing.sections("bahn", emptyList(), codesOnly = true).isEmpty())
    }

    @Test
    fun aRowShowsWhatTheListNeeds() {
        val rows = listing.sections("", emptyList()).single().rows.associateBy { it.id }

        val gh = rows.getValue("gh")
        assertEquals("GitHub", gh.title)
        assertEquals("octocat", gh.subtitle, "the username alone, the site only without one")
        assertTrue(gh.hasAttachments)
        assertNotNull(gh.totp)
        assertEquals("www.amazon.de", rows.getValue("az").subtitle, "no username: the site alone")
        assertEquals("", rows.getValue("pp").subtitle)
        assertNull(rows.getValue("db").totp)

        val app = Entry(id = "app", title = "Parking", url = "androidapp://com.example.parking")
        val withApp = VaultListing(Vault(entries = listOf(app)), now, Collator.getInstance(Locale.ENGLISH))
        assertEquals("", withApp.sections("", emptyList()).single().rows.single().subtitle, "an app's address is no site")
    }

    @Test
    fun aRowSaysWhenSomethingEndsSoonOrEnded() {
        val expired = Entry(id = "x", title = "Licence", expires = true, expiryTime = Instant.parse("2026-10-17T08:00:00Z"))
        val listing = VaultListing(Vault(entries = listOf(passport, expired, bahn)), now, Collator.getInstance(Locale.ENGLISH))
        val rows = listing.sections("", emptyList()).single().rows.associateBy { it.id }

        assertEquals(RowDeadline("expires · in 23 days", ExpiryHint.Status.SOON), rows.getValue("pp").deadline)
        assertEquals(RowDeadline("Expired yesterday", ExpiryHint.Status.EXPIRED), rows.getValue("x").deadline)
        assertNull(rows.getValue("db").deadline)
    }

    @Test
    fun entriesLeavingForTheRecycleBinAreHiddenEverywhereButThere() {
        val sections = listing.sections("", recentIds = listOf("db")).without(setOf("db"))

        assertEquals(listOf(ListSection.Kind.ALL), sections.map { it.kind }, "Recent, left empty, goes")
        assertEquals(listOf("az", "gh", "pp"), sections.single().rows.map { it.id })
        val bin = listing.sections("-", emptyList())
        assertEquals(bin, bin.without(setOf("r1")), "the recycle bin keeps them")
    }

    @Test
    fun aRowNamesItsGroupByPathAndAnEntryInTheBinByItsPathInsideTheBin() {
        val inGroup = Entry(id = "g", title = "Sparkasse", groupPath = listOf("Finanzen", "Banken"))
        val binned = Entry(id = "b", title = "Old", groupPath = listOf("Recycle Bin", "Work"))
        val binnedAlone = Entry(id = "c", title = "Older", groupPath = listOf("Recycle Bin"))
        val listing = VaultListing(Vault(entries = listOf(inGroup, bahn), recycleBin = listOf(binned, binnedAlone)), now, Collator.getInstance(Locale.ENGLISH))

        val rows = listing.sections("", emptyList()).single().rows.associateBy { it.id }
        assertEquals(listOf("Finanzen", "Banken"), rows.getValue("g").group)
        assertEquals(emptyList(), rows.getValue("db").group, "the root group is not named")
        val bin = listing.sections("-", emptyList()).single().rows.associateBy { it.id }
        assertEquals(listOf("Work"), bin.getValue("b").group)
        assertEquals(emptyList(), bin.getValue("c").group)
    }

    // Several databases open (plan AD9; the Mac's D6 `DatabasesWindowTests`)

    private val personalId = DatabaseId("P")
    private val workId = DatabaseId("W")
    private val personalMark = DatabaseMark(personalId, DatabaseLabel("Personal"), 0)
    private val workMark = DatabaseMark(workId, DatabaseLabel("Work"), 1)

    private fun p(id: String) = EntryRef(personalId, id).text
    private fun w(id: String) = EntryRef(workId, id).text

    // The same UUID in both: Work was made from a copy of Personal.
    private val personal = Vault(
        entries = listOf(
            Entry(id = "same", title = "GitHub", username = "alex", groupPath = listOf("Media")),
            Entry(id = "bank", title = "Sparkasse", username = "alex", groupPath = listOf("Finanzen", "Banken")),
            Entry(id = "tv", title = "Netflix", groupPath = listOf("Media")),
        ),
        recycleBin = listOf(
            Entry(id = "oldP1", title = "Old Personal", groupPath = listOf("Recycle Bin", "Media")),
            Entry(id = "oldP2", title = "Older Personal", groupPath = listOf("Recycle Bin")),
        ),
    )
    private val work = Vault(
        entries = listOf(
            Entry(id = "same", title = "GitHub", username = "alex-work", groupPath = listOf("Engineering")),
            Entry(id = "wbank", title = "Firmenkonto", groupPath = listOf("Finanzen")),
        ),
        recycleBin = listOf(Entry(id = "oldW", title = "Old Work", groupPath = listOf("Recycle Bin", "Engineering"))),
    )

    private fun both(p: Vault = personal, w: Vault = work) = VaultListing(
        listOf(ListedDatabase(personalId, p, personalMark), ListedDatabase(workId, w, workMark)),
        now,
        Collator.getInstance(Locale.ENGLISH),
    )

    @Test
    fun theSameUuidInTwoDatabasesIsTwoRowsEachWithItsDatabase() {
        val listing = both()
        val rows = listing.sections("github", emptyList()).single().rows

        assertEquals(listOf(p("same"), w("same")), rows.map { it.id })
        assertEquals(listOf("Personal", "Work"), rows.map { it.database?.label?.name })
        assertEquals(listOf("alex", "alex-work"), rows.map { it.subtitle })
        assertEquals("alex-work", listing.entry(w("same"))?.username, "each row is its own database's entry")
        assertEquals(5, listing.vault.entries.size)
        assertEquals(listOf(p("bank"), p("same"), p("tv"), w("same"), w("wbank")), listing.ids.sorted())
    }

    @Test
    fun withOneDatabaseOpenTheRowsAreRefsWithoutLabels() {
        val listing = VaultListing(listOf(ListedDatabase(personalId, personal)), now, Collator.getInstance(Locale.ENGLISH))
        val rows = listing.sections("", emptyList()).single().rows

        assertEquals(listOf(p("same"), p("tv"), p("bank")), rows.map { it.id })
        assertTrue(rows.all { it.database == null })
    }

    @Test
    fun recentIsAcrossTheOpenDatabasesEightInAllADatabaseNotListedTakesNoPlace() {
        val listing = both()
        val recent = listOf(w("same"), EntryRef(DatabaseId("Crypto"), "x").text, p("bank"), w("gone"))

        assertEquals(listOf(w("same"), p("bank")), listing.sections("", recent).first().rows.map { it.id })

        val many = Vault(entries = (0 until 10).map { Entry(id = "p$it", title = "P $it") })
        val ten = (9 downTo 0).map { p("p$it") }
        val rows = both(p = many).sections("", listOf(w("same")) + ten).first().rows
        assertEquals(listOf(w("same")) + ten.take(7), rows.map { it.id }, "8 shown in all")
    }

    @Test
    fun aMinusListsEveryBinInTheWindowsOrderEachLatestDeletedFirst() {
        // A file keeps no time of the move, so not one merged order (keething-spec search.recycle-bin AC5).
        val listing = both()
        val bin = listing.sections("-", emptyList()).single()

        assertEquals(ListSection.Kind.RECYCLE_BIN, bin.kind)
        assertEquals(listOf(p("oldP2"), p("oldP1"), w("oldW")), bin.rows.map { it.id })
        assertEquals(listOf(emptyList(), listOf("Media"), listOf("Engineering")), bin.rows.map { it.group }, "the bin's own group left out")
        assertEquals(listOf("Personal", "Personal", "Work"), bin.rows.map { it.database?.label?.name })
        assertEquals(listOf(w("oldW")), listing.sections("- old work", emptyList()).single().rows.map { it.id })
        assertNull(listing.sections("", emptyList()).flatMap { it.rows }.firstOrNull { it.id == w("oldW") }, "never in the ordinary list")
    }

    @Test
    fun theRowsOfADatabaseThatLockedAloneGoAtOnce() {
        val sections = both().sections("", listOf(w("same"), p("bank")))

        val left = sections.onlyOf(setOf(personalId))

        assertEquals(listOf(listOf(p("bank")), listOf(p("same"), p("tv"), p("bank"))), left.map { section -> section.rows.map { it.id } })
        assertTrue(sections.onlyOf(emptySet()).isEmpty())
    }

    @Test
    fun iconsAndDeadlinesAreEachDatabasesOwnForTheSameUuid() {
        val soon = Instant.parse("2026-10-25T00:00:00Z")
        val a = Vault(
            entries = listOf(Entry(id = "same", title = "Licence", customIconId = "icon", expires = true, expiryTime = soon)),
            customIcons = mapOf("icon" to CustomIcon(byteArrayOf(1))),
        )
        val b = Vault(
            entries = listOf(Entry(id = "same", title = "Licence", customIconId = "icon")),
            customIcons = mapOf("icon" to CustomIcon(byteArrayOf(2))),
        )
        val listing = both(p = a, w = b)
        val rows = listing.sections("", emptyList()).single().rows.associateBy { it.id }

        val personalIcon = assertNotNull(rows.getValue(p("same")).customIconId)
        val workIcon = assertNotNull(rows.getValue(w("same")).customIconId)
        assertTrue(personalIcon != workIcon)
        assertEquals(1, listing.vault.customIcons.getValue(personalIcon).image.single().toInt())
        assertEquals(2, listing.vault.customIcons.getValue(workIcon).image.single().toInt())
        assertNotNull(rows.getValue(p("same")).deadline)
        assertNull(rows.getValue(w("same")).deadline, "Work's copy does not expire")
    }

    @Test
    fun bareSignsAloneListNothingTheBinsStillList() {
        val listing = both()

        for (query in listOf("@", "!", "# ", "@ # !")) {
            assertEquals(emptyList(), listing.sections(query, emptyList()), "“$query”: never the whole vault")
            assertEquals(emptyList(), listing.sections(query, emptyList(), codesOnly = true), "“$query” in the Codes list")
        }
        assertEquals(3, listing.sections("-", emptyList()).single().rows.size)
        assertEquals(3, listing.sections("- @", emptyList()).single().rows.size, "the bins' search: a bare sign keeps them all")
    }

    @Test
    fun aBangKeepsOneDatabasesEntriesAWholeLabelThatDatabaseOnly() {
        val listing = both()

        assertEquals(listOf(w("wbank"), w("same")), listing.sections("!Work", emptyList()).single().rows.map { it.id })
        assertEquals(listOf(w("same")), listing.sections("!work git", emptyList()).single().rows.map { it.id })
        assertEquals(emptyList(), listing.sections("!Work !Personal", emptyList()))
        assertEquals(listOf(p("oldP1"), p("oldP2")), listing.sections("- !Pers", emptyList()).single().rows.map { it.id }.sorted(), "in the bins too")
        val alone = VaultListing(listOf(ListedDatabase(personalId, personal, label = "Personal")), now, Collator.getInstance(Locale.ENGLISH))
        assertEquals(3, alone.sections("!Personal", emptyList()).single().rows.size, "one database open: its label typed by hand still filters")
        assertEquals(emptyList(), alone.sections("!Work", emptyList()))
    }

    @Test
    fun aWholeLabelKeepsThatDatabaseOnlyAlsoWhenItsBinIsEmpty() {
        val workshop = DatabaseMark(DatabaseId("S"), DatabaseLabel("Workshop"), 2)
        val listing = VaultListing(
            listOf(ListedDatabase(workId, Vault(entries = work.entries), workMark), ListedDatabase(workshop.id, work, workshop)),
            now,
            Collator.getInstance(Locale.ENGLISH),
        )

        assertEquals(emptyList(), listing.sections("- !Work", emptyList()), "Work's bin is empty: Workshop's is not Work's")
        assertEquals(1, listing.sections("- !Wor", emptyList()).single().rows.size)
    }

    @Test
    fun theFacetsAreKeptUntilTheOtherFiltersChange() {
        val listing = both()
        val first = listing.facets("!Work ", inBin = false)

        assertTrue(first === listing.facets("!Work ", inBin = false))
        assertEquals(listOf("Engineering", "Finanzen"), first.groups.map { it.name })
        assertEquals(listOf("Media"), listing.facets("!Personal ", inBin = true).groups.map { it.name }, "the bin's, its own group left out")
        assertEquals(listOf("Engineering", "Finanzen", "Banken", "Media").sorted(), listing.facets("", inBin = false).groups.map { it.name }.sorted())
    }
}
