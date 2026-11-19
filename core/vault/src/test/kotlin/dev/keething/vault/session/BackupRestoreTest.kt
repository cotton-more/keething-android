package dev.keething.vault.session

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.BackupDamagedException
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.saving.BackupRestorer
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.saving.VaultSaver
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A database's backup restored (keething-spec databases.backups-window AC6-AC9). */
class BackupRestoreTest {
    /** As the app's: copies by name, oldest first; the running save's or restore's copy goes when it says it did not replace the file. */
    private class Backups : DatabaseBackups {
        val copies = LinkedHashMap<String, ByteArray>()
        var isOn = true
        var failKeep = false
        var damaged = false

        /** The backups a restore said it restored, in order. */
        val restored = mutableListOf<String>()
        private var pending: String? = null
        private var made = 0

        override val on: Boolean get() = isOn

        override fun keep(database: ByteArray, fileName: String, at: Instant) {
            if (failKeep) throw IOException("no room")
            val name = "copy ${++made}"
            copies[name] = database.copyOf()
            pending = name
        }

        override fun keepBeforeRestoring(database: InputStream, fileName: String, at: Instant): String {
            if (failKeep) throw IOException("no room")
            val name = "copy ${++made} (before restoring)"
            copies[name] = database.readBytes()
            pending = name
            return name
        }

        override fun saved() {
            pending = null
        }

        override fun restored(restoredName: String) {
            pending = null
            restored += restoredName
        }

        override fun notSaved() {
            pending?.let(copies::remove)
            pending = null
        }

        override fun holding(sha256: ByteArray): String? = copies.entries.firstOrNull { MessageDigest.isEqual(sha(it.value), sha256) }?.key

        override fun read(name: String): ByteArray? {
            if (damaged) throw BackupDamagedException("$name changed")
            return copies[name]?.copyOf()
        }

        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private class Fixture(scope: TestScope, val store: FakeStore) {
        val backups = Backups()
        val session = VaultSession(
            store = store,
            opener = VaultOpener(),
            backups = backups,
            clock = FakeClock(scope.testScheduler),
            scope = scope.backgroundScope,
            io = StandardTestDispatcher(scope.testScheduler),
        )
        val unlocked: SessionState.Unlocked get() = assertIs<SessionState.Unlocked>(session.state.value)
    }

    private val now = Instant.parse("2026-11-12T15:12:00Z")

    /** The database as a backup has it: an entry's notes as they were. */
    private val backup = fixtureBytes("kdbx4-aes-aeskdf")

    /** The database now: the backup with one entry changed since. */
    private val current: ByteArray by lazy { changed(backup, "changed since") }

    private fun changed(bytes: ByteArray, notes: String): ByteArray {
        val store = FakeStore(bytes)
        val key = DatabaseKey.of(Secret.of("pw"))
        val entry = KdbxReader.open(bytes, key).document.entries.first()
        VaultSaver(store, DatabaseBackups { _, _, _ -> }).save(VaultEdit.Update(entry.uuid, entry.lastModified, EntryChanges(notes = notes)), key, now)
        return store.content!!
    }

    /** The backup made before a master key change: it opens with "old", not with "pw". */
    private val olderKey: ByteArray by lazy {
        val store = FakeStore(backup)
        VaultSaver(store, DatabaseBackups { _, _, _ -> }).changeKey(DatabaseKey.of(Secret.of("old")), DatabaseKey.of(Secret.of("pw")), now)
        store.content!!
    }

    private fun TestScope.locked(file: ByteArray = current, backupBytes: ByteArray = backup): Fixture =
        Fixture(this, FakeStore(file)).also { it.backups.copies["old"] = backupBytes.copyOf() }

    private fun TestScope.unlocked(file: ByteArray = current, backupBytes: ByteArray = backup): Fixture =
        locked(file, backupBytes).also {
            assertNotNull(it.session.unlock(Secret.of("pw")))
            runCurrent()
            assertIs<SessionState.Unlocked>(it.session.state.value)
        }

    private suspend fun TestScope.restore(f: Fixture, name: String = "old"): RestoreOutcome {
        val outcome = f.session.restoreBackup(name)
        runCurrent()
        return outcome.await()
    }

    // spec: databases.backups-window/AC6
    @Test
    fun theFileIsBackedUpMarkedWithBackupsOffThenReplacedWithTheBackupsBytes() = runTest {
        val f = unlocked()
        f.backups.isOn = false

        val outcome = restore(f)

        assertEquals(RestoreOutcome.Restored(beforeRestoring = "copy 1 (before restoring)", olderKey = false), outcome)
        assertContentEquals(backup, f.store.content, "the backup's bytes as they are")
        assertContentEquals(current, f.backups.copies.getValue("copy 1 (before restoring)"), "the file as it was, backed up with backups off")
        assertEquals(listOf("old"), f.backups.restored)
    }

    // spec: databases.backups-window/AC7
    @Test
    fun anOpenDatabaseBecomesTheBackupReadBackAndItsNextSaveAddsNoCopy() = runTest {
        val f = unlocked()
        assertEquals("changed since", f.unlocked.vault.entries.first().notes)

        restore(f)

        val asKept = assertIs<OpenResult.Opened>(VaultOpener().open(FakeStore(backup), DatabaseKey.of(Secret.of("pw")))).opened.vault
        assertEquals(asKept.entries.first().notes, f.unlocked.vault.entries.first().notes)
        // The restored file is not the session's own, but a backup has its bytes: the next save keeps no copy.
        val entry = f.unlocked.vault.entries.first()
        val saved = assertNotNull(f.session.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "after"))))
        runCurrent()
        assertIs<SaveOutcome.Saved>(saved.await())
        assertEquals(listOf("old", "copy 1 (before restoring)"), f.backups.copies.keys.toList())
    }

    // spec: databases.backups-window/AC7
    @Test
    fun undoRestoresTheFileAsItWasAndAddsNoCopyOfTheBackupItUndoes() = runTest {
        val f = unlocked()
        val restored = assertIs<RestoreOutcome.Restored>(restore(f))

        val undone = restore(f, assertNotNull(restored.beforeRestoring))

        assertEquals(RestoreOutcome.Restored(beforeRestoring = "old", olderKey = false), undone)
        assertContentEquals(current, f.store.content)
        assertEquals(listOf("old", "copy 1 (before restoring)"), f.backups.copies.keys.toList())
        assertEquals("changed since", f.unlocked.vault.entries.first().notes)
    }

    // spec: databases.backups-window/AC7
    @Test
    fun aBackupWithAnOlderMasterKeyIsRestoredAndTheDatabaseLocks() = runTest {
        val f = unlocked(backupBytes = olderKey)

        val outcome = restore(f)

        assertEquals(RestoreOutcome.Restored(beforeRestoring = "copy 1 (before restoring)", olderKey = true), outcome)
        assertEquals(SessionState.Locked(LockReason.RestoredOlderKey), f.session.state.value)
        assertContentEquals(olderKey, f.store.content)
        assertContentEquals(current, f.backups.copies.getValue("copy 1 (before restoring)"))
        // It opens with the key it had then.
        assertNotNull(f.session.unlock(Secret.of("old")))
        runCurrent()
        assertIs<SessionState.Unlocked>(f.session.state.value)
    }

    // spec: databases.backups-window/AC7
    @Test
    fun aLockedDatabaseIsReplacedNothingReadAndUnlocksAsAlways() = runTest {
        val f = locked(backupBytes = olderKey)

        val outcome = restore(f)

        assertEquals(RestoreOutcome.Restored(beforeRestoring = "copy 1 (before restoring)", olderKey = false), outcome)
        assertEquals(SessionState.Locked(LockReason.Initial), f.session.state.value)
        assertContentEquals(olderKey, f.store.content)
        assertNotNull(f.session.unlock(Secret.of("pw")))
        runCurrent()
        assertIs<SessionState.Locked>(f.session.state.value, "the key it had then is asked as always")
    }

    // spec: databases.backups-window/AC6
    @Test
    fun aFileChangedElsewhereDuringTheRestoreIsBackedUpAsItIsThenReplaced() = runTest {
        val f = unlocked()
        val elsewhere = changed(current, "from another device")
        // The second read is the backup's, right before the replace: the file is not what the first read had.
        val reads = f.store.reads
        f.store.beforeRead = { if (it == reads + 2) f.store.change(elsewhere, notify = false) }

        val outcome = assertIs<RestoreOutcome.Restored>(restore(f))

        assertContentEquals(backup, f.store.content)
        assertEquals(listOf("old", outcome.beforeRestoring), f.backups.copies.keys.toList())
        assertContentEquals(elsewhere, f.backups.copies.getValue(assertNotNull(outcome.beforeRestoring)), "the file as it was when it was replaced")
    }

    // spec: databases.backups-window/AC8
    @Test
    fun aRestoreThatCannotWriteLeavesTheFileAndTheBackupsAsTheyWere() = runTest {
        val f = unlocked()
        f.store.failNextReplace = true

        val outcome = restore(f)

        assertEquals(RestoreOutcome.Failed(SaveFailure.WriteFailed("disk full")), outcome)
        assertContentEquals(current, f.store.content)
        assertEquals(listOf("old"), f.backups.copies.keys.toList(), "no backup added")
        assertEquals("changed since", f.unlocked.vault.entries.first().notes)

        f.backups.failKeep = true
        assertIs<RestoreOutcome.Failed>(restore(f))
        assertContentEquals(current, f.store.content, "not replaced without its backup")
    }

    @Test
    fun aRestoreWhoseNewTimeCameOutTooCloseIsRestoredWithAWarning() = runTest {
        val f = unlocked()
        f.store.nextReplaceTooClose = true

        val outcome = assertIs<RestoreOutcome.Restored>(restore(f))

        assertTrue(outcome.mayNotSync)
        assertContentEquals(backup, f.store.content)
        assertNotEquals("changed since", f.unlocked.vault.entries.first().notes, "the vault is the backup read back")
    }

    // spec: databases.backups-window/AC8
    @Test
    fun aBackupGoneOrDamagedReplacesNothing() = runTest {
        val f = unlocked()

        assertEquals(RestoreOutcome.Failed(SaveFailure.BackupGone), restore(f, "gone"))

        f.backups.damaged = true
        assertEquals(RestoreOutcome.Failed(SaveFailure.BackupDamaged), restore(f))
        f.backups.damaged = false

        // A KDBX 4 header that does not match its own SHA-256 (keething-spec databases.backups-window AC5).
        f.backups.copies["old"] = backup.copyOf().also { it[50] = (it[50] + 1).toByte() }
        assertEquals(RestoreOutcome.Failed(SaveFailure.BackupDamaged), restore(f))

        assertContentEquals(current, f.store.content)
        assertEquals(0, f.store.replaced)
        assertEquals(listOf("old"), f.backups.copies.keys.toList())
    }

    // spec: databases.backups-window/AC9
    @Test
    fun aReadOnlyDatabaseRestoresNothing() = runTest {
        val f = unlocked(file = fixtureBytes("kdbx3"))
        assertTrue(f.unlocked.viewOnly)

        assertEquals(RestoreOutcome.Failed(SaveFailure.ViewOnly), restore(f))
        assertContentEquals(fixtureBytes("kdbx3"), f.store.content)
        assertEquals(listOf("old"), f.backups.copies.keys.toList())
    }

    // spec: databases.backups-window/AC6
    @Test
    fun aBackupOfADatabaseWhoseFileIsGoneIsWrittenAsANewFileWithNothingKeptBeforeIt() = runTest {
        val f = locked(file = current)
        f.store.change(null)

        val outcome = restore(f)

        assertEquals(RestoreOutcome.Restored(beforeRestoring = null, olderKey = false), outcome, "no Undo")
        assertContentEquals(backup, f.store.content)
        assertEquals(1, f.store.created)
        assertEquals(0, f.store.replaced)
        assertEquals(listOf("old"), f.backups.copies.keys.toList(), "the backups as they were")
        assertEquals(emptyList(), f.backups.restored, "none pushed out")
        assertNotNull(f.session.unlock(Secret.of("pw")))
        runCurrent()
        assertIs<SessionState.Unlocked>(f.session.state.value)
    }

    // spec: databases.backups-window/AC6
    @Test
    fun anOpenDatabaseWhoseFileIsGoneBecomesTheBackupWrittenAsANewFile() = runTest {
        val f = unlocked()
        f.store.change(null, notify = false)

        val outcome = restore(f)

        assertEquals(RestoreOutcome.Restored(beforeRestoring = null, olderKey = false), outcome)
        assertContentEquals(backup, f.store.content)
        assertNotEquals("changed since", f.unlocked.vault.entries.first().notes, "the vault is the backup")
    }

    // spec: databases.backups-window/AC8
    @Test
    fun aFileThereAgainOrItsFolderGoneByTheWriteIsNotRestoredAndNothingIsWritten() = runTest {
        val f = locked(file = current)
        f.store.change(null)
        f.store.beforeCreate = { f.store.change(current) }

        assertEquals(RestoreOutcome.Failed(SaveFailure.FileThereNow), restore(f))
        assertContentEquals(current, f.store.content, "never written over")
        assertEquals(0, f.store.created)

        f.store.beforeCreate = {}
        f.store.change(null)
        f.store.folderGone = true
        assertEquals(RestoreOutcome.Failed(SaveFailure.FolderGone), restore(f))
        assertEquals(null, f.store.content)
        assertEquals(listOf("old"), f.backups.copies.keys.toList())
    }

    // spec: databases.backups-window/AC6
    @Test
    fun aDatabaseWithoutASessionIsRestoredAsALockedOne() {
        val store = FakeStore(current)
        val backups = Backups().also { it.copies["old"] = backup.copyOf() }

        val outcome = BackupRestorer(store, backups, FakeClock(TestCoroutineScheduler())).restore("old")

        assertEquals(RestoreOutcome.Restored(beforeRestoring = "copy 1 (before restoring)", olderKey = false), outcome)
        assertContentEquals(backup, store.content)
        assertContentEquals(current, backups.copies.getValue("copy 1 (before restoring)"))

        store.change(null)
        assertEquals(RestoreOutcome.Restored(beforeRestoring = null, olderKey = false), BackupRestorer(store, backups, FakeClock(TestCoroutineScheduler())).restore("old"))
        assertContentEquals(backup, store.content, "its file gone: written as a new file")
    }
}
