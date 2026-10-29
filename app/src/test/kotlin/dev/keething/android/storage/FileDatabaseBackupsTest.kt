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
        for (i in 0 until 12) backups.keep(byteArrayOf(i.toByte()), "db.kdbx", at.plusSeconds(i * 60L))

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
            one.keep("one $i".encodeToByteArray(), "Personal.kdbx", at.plusSeconds(i * 60L))
            two.keep("two $i".encodeToByteArray(), "Personal.kdbx", at.plusSeconds(i * 60L + 1))
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
        for (i in 0 until 6) backups.keep(byteArrayOf(), "Crypto.kdbx", at.plusSeconds(i.toLong()))
        // Located again under another name: the older backups are its own too.
        for (i in 6 until 12) backups.keep(byteArrayOf(), "Crypto 2.kdbx", at.plusSeconds(i.toLong()))

        val kept = names()
        assertEquals(12, kept.size, "10 backups, the README and the other file")
        assertTrue("README.txt" in kept && "Crypto 2026-10-20.kdbx" in kept)
        assertEquals(6, kept.count { it.startsWith("Crypto 2 ") })
        assertEquals(4, kept.count { it.startsWith("Crypto 20") && it != "Crypto 2026-10-20.kdbx" }, "the two oldest went")
    }

    @Test
    fun aTenthSaveWithinASecondSortsAfterTheNinth() {
        for (i in 0 until 12) backups.keep(byteArrayOf(i.toByte()), "db.kdbx", at)

        val kept = names()
        assertEquals(10, kept.size)
        assertFalse("db 2026-10-20T19-30-05.kdbx" in kept || "db 2026-10-20T19-30-05_2.kdbx" in kept, "the first two went")
        assertTrue("db 2026-10-20T19-30-05_12.kdbx" in kept)
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
