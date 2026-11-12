package dev.keething.android.storage

import dev.keething.vault.databases.DatabaseId
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
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

    /** The folder's files but the copies' SHA-256. */
    private fun names() = File(folder, "backups").list().orEmpty().filter { it != FileDatabaseBackups.DIGESTS }.sorted()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

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
    fun aCopyFromAStreamIsTheWholeStreamAndASaveThatDidNotReplaceTheFileTakesItBack() {
        val database = ByteArray(200_000) { it.toByte() }

        backups.keep(ByteArrayInputStream(database), "Personal.kdbx", at)

        assertContentEquals(database, File(folder, "backups/Personal 2026-10-20T19-30-05.kdbx").readBytes())
        backups.notSaved()
        assertEquals(emptyList(), names())
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

    // spec: databases.backups/AC3
    @Test
    fun theNumberKeptIsTheDatabasesSettingAtEachSave() {
        var count = 5
        val backups = FileDatabaseBackups(File(folder, "backups"), zone = ZoneOffset.UTC, count = { count })
        for (i in 0 until 7) backups.save(byteArrayOf(i.toByte()), "db.kdbx", at.plusSeconds(i * 60L))
        assertEquals(5, names().size)

        count = 20
        for (i in 7 until 30) backups.save(byteArrayOf(i.toByte()), "db.kdbx", at.plusSeconds(i * 60L))
        assertEquals(20, names().size)
    }

    // spec: databases.backups-window/AC2
    @Test
    fun fewerKeptDeletesTheOldestAtOnceAndDeletingAllLeavesOtherFiles() {
        val dir = File(folder, "backups")
        for (i in 0 until 8) backups.save(byteArrayOf(i.toByte()), "db.kdbx", at.plusSeconds(i * 60L))
        File(dir, "notes.txt").writeText("mine")
        assertEquals(FileDatabaseBackups.Summary(8, 8), backups.summary())

        assertEquals(3, backups.prune(5))
        assertEquals("db 2026-10-20T19-33-05.kdbx", names().first(), "the three oldest went")
        assertEquals(FileDatabaseBackups.Summary(5, 5), backups.summary())

        assertEquals(5, backups.deleteAll())
        assertEquals(listOf("notes.txt"), names())
        assertEquals(FileDatabaseBackups.Summary(0, 0), backups.summary())
    }

    // spec: databases.backups/AC1
    @Test
    fun whetherACopyHasTheSameBytesIsKnownFromTheirSha256WrittenBesideThem() {
        val dir = File(folder, "backups")
        backups.save(byteArrayOf(1, 2, 3), "db.kdbx", at)
        backups.save(byteArrayOf(4, 5), "db.kdbx", at.plusSeconds(60))
        assertTrue(backups.holds(sha256(byteArrayOf(1, 2, 3))))
        assertFalse(backups.holds(sha256(byteArrayOf(9))))
        // Written as each copy was kept: another object (the app started again) knows them without reading them.
        val lines = File(dir, FileDatabaseBackups.DIGESTS).readLines()
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.startsWith(sha256(byteArrayOf(4, 5)).toHexString() + " 2 db ") })
        assertTrue(FileDatabaseBackups(dir, zone = ZoneOffset.UTC).holds(sha256(byteArrayOf(4, 5))))

        // A copy that was taken back is not one.
        backups.keep(byteArrayOf(7), "db.kdbx", at.plusSeconds(120))
        backups.notSaved()
        assertFalse(backups.holds(sha256(byteArrayOf(7))))
    }

    @Test
    fun aCopyWithoutItsLineIsReadOnceAndAPrunedOneLosesIts() {
        val dir = File(folder, "backups").apply { mkdirs() }
        // Kept before the SHA-256 were written beside them.
        File(dir, "db 2026-10-20T19-30-05.kdbx").writeBytes(byteArrayOf(1))
        File(dir, "db 2026-10-20T19-31-05.kdbx").writeBytes(byteArrayOf(2))

        assertTrue(backups.holds(sha256(byteArrayOf(1))))
        assertTrue(backups.holds(sha256(byteArrayOf(2))))
        assertEquals(2, File(dir, FileDatabaseBackups.DIGESTS).readLines().size)

        // A line that does not match its copy's size is not trusted.
        File(dir, "db 2026-10-20T19-31-05.kdbx").writeBytes(byteArrayOf(3, 3))
        val fresh = FileDatabaseBackups(dir, zone = ZoneOffset.UTC)
        assertTrue(fresh.holds(sha256(byteArrayOf(3, 3))))
        assertFalse(fresh.holds(sha256(byteArrayOf(2))))

        fresh.prune(1)
        assertEquals(1, File(dir, FileDatabaseBackups.DIGESTS).readLines().size)
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

        val oneNames = File(base, "one").list().orEmpty().filter { it != FileDatabaseBackups.DIGESTS }.sorted()
        assertEquals(10, oneNames.size)
        assertEquals(10, File(base, "two").list().orEmpty().count { it != FileDatabaseBackups.DIGESTS })
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

    // spec: databases.backups-window/AC1
    @Test
    fun theBackupsAreListedNewestFirstWithWhenTheyWereWrittenAndTheirSize() {
        val dir = File(folder, "backups")
        for (i in 0 until 3) backups.save(ByteArray(10 + i), "db.kdbx", at.plusSeconds(i * 60L))
        File(dir, "notes.txt").writeText("mine")
        // Written at 19:30, 19:31 and 19:32, the middle one's time put last: the time it was written decides, not its name.
        File(dir, "db 2026-10-20T19-30-05.kdbx").setLastModified(at.toEpochMilli())
        File(dir, "db 2026-10-20T19-31-05.kdbx").setLastModified(at.plusSeconds(600).toEpochMilli())
        File(dir, "db 2026-10-20T19-32-05.kdbx").setLastModified(at.plusSeconds(120).toEpochMilli())

        val listed = backups.list()

        assertEquals(listOf("db 2026-10-20T19-31-05.kdbx", "db 2026-10-20T19-32-05.kdbx", "db 2026-10-20T19-30-05.kdbx"), listed.map { it.name })
        assertEquals(listOf(11L, 12L, 10L), listed.map { it.bytes })
        assertEquals(at.plusSeconds(600), listed.first().at)
        assertEquals(emptyList(), FileDatabaseBackups(File(folder, "none")).list())
    }

    // spec: databases.backups-window/AC5
    @Test
    fun aBackupWhoseHeaderDoesNotMatchItsChecksumIsListedAsDamaged() {
        val database = File(System.getProperty("keething.fixtures"), "kdbx4-aes-argon2d.kdbx").readBytes()
        backups.save(database, "db.kdbx", at)
        backups.save(database.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }, "db.kdbx", at.plusSeconds(60))
        backups.save(database.copyOf(60), "db.kdbx", at.plusSeconds(120))

        val damaged = backups.list().associate { it.name to it.damaged }

        assertEquals(
            mapOf("db 2026-10-20T19-30-05.kdbx" to false, "db 2026-10-20T19-31-05.kdbx" to true, "db 2026-10-20T19-32-05.kdbx" to true),
            damaged,
        )
    }

    // spec: databases.backups-window/AC4
    @Test
    fun oneBackupIsDeletedByItsNameAndNoOtherFile() {
        val dir = File(folder, "backups")
        for (i in 0 until 3) backups.save(byteArrayOf(i.toByte()), "db.kdbx", at.plusSeconds(i * 60L))
        File(dir, "notes.txt").writeText("mine")

        assertTrue(backups.delete("db 2026-10-20T19-31-05.kdbx"))

        assertEquals(listOf("db 2026-10-20T19-30-05.kdbx", "db 2026-10-20T19-32-05.kdbx", "notes.txt"), names())
        assertFalse(backups.delete("db 2026-10-20T19-31-05.kdbx"), "gone already")
        assertFalse(backups.delete("notes.txt"), "not a backup")
        assertFalse(backups.delete("../backups/db 2026-10-20T19-30-05.kdbx"), "not a name in the folder")
        assertEquals(3, names().size)
        assertFalse(File(dir, FileDatabaseBackups.DIGESTS).readText().contains("19-31-05"), "its SHA-256 went with it")
        assertTrue(backups.holds(sha256(byteArrayOf(0))))
        assertFalse(backups.holds(sha256(byteArrayOf(1))))
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
