package dev.keething.android.list

import dev.keething.android.R
import dev.keething.android.databases.DatabaseLabel
import dev.keething.android.databases.DatabaseMark
import dev.keething.android.ui.english
import dev.keething.android.ui.uiText
import dev.keething.card.ExpiryHint
import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.CustomIcon
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
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

/** What the list calls a further code without a name of its own, as the app passes it in. */
internal val UNNAMED_CODE = uiText(R.string.list_one_time_code).english()

class VaultListingTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")

    /** A recycle bin at the top level, as KeePass makes it. */
    private val topBin = VaultGroup("BIN", "Recycle Bin", listOf("Recycle Bin"))

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
        unnamedCode = UNNAMED_CODE,
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

    private val secret = TotpConfig(Secret.of("12345678901234567890"))
    private fun link(issuer: String) = "otpauth://totp/$issuer:alex?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=$issuer"

    // spec: app.codes-view/AC1
    @Test
    fun theCodesViewHasATilePerCodeByTitleOwnCodeFirst() {
        val microsoft = Entry(
            id = "ms", title = "Microsoft", username = "alex@outlook.com", totp = secret,
            customFields = listOf(
                CustomField.plain("Recovery", "words"),
                CustomField.plain("One-time code 2", link("Microsoft")),
                CustomField.protected("Work account", link("Microsoft")),
            ),
        )
        val hetzner = Entry(id = "hz", title = "Hetzner", username = "alex", customFields = listOf(CustomField.plain("Robot", link("Hetzner"))))
        val broken = Entry(id = "br", title = "Broken", customFields = listOf(CustomField.plain("Code", "otpauth://totp/x?secret=")))
        val listing = VaultListing(Vault(entries = listOf(microsoft, github, bahn, hetzner, broken)), now, Collator.getInstance(Locale.ENGLISH), unnamedCode = UNNAMED_CODE)

        val tiles = listing.codes("")
        assertEquals(listOf("GitHub", "Hetzner", "Microsoft", "Microsoft", "Microsoft"), tiles.map { it.title.english() })
        assertEquals(listOf(null, "Robot", null, "One-time code 2", "Work account"), tiles.map { it.fieldName }, "own code first, then field order")
        assertEquals(listOf("gh", "hz", "ms", "ms", "ms"), tiles.map { it.entryId })
        assertEquals(tiles.size, tiles.map { it.key }.toSet().size, "each tile its own key")
        assertTrue(listing.codes("broken").isEmpty(), "a code that cannot be read is not listed")
    }

    // spec: app.codes-view/AC2
    @Test
    fun aTileSaysTheUsernameForTheOwnCodeTheFieldsNameForAFurtherOne() {
        val microsoft = Entry(
            id = "ms", title = "Microsoft", username = "alex@outlook.com", totp = secret,
            customFields = listOf(CustomField.plain("One-time code 2", link("Microsoft")), CustomField.plain("Backup_Codes", link("Microsoft"))),
        )
        val listing = VaultListing(Vault(entries = listOf(microsoft)), now, Collator.getInstance(Locale.ENGLISH), unnamedCode = UNNAMED_CODE)

        val tiles = listing.codes("")
        assertEquals(listOf("alex@outlook.com", "One-time code 2", "Backup › Codes"), tiles.map { it.subtitle })
        assertEquals(listOf(false, true, true), tiles.map { it.further })
        assertEquals(secret, tiles[0].totp)
        assertEquals(6, tiles[1].totp.digits)
    }

    // spec: app.codes-view/AC3
    @Test
    fun theCodesSearchSearchesTheTitleTheUsernameAndAFurtherCodesName() {
        val microsoft = Entry(
            id = "ms", title = "Microsoft", username = "alex@outlook.com", totp = secret, notes = "xbox",
            url = "https://login.live.com", customFields = listOf(CustomField.plain("Work account", link("Microsoft"))),
        )
        val bank = Entry(id = "bk", title = "Bank", username = "alex", totp = secret, groupPath = listOf("Finanzen"), tags = listOf("money"))
        val listing = VaultListing(Vault(entries = listOf(microsoft, github, bahn, bank, amazon)), now, Collator.getInstance(Locale.ENGLISH), unnamedCode = UNNAMED_CODE)

        assertEquals(listOf("bk", "ms", "ms"), listing.codes("alex").map { it.entryId }, "the username: both of Microsoft's codes")
        assertEquals(listOf("Work account"), listing.codes("work").map { it.fieldName }, "a further code's name finds that code only")
        assertEquals(listOf("gh"), listing.codes("git").map { it.entryId })
        assertTrue(listing.codes("bahn").isEmpty(), "Deutsche Bahn has no code")
        assertTrue(listing.codes("xbox").isEmpty(), "not the notes")
        assertTrue(listing.codes("live.com").isEmpty(), "not the URL")
        assertEquals(listOf("bk"), listing.codes("@Finanzen").map { it.entryId }, "the filters still filter")
        assertEquals(listOf("bk"), listing.codes("#money").map { it.entryId })
        for (query in listOf("@", "!", "# ")) assertTrue(listing.codes(query).isEmpty(), "“$query”: never all codes")
    }

    @Test
    fun withTwoDatabasesOpenTheCodesAreEachDatabasesAndTheBangFilterKeepsOne() {
        val p = personal.copy(entries = personal.entries.map { if (it.id == "same") it.copy(totp = secret) else it })
        val w = work.copy(entries = work.entries.map { if (it.id == "same") it.copy(totp = secret) else it })
        val listing = both(p, w)

        val tiles = listing.codes("")
        assertEquals(listOf(p("same"), w("same")), tiles.map { it.entryId })
        assertEquals(listOf("Personal", "Work"), tiles.map { it.database?.label?.name }, "the group line starts with the database")
        assertEquals(listOf(listOf("Media"), listOf("Engineering")), tiles.map { it.group })
        assertEquals(listOf(w("same")), listing.codes("!Work").map { it.entryId })
    }

    // spec: app.codes-view/AC2
    @Test
    fun withOneDatabaseOpenATileHasItsGroupsPathAndNoDatabase() {
        val nested = Entry(id = "sp", title = "Sparkasse", totp = secret, groupPath = listOf("Finanzen", "Banken"))
        val atRoot = Entry(id = "rt", title = "Root", totp = secret)
        val tiles = VaultListing(Vault(entries = listOf(nested, atRoot)), now, Collator.getInstance(Locale.ENGLISH), unnamedCode = UNNAMED_CODE).codes("")

        assertEquals(listOf("Root", "Sparkasse"), tiles.map { it.title.english() })
        assertEquals(listOf(emptyList(), listOf("Finanzen", "Banken")), tiles.map { it.group }, "the root group: no line")
        assertTrue(tiles.all { it.database == null })
    }

    @Test
    fun aRowShowsWhatTheListNeeds() {
        val rows = listing.sections("", emptyList()).single().rows.associateBy { it.id }

        val gh = rows.getValue("gh")
        assertEquals("GitHub", gh.title.english())
        assertEquals("octocat", gh.subtitle.english(), "the username alone, the site only without one")
        assertTrue(gh.hasAttachments)
        assertEquals(1, gh.codes.size)
        assertEquals("www.amazon.de", rows.getValue("az").subtitle.english(), "no username: the site alone")
        assertEquals("", rows.getValue("pp").subtitle.english())
        assertTrue(rows.getValue("db").codes.isEmpty())

        val app = Entry(id = "app", title = "Parking", url = "androidapp://com.example.parking")
        val withApp = VaultListing(Vault(entries = listOf(app)), now, Collator.getInstance(Locale.ENGLISH), unnamedCode = UNNAMED_CODE)
        assertEquals("", withApp.sections("", emptyList()).single().rows.single().subtitle.english(), "an app's address is no site")
    }

    // spec: search.row-codes/AC1
    @Test
    fun aRowCarriesEveryCodeOfItsEntryItsOwnFirstThenItsFurtherOnesInFieldOrder() {
        val microsoft = Entry(
            id = "ms", title = "Microsoft", totp = secret,
            customFields = listOf(
                CustomField.plain("Work account", link("Microsoft")),
                CustomField.plain("Notes", "no code"),
                CustomField.protected("One-time code 2", link("Microsoft")),
            ),
        )
        val furtherOnly = Entry(id = "hz", title = "Hetzner", customFields = listOf(CustomField.plain("Robot", link("Hetzner"))))
        val broken = Entry(id = "br", title = "Broken", customFields = listOf(CustomField.plain("Code", "otpauth://totp/x?secret=")))
        val binned = Entry(id = "old", title = "Old", totp = secret, customFields = listOf(CustomField.plain("Backup", link("Old"))))
        val listing = VaultListing(Vault(entries = listOf(microsoft, furtherOnly, broken, bahn), recycleBin = listOf(binned)), now, Collator.getInstance(Locale.ENGLISH), unnamedCode = UNNAMED_CODE)
        val rows = listing.sections("", emptyList()).single().rows.associateBy { it.id }

        assertEquals(listOf(null, "Work account", "One-time code 2"), rows.getValue("ms").codes.map { it.fieldName }, "own first, a protected link too")
        assertEquals(listOf("ms\nWork account", "ms\nOne-time code 2"), rows.getValue("ms").codes.drop(1).map { it.key }, "the Codes view's tiles")
        assertEquals(listOf("Robot"), rows.getValue("hz").codes.map { it.fieldName }, "further codes only: still a code")
        assertTrue(rows.getValue("br").codes.isEmpty(), "a code that cannot be read is not one")
        assertTrue(rows.getValue("db").codes.isEmpty())
        assertEquals(listing.codes("").filter { it.entryId == "ms" }, rows.getValue("ms").codes, "as the Codes view lists them")
        assertEquals(listOf(null, "Backup"), listing.sections("-", emptyList()).single().rows.single().codes.map { it.fieldName }, "an entry in the bin too")
    }

    @Test
    fun aRowSaysWhenSomethingEndsSoonOrEnded() {
        val expired = Entry(id = "x", title = "Licence", expires = true, expiryTime = Instant.parse("2026-10-17T08:00:00Z"))
        val listing = VaultListing(Vault(entries = listOf(passport, expired, bahn)), now, Collator.getInstance(Locale.ENGLISH), unnamedCode = UNNAMED_CODE)
        val rows = listing.sections("", emptyList()).single().rows.associateBy { it.id }

        assertEquals("expires · Expires in 23 days" to ExpiryHint.Status.SOON, rows.getValue("pp").deadline?.let { it.text.english() to it.status })
        assertEquals("Expired yesterday" to ExpiryHint.Status.EXPIRED, rows.getValue("x").deadline?.let { it.text.english() to it.status })
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
        val listing = VaultListing(
            Vault(entries = listOf(inGroup, bahn), recycleBin = listOf(binned, binnedAlone), recycleBinGroup = topBin),
            now,
            Collator.getInstance(Locale.ENGLISH),
            unnamedCode = UNNAMED_CODE,
        )

        val rows = listing.sections("", emptyList()).single().rows.associateBy { it.id }
        assertEquals(listOf("Finanzen", "Banken"), rows.getValue("g").group)
        assertEquals(emptyList(), rows.getValue("db").group, "the root group is not named")
        val bin = listing.sections("-", emptyList()).single().rows.associateBy { it.id }
        assertEquals(listOf("Work"), bin.getValue("b").group)
        assertEquals(emptyList(), bin.getValue("c").group)
    }

    // spec: search.recycle-bin/AC2
    @Test
    fun theBinsSearchReadsThePathsInsideTheBinTakenOffAtTheBinsOwnDepth() {
        // KeePass lets the bin be moved under another group: what its rows leave out is down to the bin, not one level.
        val nested = Vault(
            entries = listOf(Entry(id = "kept", title = "Archive index", groupPath = listOf("Archive"))),
            // In the file's order: the bin's own entries, then its groups' (a group "Work" deleted into it).
            recycleBin = listOf(
                Entry(id = "router", title = "Old router", groupPath = listOf("Archive", "Recycle Bin")),
                Entry(id = "wiki", title = "Old wiki", groupPath = listOf("Archive", "Recycle Bin", "Work")),
            ),
            recycleBinGroup = VaultGroup("BIN", "Recycle Bin", listOf("Archive", "Recycle Bin")),
        )
        val listing = both(w = nested)

        val bin = listing.sections("-", emptyList()).single().rows
        assertEquals(listOf(p("oldP2"), p("oldP1"), w("wiki"), w("router")), bin.map { it.id }, "each file's order reversed: a deleted group's entries first")
        val rows = bin.associateBy { it.id }
        assertEquals(listOf("Media"), rows.getValue(p("oldP1")).group, "a bin at the top level: its own group left out")
        assertEquals(listOf("Work"), rows.getValue(w("wiki")).group)
        assertEquals(emptyList(), rows.getValue(w("router")).group)
        for (word in listOf("recycle", "bin", "archive", "@Rec", "@Arch", "rb")) {
            assertEquals(emptyList(), listing.sections("- $word", emptyList()), "$word names nothing inside a bin")
        }
        assertEquals(listOf(w("wiki")), listing.sections("- @Work", emptyList()).single().rows.map { it.id })
        assertEquals(listOf(p("oldP1")), listing.sections("- media", emptyList()).single().rows.map { it.id })
        assertEquals(listOf("Media", "Work"), searchChips("- @", listing, codesOnly = false).chips.map { it.label.english() })
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
        recycleBinGroup = topBin,
    )
    private val work = Vault(
        entries = listOf(
            Entry(id = "same", title = "GitHub", username = "alex-work", groupPath = listOf("Engineering")),
            Entry(id = "wbank", title = "Firmenkonto", groupPath = listOf("Finanzen")),
        ),
        recycleBin = listOf(Entry(id = "oldW", title = "Old Work", groupPath = listOf("Recycle Bin", "Engineering"))),
        recycleBinGroup = topBin,
    )

    private fun both(p: Vault = personal, w: Vault = work) = VaultListing(
        listOf(ListedDatabase(personalId, p, personalMark), ListedDatabase(workId, w, workMark)),
        now,
        Collator.getInstance(Locale.ENGLISH),
        unnamedCode = UNNAMED_CODE,
    )

    @Test
    fun theSameUuidInTwoDatabasesIsTwoRowsEachWithItsDatabase() {
        val listing = both()
        val rows = listing.sections("github", emptyList()).single().rows

        assertEquals(listOf(p("same"), w("same")), rows.map { it.id })
        assertEquals(listOf("Personal", "Work"), rows.map { it.database?.label?.name })
        assertEquals(listOf("alex", "alex-work"), rows.map { it.subtitle.english() })
        assertEquals("alex-work", listing.entry(w("same"))?.username, "each row is its own database's entry")
        assertEquals(5, listing.vault.entries.size)
        assertEquals(listOf(p("bank"), p("same"), p("tv"), w("same"), w("wbank")), listing.ids.sorted())
    }

    @Test
    fun withOneDatabaseOpenTheRowsAreRefsWithoutLabels() {
        val listing = VaultListing(listOf(ListedDatabase(personalId, personal)), now, Collator.getInstance(Locale.ENGLISH), unnamedCode = UNNAMED_CODE)
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
        // Each bin in its own file's order, not one merged order (keething-spec search.recycle-bin AC5).
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
            assertEquals(emptyList(), listing.codes(query), "“$query” in the Codes view")
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
        val alone = VaultListing(listOf(ListedDatabase(personalId, personal, label = "Personal")), now, Collator.getInstance(Locale.ENGLISH), unnamedCode = UNNAMED_CODE)
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
            unnamedCode = UNNAMED_CODE,
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
