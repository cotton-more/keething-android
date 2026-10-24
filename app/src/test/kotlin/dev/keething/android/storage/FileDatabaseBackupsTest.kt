package dev.keething.android.storage

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
    fun onlyTheNewestTenStayAndOtherDatabasesKeepTheirs() {
        backups.keep(byteArrayOf(0), "other.kdbx", at)
        for (i in 0 until 12) backups.keep(byteArrayOf(i.toByte()), "db.kdbx", at.plusSeconds(i * 60L))

        val kept = names().filter { it.startsWith("db ") }
        assertEquals(10, kept.size)
        assertEquals("db 2026-10-20T19-32-05.kdbx", kept.first(), "the two oldest went")
        assertEquals(listOf("other 2026-10-20T19-30-05.kdbx"), names().filter { it.startsWith("other ") })
    }
}
