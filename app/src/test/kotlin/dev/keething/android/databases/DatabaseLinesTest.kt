package dev.keething.android.databases

import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.Databases
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
    private val personal = DatabaseRecord(DatabaseId("p"), "$root/Sync/KeePass/Personal.kdbx")

    /** The files that are there, and when each changed. */
    private val there = mutableMapOf(personal.path to now.minusSeconds(2 * 3600))
    private var access = true
    private val files = object : DatabaseFiles {
        override fun exists(path: String): Boolean? = if (access) path in there else null

        override fun modified(path: String): Instant? = there[path]
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
        assertEquals(personal.path, lock.line.path)
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
        val work = DatabaseRecord(DatabaseId("w"), "$root/Sync/Work/Work.kdbx", keyFilePath = "$root/Documents/Keys/work.keyx")
        there[work.path] = now.minusSeconds(86_400)
        there[work.keyFilePath!!] = now

        val lock = lockScreen(work, personal)

        assertEquals(KeyFileLine("work.keyx", "Documents/Keys", "$root/Documents/Keys/work.keyx", isMissing = false), lock.line.keyFile)
        assertNull(lock.problem)
        assertTrue(lock.showsPasswordField && lock.offersFingerprint)
        assertFalse(lock.needsPassword || lock.offersKeyFile)
        assertEquals("Master password, if it has one", lock.passwordPlaceholder)
    }

    @Test
    fun aKeyFileThatIsNotThereIsSaidBeforeAnythingIsTypedAndNoFingerprintIsAsked() {
        val work = DatabaseRecord(DatabaseId("w"), "$root/Sync/Work/Work.kdbx", keyFilePath = "$root/Documents/Keys/work.keyx")
        there[work.path] = now

        val lock = lockScreen(work)

        assertEquals("Work needs its key file: work.keyx is not in Documents/Keys.", lock.problem)
        assertTrue(lock.line.keyFile!!.isMissing)
        assertTrue(lock.showsPasswordField)
        assertFalse(lock.offersFingerprint, "no fingerprint for what cannot open")
    }

    @Test
    fun oneThatOpensWithItsKeyFileAloneShowsNoFieldUnlockIsEnough() {
        val keys = DatabaseRecord(DatabaseId("k"), "$root/Documents/Taxes/Taxes.kdbx", keyFilePath = "$root/k/taxes.keyx", opensWithoutPassword = true)
        there[keys.path] = now
        there[keys.keyFilePath!!] = now

        val lock = lockScreen(keys)

        assertFalse(lock.showsPasswordField || lock.needsPassword || lock.showsLocate)
        assertTrue(lock.opensWithKeyFileAlone)
        assertTrue(lock.offersFingerprint, "it opens after the fingerprint with nothing stored (plan AD5)")
        assertNull(lock.problem)
    }

    // A file not found

    @Test
    fun aFileNotFoundSaysSoWithLocateInPlaceOfTheField() {
        val crypto = DatabaseRecord(DatabaseId("c"), "$root/Download/Crypto.kdbx", keyFilePath = "$root/k/c.keyx")

        val lock = lockScreen(crypto, personal)

        assertEquals("Crypto.kdbx is not in Download any more. Locate the file, or choose another database.", lock.missingText)
        assertTrue(lock.showsLocate && lock.line.isMissing)
        assertFalse(lock.showsPasswordField || lock.offersFingerprint || lock.offersKeyFile || lock.opensWithKeyFileAlone)
        assertNull(lock.problem, "the file first: its key file is said once it is back")
        assertEquals("Download · not found", lock.line.detail)

        val card = DatabaseRecord(DatabaseId("s"), "/storage/1234-5678/Crypto.kdbx")
        assertEquals("Crypto.kdbx is not in /storage/1234-5678. Put the card in, or locate the file.", lockScreen(card).missingText)
    }

    @Test
    fun withoutAllFilesAccessNothingIsSaidMissing() {
        access = false
        val work = DatabaseRecord(DatabaseId("w"), "$root/Sync/Work/Work.kdbx", keyFilePath = "$root/k/work.keyx")

        val lock = lockScreen(work)

        assertFalse(lock.line.isMissing || lock.line.keyFile!!.isMissing)
        assertNull(lock.problem)
        assertNull(lock.missingText)
        assertEquals("Sync/Work", lock.line.detail)
    }

    // The sheet and Settings

    @Test
    fun theSheetAndSettingsListEveryDatabaseWithItsFolderOrThatItIsNotFound() {
        val work = DatabaseRecord(DatabaseId("w"), "$root/Sync/Work/Work.kdbx", keyFilePath = "$root/k/work.keyx")
        val crypto = DatabaseRecord(DatabaseId("c"), "$root/Download/Crypto.kdbx")
        there[work.path] = now

        val lines = lines(personal, work, crypto)

        assertEquals(listOf("Personal", "Work", "Crypto"), lines.map { it.name })
        assertEquals(listOf(true, false, false), lines.map { it.isCurrent })
        assertEquals(listOf("Sync/KeePass", "Sync/Work", "not found"), lines.map { it.sheetDetail })
        assertEquals(listOf("Sync/KeePass", "Sync/Work", "Not found · Download"), lines.map { it.settingsDetail })
        assertEquals(listOf(false, true, false), lines.map { it.keyFile != null })
    }
}
