package dev.keething.android.databases

import dev.keething.domain.storage.StoragePlace
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.Databases
import dev.keething.vault.databases.FileLocation
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lock screen's database, its sheet and Settings' rows (the Mac's `DatabaseListModelTests`, rows 1 and 6 of its
 * canvas; the phone's canvas, rows "Databases").
 */
class DatabaseLinesTest {
    private val root = "/storage/emulated/0"
    private val now = Instant.parse("2026-10-26T12:00:00Z")

    /** The file at [path] on the phone through a folder granted at the top of its volume (for the words, as any other). */
    private fun at(path: String): FileLocation.InFolder {
        val place = checkNotNull(StoragePlace.ofPath(path))
        return FileLocation.InFolder("grant-${place.volume}", StoragePlace(place.volume, ""), place.path)
    }

    private fun pathOf(file: FileLocation?): String = DatabaseText.pathOf(checkNotNull(file), root)

    private val personal = DatabaseRecord(DatabaseId("p"), at("$root/Sync/KeePass/Personal.kdbx"))

    /** The files that are there, by their paths on the phone, and when each changed; the grants gone. */
    private val there = mutableMapOf(pathOf(personal.file) to now.minusSeconds(2 * 3600))
    private val gone = mutableSetOf<String>()
    private val files = DatabaseFiles { location ->
        when {
            location.grant in gone -> FileLook(Reach.NO_ACCESS)
            else -> there[pathOf(location)]?.let { FileLook(Reach.THERE, it) } ?: FileLook(Reach.NOT_FOUND)
        }
    }

    private fun lines(vararg records: DatabaseRecord, current: DatabaseRecord = records.first()) =
        Databases(records.toList(), current.id).lines(files, root, now, ZoneOffset.UTC)

    private fun lockScreen(record: DatabaseRecord, vararg others: DatabaseRecord): LockScreenDatabase =
        record.lockScreen(lines(record, *others, current = record).first { it.id == record.id })

    // The lock screen's line

    @Test
    fun theLineSaysTheNameTheFolderAndWhenItChanged() {
        val lock = lockScreen(personal)

        assertEquals("Personal", lock.line.name)
        assertEquals("P", lock.line.initial)
        assertEquals("Sync/KeePass · changed 2 hours ago", lock.line.detail)
        assertEquals("$root/Sync/KeePass/Personal.kdbx", lock.line.path)
        assertTrue(lock.line.isCurrent)
        assertNull(lock.line.keyFile)
        assertFalse(lock.line.isMissing)
        assertNull(lock.problem)
        assertTrue(lock.showsPasswordField && !lock.showsLocate && lock.needsPassword && lock.offersFingerprint && lock.offersKeyFile)
        assertFalse(lock.opensWithKeyFileAlone)
        assertEquals("Master password", lock.passwordPlaceholder)
    }

    @Test
    fun whenItChangedSaidAsAPersonWould() {
        val now = Instant.parse("2026-10-26T15:00:00Z")
        val cases = listOf(
            "2026-10-26T14:59:40Z" to "changed just now",
            "2026-10-26T16:00:00Z" to "changed just now", // a clock a little ahead
            "2026-10-26T14:59:00Z" to "changed 1 minute ago",
            "2026-10-26T14:15:00Z" to "changed 45 minutes ago",
            "2026-10-26T14:00:00Z" to "changed 1 hour ago",
            "2026-10-26T04:00:00Z" to "changed 11 hours ago",
            "2026-10-26T01:00:00Z" to "changed today",
            "2026-10-25T23:00:00Z" to "changed yesterday",
            "2026-10-23T09:00:00Z" to "changed 3 days ago",
            "2026-10-17T09:00:00Z" to "changed last week",
            "2026-03-02T09:00:00Z" to "changed in March",
            "2025-11-30T09:00:00Z" to "changed in November 2025",
        )
        for ((date, said) in cases) assertEquals(said, DatabaseText.changed(Instant.parse(date), now, ZoneOffset.UTC), date)
        assertEquals("yesterday", DatabaseText.ago(Instant.parse("2026-10-26T01:00:00Z"), now, ZoneId.of("America/New_York")), "the local day")
    }

    @Test
    fun foldersAreSaidFromTheSharedStoragesRoot() {
        assertEquals("Sync/KeePass", DatabaseText.folder("$root/Sync/KeePass/Personal.kdbx", root))
        assertEquals("Internal storage", DatabaseText.folder("$root/Personal.kdbx", root))
        assertEquals("/storage/emulated/01", DatabaseText.folder("/storage/emulated/01/x.kdbx", root), "only the root itself")
        assertEquals("/storage/1234-5678/Keys", DatabaseText.folder("/storage/1234-5678/Keys/x.kdbx", root))
        assertEquals("Download", DatabaseText.folder("$root/Download/x.kdbx", "$root/"))
    }

    // A key file

    @Test
    fun withAKeyFileItsLineIsUnderTheFieldUnlockNeedsNothingTypedAndThePlaceholderSaysSo() {
        val work = DatabaseRecord(DatabaseId("w"), at("$root/Sync/Work/Work.kdbx"), keyFile = at("$root/Documents/Keys/work.keyx"))
        there[pathOf(work.file)] = now.minusSeconds(86_400)
        there[pathOf(work.keyFile)] = now

        val lock = lockScreen(work, personal)

        assertEquals(KeyFileLine("work.keyx", "Documents/Keys", "$root/Documents/Keys/work.keyx", reach = Reach.THERE), lock.line.keyFile)
        assertNull(lock.problem)
        assertTrue(lock.showsPasswordField && lock.offersFingerprint)
        assertFalse(lock.needsPassword || lock.offersKeyFile)
        assertEquals("Master password", lock.passwordPlaceholder)
    }

    @Test
    fun aKeyFileThatIsNotThereIsSaidBeforeAnythingIsTypedAndNoFingerprintIsAsked() {
        val work = DatabaseRecord(DatabaseId("w"), at("$root/Sync/Work/Work.kdbx"), keyFile = at("$root/Documents/Keys/work.keyx"))
        there[pathOf(work.file)] = now

        val lock = lockScreen(work)

        assertEquals("Work needs its key file: work.keyx is not in Documents/Keys.", lock.problem)
        assertTrue(lock.line.keyFile!!.isMissing)
        assertTrue(lock.showsPasswordField)
        assertFalse(lock.offersFingerprint, "no fingerprint for what cannot open")
    }

    @Test
    fun oneThatOpensWithItsKeyFileAloneShowsNoFieldUnlockIsEnough() {
        val keys = DatabaseRecord(DatabaseId("k"), at("$root/Documents/Taxes/Taxes.kdbx"), keyFile = at("$root/k/taxes.keyx"), opensWithoutPassword = true)
        there[pathOf(keys.file)] = now
        there[pathOf(keys.keyFile)] = now

        val lock = lockScreen(keys)

        assertFalse(lock.showsPasswordField || lock.needsPassword || lock.showsLocate)
        assertTrue(lock.opensWithKeyFileAlone)
        assertTrue(lock.offersFingerprint, "it opens after the fingerprint with nothing stored (plan AD5)")
        assertNull(lock.problem)
    }

    // A file not found

    @Test
    fun aFileNotFoundSaysSoWithLocateInPlaceOfTheField() {
        val crypto = DatabaseRecord(DatabaseId("c"), at("$root/Download/Crypto.kdbx"), keyFile = at("$root/k/c.keyx"))

        val lock = lockScreen(crypto, personal)

        assertEquals("Crypto.kdbx is not in Download any more. Locate the file, or choose another database.", lock.missingText)
        assertTrue(lock.showsLocate && lock.line.isMissing)
        assertFalse(lock.showsPasswordField || lock.offersFingerprint || lock.offersKeyFile || lock.opensWithKeyFileAlone)
        assertNull(lock.problem, "the file first: its key file is said once it is back")
        assertEquals("Download · not found", lock.line.detail)

        val card = DatabaseRecord(DatabaseId("s"), at("/storage/1234-5678/Crypto.kdbx"))
        assertEquals("Crypto.kdbx is not in /storage/1234-5678. Put the card in, or locate the file.", lockScreen(card).missingText)
    }

    @Test
    fun aGrantGoneSaysNoAccessWithLocateAndAKeyFilesSaysChooseItAgain() {
        // spec: databases.saf/AC10
        val work = DatabaseRecord(DatabaseId("w"), at("$root/Sync/Work/Work.kdbx"), keyFile = FileLocation.Granted("grant-key", StoragePlace("primary", "k/work.keyx")))
        there[pathOf(work.file)] = now
        there[pathOf(work.keyFile)] = now
        gone += "grant-primary"

        val lock = lockScreen(work)
        assertEquals(Reach.NO_ACCESS, lock.line.reach)
        assertTrue(lock.showsLocate && lock.line.isMissing)
        assertEquals("Locate…", lock.locateLabel)
        assertEquals(
            "Keething can no longer reach Sync/Work: renamed, moved or deleted on a device, or its access taken back. Locate the file to choose its folder again.",
            lock.missingText,
        )
        assertEquals("Sync/Work · no access", lock.line.detail)
        assertEquals("No access · Sync/Work", lock.line.settingsDetail)

        gone.clear()
        gone += "grant-key"
        val key = lockScreen(work)
        assertFalse(key.line.isMissing)
        assertEquals(Reach.NO_ACCESS, key.line.keyFile?.reach)
        assertEquals("no access · k", key.line.keyFile?.detail)
        assertEquals("Keething may not read work.keyx any more: choose it again.", key.problem)
        assertFalse(key.offersFingerprint)
    }

    @Test
    fun aRecordKeptByItsPathIsNeverLookedAtAndAsksForItsFolder() {
        // spec: databases.saf/AC13
        val old = DatabaseRecord(DatabaseId("o"), FileLocation.ByPath("$root/Sync/KeePass/Old.kdbx"), keyFile = FileLocation.ByPath("$root/Sync/Keys/old.keyx"))
        val looked = mutableListOf<FileLocation>()
        val lock = old.lockScreen(Databases(listOf(old), old.id).lines({ looked += it; FileLook(Reach.THERE) }, root, now, ZoneOffset.UTC).single())

        assertEquals(emptyList(), looked, "nothing by its path (AC1)")
        assertEquals(Reach.NEEDS_FOLDER, lock.line.reach)
        assertTrue(lock.showsLocate && !lock.showsPasswordField && !lock.offersFingerprint)
        assertEquals("Choose its folder…", lock.locateLabel)
        assertEquals("Keething now opens files only in folders you choose. Choose Sync/KeePass, the folder of Old.kdbx: it is asked once.", lock.missingText)
        assertEquals("Sync/KeePass · needs its folder", lock.line.detail)
        assertEquals("needs its folder", lock.line.sheetDetail)
        assertEquals(Reach.NO_ACCESS, lock.line.keyFile?.reach, "its folder may hold it; else Choose key file… (AC10)")
    }

    // The sheet and Settings

    @Test
    fun theSheetAndSettingsListEveryDatabaseWithItsFolderOrThatItIsNotFound() {
        val work = DatabaseRecord(DatabaseId("w"), at("$root/Sync/Work/Work.kdbx"), keyFile = at("$root/k/work.keyx"))
        val crypto = DatabaseRecord(DatabaseId("c"), at("$root/Download/Crypto.kdbx"))
        there[pathOf(work.file)] = now

        val lines = lines(personal, work, crypto)

        assertEquals(listOf("Personal", "Work", "Crypto"), lines.map { it.name })
        assertEquals(listOf(true, false, false), lines.map { it.isCurrent })
        assertEquals(listOf("Sync/KeePass", "Sync/Work", "not found"), lines.map { it.sheetDetail })
        assertEquals(listOf("Sync/KeePass", "Sync/Work", "Not found · Download"), lines.map { it.settingsDetail })
        assertEquals(listOf(false, true, false), lines.map { it.keyFile != null })
    }

    // Names and colours (keething-spec databases.name-colour; the Mac's D7b cases)

    private fun record(id: String, path: String, name: String? = null, color: String? = null) =
        DatabaseRecord(DatabaseId(id), at("$root/$path"), name = name, color = color)

    @Test
    fun twoNamedAlikeEachAddTheirFileNameWithFileNamesAlikeTooTheirFolder() {
        val archive = record("a", "Archive/Personal-2024.kdbx")
        val work = record("w", "Work/Work.kdbx", name = "personal") // the file's own name, case aside
        assertEquals(
            listOf("Personal · Personal.kdbx", "Personal-2024", "personal · Work.kdbx"),
            lines(personal, archive, work).map { it.label.full },
        )

        val old = record("o", "Old/Personal.kdbx")
        val both = lines(personal, old)
        assertEquals(listOf("Personal · Sync/KeePass", "Personal · Old"), both.map { it.label.full })
        assertEquals("Personal", both.first().name, "the name alone where it is said in words")
        assertEquals("Sync/KeePass", lockScreen(personal, old).line.label.addition)
    }

    @Test
    fun namesAreComparedAsTheSearchFoldsThemOverTheWholeList() {
        val accented = record("c", "Other/Cafe.kdbx", name = "Personál")
        val lone = record("l", "Lone.kdbx", name = "Lone")
        assertEquals(listOf("Personal · Personal.kdbx", "Personál · Cafe.kdbx", "Lone"), lines(personal, accented, lone).map { it.label.full })
        // The current one does not matter: a label does not change when another becomes current or opens.
        assertEquals(
            lines(personal, accented, lone).map { it.label },
            lines(personal, accented, lone, current = lone).map { it.label },
        )
    }

    @Test
    fun aMarkIsTheLabelAndColourOverTheWholeListWhicheverAreOpen() {
        // Rows and cards say a database by its mark while two or more are open (keething-spec databases.name-colour AC6).
        val old = record("o", "Old/Personal.kdbx", color = "#FF0000")
        val work = record("w", "Work/Work.kdbx")
        val marks = Databases(listOf(personal, old, work), personal.id).marks(root)

        assertEquals(DatabaseMark(personal.id, DatabaseLabel("Personal", "Sync/KeePass"), 0), marks.getValue(personal.id))
        assertEquals(DatabaseMark(old.id, DatabaseLabel("Personal", "Old"), 7), marks.getValue(old.id))
        assertEquals(DatabaseMark(work.id, DatabaseLabel("Work"), 1), marks.getValue(work.id))
    }

    @Test
    fun namesAreJoinedAsASentenceSaysThem() {
        assertEquals("", DatabaseText.joined(emptyList()))
        assertEquals("Personal", DatabaseText.joined(listOf("Personal")))
        assertEquals("Personal and Work", DatabaseText.joined(listOf("Personal", "Work")))
        assertEquals("Personal, Work and Family Shared", DatabaseText.joined(listOf("Personal", "Work", "Family Shared")))
    }

    @Test
    fun coloursGoRoundThePaletteInTheListsOrder() {
        val records = (1..10).map { record("$it", "db$it.kdbx") }.toTypedArray()
        assertEquals(9, DatabasePalette.shades.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 0), lines(*records).map { it.colorIndex })
    }

    @Test
    fun aFilesOwnColourIsThePalettesNearestTheOthersTakeTheColoursLeftInTheListsOrder() {
        val work = record("w", "Work/Work.kdbx", color = "#FF0000")
        val family = record("f", "Family.kdbx", color = "#0E9AA7") // the palette's teal itself
        val crypto = record("c", "Crypto.kdbx")
        assertEquals(listOf(1, 7, 0, 2), lines(personal, work, family, crypto).map { it.colorIndex }, "Personal and Crypto skip teal and red")

        // Two files of the same colour keep it: the user chose it.
        val orangeRed = work.copy(color = "#FF4500")
        val crimson = family.copy(color = "#DC143C")
        assertEquals(listOf(0, 7, 7, 1), lines(personal, orangeRed, crimson, crypto).map { it.colorIndex })
    }

    @Test
    fun aColourAsTheNearestOfTheNineNoColourOfItsOwnIsGrey() {
        val cases = listOf(
            "#FF0000" to 7, "#DC143C" to 7, "#00FF00" to 2, "#006400" to 2, "#0000FF" to 5, "#000080" to 5, "#4169E1" to 5,
            "#00FFFF" to 0, "#008080" to 0, "#40E0D0" to 0, "#FFA500" to 1, "#FF8000" to 1, "#FFFF00" to 1,
            "#800080" to 3, "#9370DB" to 3, "#FF69B4" to 4, "#FF00FF" to 4, "#A2845E" to 6, "#B8946A" to 6,
            "#808080" to 8, "#C0C0C0" to 8, "#000000" to 8, "#FFFFFF" to 8,
        )
        for ((hex, index) in cases) assertEquals(index, DatabasePalette.nearest(hex), hex)
        assertNull(DatabasePalette.nearest("red"))
        DatabasePalette.shades.forEachIndexed { index, shade ->
            assertEquals(index, DatabasePalette.nearest("#%06X".format(shade.dotLight)), "its own light dot")
            assertEquals(index, DatabasePalette.nearest("#%06X".format(shade.dotDark)), "its own dark dot")
        }
    }

    // Through a granted folder (keething-spec databases.saf AC2, AC9): its folder from its place there

    private val sync = StoragePlace(StoragePlace.PRIMARY, "Sync")

    @Test
    fun aDatabaseAndItsKeyFileThroughAGrantedFolderSayTheirFoldersFromTheirPathsInside() {
        val work = DatabaseRecord(
            DatabaseId("w"),
            FileLocation.InFolder("grant-sync", sync, "Work/Work.kdbx"),
            keyFile = FileLocation.InFolder("grant-sync", sync, "Keys/work.keyx"),
        )

        val lock = lockScreen(work)

        assertEquals("Sync/Work · not found", lock.line.detail, "looked at through its grant")
        there[pathOf(work.file)] = now.minusSeconds(60)
        there[pathOf(work.keyFile)] = now
        val found = lockScreen(work)
        assertEquals("Sync/Work · changed 1 minute ago", found.line.detail)
        assertEquals("$root/Sync/Work/Work.kdbx", found.line.path, "its words")
        assertFalse(found.line.isMissing)
        assertEquals(KeyFileLine("work.keyx", "Sync/Keys", "$root/Sync/Keys/work.keyx", reach = Reach.THERE), found.line.keyFile)
        assertEquals("Master password", found.passwordPlaceholder)
    }

    @Test
    fun twoAlikeOneThroughAGrantAreToldApartByTheirFoldersAndACardsIsRemovable() {
        val archive = DatabaseRecord(DatabaseId("a"), FileLocation.InFolder("grant-archive", StoragePlace(StoragePlace.PRIMARY, "Archive"), "Personal.kdbx"))
        assertEquals(listOf("Personal · Sync/KeePass", "Personal · Archive"), lines(personal, archive).map { it.label.full })

        val top = DatabaseRecord(DatabaseId("t"), FileLocation.InFolder("grant-top", StoragePlace(StoragePlace.PRIMARY, ""), "Top.kdbx"))
        there[pathOf(top.file)] = now.minusSeconds(3 * 86_400)
        assertEquals("Internal storage · changed 3 days ago", lockScreen(top).line.detail)

        val card = DatabaseRecord(DatabaseId("s"), FileLocation.InFolder("grant-card", StoragePlace("1234-5678", "KeePass"), "Crypto.kdbx"))
        assertEquals("/storage/1234-5678/KeePass/Crypto.kdbx", lockScreen(card).line.path)
        assertEquals("Crypto.kdbx", lockScreen(card).line.fileName)
        assertEquals(
            "Crypto.kdbx is not in /storage/1234-5678/KeePass. Put the card in, or locate the file.",
            card.lockScreen(lockScreen(card).line.copy(reach = Reach.NOT_FOUND)).missingText,
        )
    }
}
