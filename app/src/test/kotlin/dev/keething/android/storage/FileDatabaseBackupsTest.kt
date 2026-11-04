package dev.keething.android.storage

import dev.keething.vault.databases.DatabaseId
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDatabaseBackupsTest {
    private val folder: File = Files.createTempDirectory("backups-test").toFile()
    private val backups = FileDatabaseBackups(File(folder, "backups"), zone = ZoneOffset.UTC)
    private val at = Instant.parse("2026-10-20T19:30:05Z")

    @AfterTest
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    private fun names() = File(folder, "backups").list().orEmpty().sorted()

    /** A save that went through: its copy kept, then the oldest beyond ten pushed out. */
    private fun FileDatabaseBackups.save(database: ByteArray, fileName: String, at: Instant) {
        keep(database, fileName, at)
        saved()
    }

    @Test
    fun theFileIsKeptUnderItsNameAndTheTime() {
        backups.keep(byteArrayOf(1, 2), "1Password.kdbx", at)

        assertEquals(listOf("1Password 2026-10-20T19-30-05.kdbx"), names())
        assertContentEquals(byteArrayOf(1, 2), File(folder, "backups/1Password 2026-10-20T19-30-05.kdbx").readBytes())
    }

    @Test
    fun twoSavesWithinASecondKeepTwoFiles() {
        backups.keep(byteArrayOf(1), "db.kdbx", at)
        backups.keep(byteArrayOf(2), "db.kdbx", at)

        assertEquals(listOf("db 2026-10-20T19-30-05.kdbx", "db 2026-10-20T19-30-05_2.kdbx"), names())
    }

    @Test
    fun onlyTheNewestTenStay() {
        for (i in 0 until 12) backups.save(byteArrayOf(i.toByte()), "db.kdbx", at.plusSeconds(i * 60L))

        val kept = names()
        assertEquals(10, kept.size)
        assertEquals("db 2026-10-20T19-32-05.kdbx", kept.first(), "the two oldest went")
    }

    @Test
    fun twoPersonalKdbxInTwoFoldersKeepTheirNewestTenEachInTheirDatabasesFolder() {
        val base = File(folder, "backups")
        val one = FileDatabaseBackups(File(base, DatabaseId("one").fileName), zone = ZoneOffset.UTC)
        val two = FileDatabaseBackups(File(base, DatabaseId("two").fileName), zone = ZoneOffset.UTC)

        for (i in 0 until 12) {
            one.save("one $i".encodeToByteArray(), "Personal.kdbx", at.plusSeconds(i * 60L))
            two.save("two $i".encodeToByteArray(), "Personal.kdbx", at.plusSeconds(i * 60L + 1))
        }

        val oneNames = File(base, "one").list().orEmpty().sorted()
        assertEquals(10, oneNames.size)
        assertEquals(10, File(base, "two").list().orEmpty().size)
        assertEquals("one 11", File(base, "one/${oneNames.last()}").readText())
    }

    @Test
    fun aFolderIsPrunedAsOneDatabasesWhateverTheFileWasCalledAndOtherFilesAreLeftAlone() {
        val dir = File(folder, "backups").also { it.mkdirs() }
        File(dir, "README.txt").writeText("notes")
        File(dir, "Crypto 2026-10-20.kdbx").writeText("not a backup's name")
        for (i in 0 until 6) backups.save(byteArrayOf(), "Crypto.kdbx", at.plusSeconds(i.toLong()))
        // Located again under another name: the older backups are its own too.
        for (i in 6 until 12) backups.save(byteArrayOf(), "Crypto 2.kdbx", at.plusSeconds(i.toLong()))

        val kept = names()
        assertEquals(12, kept.size, "10 backups, the README and the other file")
        assertTrue("README.txt" in kept && "Crypto 2026-10-20.kdbx" in kept)
        assertEquals(6, kept.count { it.startsWith("Crypto 2 ") })
        assertEquals(4, kept.count { it.startsWith("Crypto 20") && it != "Crypto 2026-10-20.kdbx" }, "the two oldest went")
    }

    @Test
    fun aTenthSaveWithinASecondSortsAfterTheNinth() {
        for (i in 0 until 12) backups.save(byteArrayOf(i.toByte()), "db.kdbx", at)

        val kept = names()
        assertEquals(10, kept.size)
        assertFalse("db 2026-10-20T19-30-05.kdbx" in kept || "db 2026-10-20T19-30-05_2.kdbx" in kept, "the first two went")
        assertTrue("db 2026-10-20T19-30-05_12.kdbx" in kept)
    }

    // spec: kdbx.hardening/AC9
    @Test
    fun aSaveThatDidNotReplaceTheFileTakesItsCopyBackAndPushesNoneOut() {
        for (i in 0 until 10) backups.save(byteArrayOf(i.toByte()), "db.kdbx", at.plusSeconds(i * 60L))
        val before = names()

        repeat(3) { attempt ->
            backups.keep(byteArrayOf(99), "db.kdbx", at.plusSeconds(3600L + attempt))
            assertEquals(11, names().size, "kept, flushed, before the replace")
            backups.notSaved()
        }

        assertEquals(before, names(), "the ten as they were")
    }

    @Test
    fun theOldestGoOnlyOnceASaveReplacedTheFile() {
        for (i in 0 until 10) backups.save(byteArrayOf(i.toByte()), "db.kdbx", at.plusSeconds(i * 60L))

        backups.keep(byteArrayOf(10), "db.kdbx", at.plusSeconds(3600))
        assertEquals(11, names().size)
        backups.saved()

        assertEquals(10, names().size)
        assertFalse("db 2026-10-20T19-30-05.kdbx" in names(), "the oldest went")
    }

    // spec: kdbx.backups/AC3
    @Test
    fun aTimeZoneChangeDoesNotPushTheNewestBackupOut() {
        // L11: ten saves in Berlin, one more three hours later in New York, whose local time sorts first by name.
        val dir = File(folder, "backups")
        val berlin = FileDatabaseBackups(dir, zone = java.time.ZoneId.of("Europe/Berlin"))
        for (i in 0 until 10) {
            berlin.save(byteArrayOf(i.toByte()), "db.kdbx", at.plusSeconds(i * 60L))
            Thread.sleep(5)
        }
        val newYork = FileDatabaseBackups(dir, zone = java.time.ZoneId.of("America/New_York"))

        newYork.save(byteArrayOf(10), "db.kdbx", at.plusSeconds(3 * 3600L))

        assertEquals(10, names().size)
        assertTrue("db 2026-10-20T18-30-05.kdbx" in names(), "the New York one, newest though its name sorts first")
        assertFalse("db 2026-10-20T21-30-05.kdbx" in names(), "Berlin's oldest went")
    }

    @Test
    fun aBackupsNameSortsByItsTimeThenItsNumber() {
        assertEquals("2026-10-20T19-30-05_01", FileDatabaseBackups.order("db 2026-10-20T19-30-05.kdbx"))
        assertEquals("2026-10-20T19-30-05_10", FileDatabaseBackups.order("My db 2026-10-20T19-30-05_10.kdbx"))
        for (name in listOf("db.kdbx", "db 2026-10-20T19-30-05_1.kdbx", "db 2026-10-20T19-30-05_100.kdbx", "db 2026-10-20 19-30-05.kdbx", "db 2026-10-20T19-30-05.txt")) {
            assertNull(FileDatabaseBackups.order(name), name)
        }
    }
}
