package dev.keepassbar.android.storage

import dev.keepassbar.domain.storage.DatabaseRead
import dev.keepassbar.domain.time.Clock
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class PathDatabaseStoreTest {
    private val folder: File = Files.createTempDirectory("store-test").toFile()
    private val database = File(folder, "vault.kdbx")
    private val watcher = FakeFolderWatcher()
    private val clock = FixedClock(Instant.parse("2026-06-01T12:00:00Z"))
    private val store = PathDatabaseStore(database, watcher, clock)

    @AfterTest
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun readsTheBytesWithTheVersionTheyWereReadAt() {
        database.writeBytes(byteArrayOf(1, 2, 3))

        val read = assertIs<DatabaseRead.Found>(store.read())

        assertContentEquals(byteArrayOf(1, 2, 3), read.bytes)
        assertEquals(store.version(), read.version)
        assertEquals(3, read.version.size)
    }

    @Test
    fun reportsAMissingFile() {
        assertEquals(DatabaseRead.Missing, store.read())
        assertNull(store.version())
    }

    @Test
    fun replacesTheFileByRenamingAWrittenTemporaryFile() {
        database.writeBytes(byteArrayOf(1))
        val before = store.version()

        store.replace(byteArrayOf(4, 5))

        assertContentEquals(byteArrayOf(4, 5), database.readBytes())
        assertNotEquals(before, store.version())
        assertEquals(listOf("vault.kdbx"), folder.list()?.toList())
    }

    @Test
    fun replaceGivesTheFileANewIdentityEvenWithTheSameSizeAndContent() {
        database.writeBytes(byteArrayOf(1))
        val before = store.version()

        store.replace(byteArrayOf(1))

        assertNotEquals(before?.identity, store.version()?.identity)
    }

    @Test
    fun replaceGivesTheNewFileTheClocksTime() {
        database.writeBytes(byteArrayOf(1))
        modifiedAt(Instant.parse("2026-05-01T12:00:00Z"))

        store.replace(byteArrayOf(2))

        assertEquals(clock.now, store.version()?.modified)
    }

    @Test
    fun replaceGivesATimeAtLeastTwoSecondsAfterTheReplacedFilesForSyncthing() {
        database.writeBytes(byteArrayOf(1))
        val replacedAt = clock.now.minusSeconds(1)
        modifiedAt(replacedAt)

        store.replace(byteArrayOf(2))

        assertEquals(replacedAt.plusSeconds(2), store.version()?.modified)
    }

    @Test
    fun replaceFailsWhenTheNewFileDoesNotKeepItsTime() {
        val store = PathDatabaseStore(database, watcher, clock, setModifiedTime = { _, _ -> })
        database.writeBytes(byteArrayOf(1))
        modifiedAt(Instant.parse("2026-05-01T12:00:00Z"))

        assertFailsWith<IOException> { store.replace(byteArrayOf(2)) }
        assertContentEquals(byteArrayOf(2), database.readBytes())
    }

    @Test
    fun createsTheFileWhenThereIsNone() {
        store.replace(byteArrayOf(7))

        assertContentEquals(byteArrayOf(7), database.readBytes())
    }

    @Test
    fun theTemporaryFileHasANameSyncthingIgnoresAndDoesNotUseItself() {
        val name = PathDatabaseStore.temporaryFileName()

        // Syncthing never scans or sends ".syncthing.*.tmp"; it downloads into ".syncthing.<name>.tmp" itself.
        assertTrue(name.startsWith(".syncthing.") && name.endsWith(".tmp"), name)
        assertNotEquals(".syncthing.vault.kdbx.tmp", name)
        assertNotEquals(name, PathDatabaseStore.temporaryFileName())
    }

    @Test
    fun listsConflictCopiesOfTheDatabaseNewestFirst() {
        database.writeBytes(byteArrayOf(1))
        File(folder, "vault.sync-conflict-20261001-120000-AAAAAAA.kdbx").writeBytes(byteArrayOf(1))
        File(folder, "vault.sync-conflict-20261002-120000-BBBBBBB.kdbx").writeBytes(byteArrayOf(1))
        File(folder, "other.sync-conflict-20261003-120000-CCCCCCC.kdbx").writeBytes(byteArrayOf(1))

        val copies = store.conflictCopies()

        assertEquals(listOf("BBBBBBB", "AAAAAAA"), copies.map { it.deviceId })
    }

    @Test
    fun watchReportsAReplacedDatabase() {
        database.writeBytes(byteArrayOf(1))
        var changes = 0
        store.watch { changes++ }

        PathDatabaseStore(database, FakeFolderWatcher(), clock).replace(byteArrayOf(2))
        watcher.report("vault.kdbx")

        assertEquals(1, changes)
    }

    @Test
    fun watchReportsADeletedDatabase() {
        database.writeBytes(byteArrayOf(1))
        var changes = 0
        store.watch { changes++ }

        database.delete()
        watcher.report("vault.kdbx")

        assertEquals(1, changes)
    }

    @Test
    fun watchReportsANewConflictCopy() {
        database.writeBytes(byteArrayOf(1))
        var changes = 0
        store.watch { changes++ }

        File(folder, "vault.sync-conflict-20261001-120000-AAAAAAA.kdbx").writeBytes(byteArrayOf(1))
        watcher.report("vault.sync-conflict-20261001-120000-AAAAAAA.kdbx")

        assertEquals(1, changes)
    }

    @Test
    fun watchIgnoresEventsThatChangedNothing() {
        database.writeBytes(byteArrayOf(1))
        var changes = 0
        store.watch { changes++ }

        watcher.report("vault.kdbx")
        File(folder, "notes.txt").writeText("x")
        watcher.report("notes.txt")
        watcher.report(null)

        assertEquals(0, changes)
    }

    @Test
    fun watchChecksTheDatabaseWhenTheEventNamesNoFile() {
        database.writeBytes(byteArrayOf(1))
        var changes = 0
        store.watch { changes++ }

        database.writeBytes(byteArrayOf(1, 2))
        watcher.report(null)

        assertEquals(1, changes)
    }

    @Test
    fun closingTheWatchStopsTheFolderWatcher() {
        val handle = store.watch {}

        handle.close()

        assertTrue(watcher.closed)
    }

    private fun modifiedAt(time: Instant) {
        Files.setLastModifiedTime(database.toPath(), FileTime.from(time))
    }
}

private class FixedClock(val now: Instant) : Clock {
    override fun now(): Instant = now

    override fun elapsed(): Duration = Duration.ZERO
}

private class FakeFolderWatcher : FolderWatcher {
    private var onEvent: ((String?) -> Unit)? = null
    var closed = false
        private set

    override fun watch(folder: File, onEvent: (fileName: String?) -> Unit): AutoCloseable {
        this.onEvent = onEvent
        return AutoCloseable { closed = true }
    }

    fun report(fileName: String?) {
        onEvent?.invoke(fileName)
    }
}
