package dev.keething.vault.session

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.saving.VaultSaver
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A conflict copy resolved without a merge (keething-spec kdbx.conflict-copies AC6). */
class ConflictCopyResolveTest {
    /** As the app's: the running save's copy goes when it says it did not save. */
    private class Backups : DatabaseBackups {
        val kept = mutableListOf<Pair<String, ByteArray>>()
        var fail = false
        private var pending = false

        override fun keep(database: ByteArray, fileName: String, at: Instant) {
            if (fail) throw IOException("no room")
            kept += fileName to database.copyOf()
            pending = true
        }

        override fun saved() {
            pending = false
        }

        override fun notSaved() {
            if (pending) kept.removeAt(kept.lastIndex)
            pending = false
        }
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

    private val database = fixtureBytes("kdbx4-aes-aeskdf")
    private val copy = ConflictCopy("test.sync-conflict-20261108-101500-ABCDEF2.kdbx", LocalDateTime.of(2026, 11, 8, 10, 15), "ABCDEF2")

    /** The copy: the database with one entry changed on another device, the same key. */
    private val copyBytes: ByteArray by lazy {
        val store = FakeStore(database)
        val key = DatabaseKey.of(Secret.of("pw"))
        val entry = KdbxReader.open(database, key).document.entries.first()
        val saver = VaultSaver(store, DatabaseBackups { _, _, _ -> })
        saver.save(VaultEdit.Update(entry.uuid, entry.lastModified, EntryChanges(notes = "from the other device")), key, Instant.parse("2026-11-08T10:15:00Z"))
        store.content!!
    }

    private fun TestScope.unlocked(copyBytes: ByteArray = this@ConflictCopyResolveTest.copyBytes): Fixture =
        Fixture(this, FakeStore(database).also { it.addCopy(copy, copyBytes) }).also {
            assertNotNull(it.session.unlock(Secret.of("pw")))
            runCurrent()
            assertEquals(listOf(copy), it.unlocked.conflictCopies)
        }

    // spec: kdbx.conflict-copies/AC6
    @Test
    fun usingTheCopyMakesItTheDatabaseBackedUpFirstAndDeletesTheCopy() = runTest {
        val f = unlocked()

        val outcome = assertNotNull(f.session.takeConflictCopy(copy))
        runCurrent()

        assertEquals(SaveOutcome.Saved(), outcome.await())
        assertContentEquals(copyBytes, f.store.content, "the copy as it is")
        assertEquals(listOf("test.kdbx"), f.backups.kept.map { it.first })
        assertContentEquals(database, f.backups.kept.single().second)
        assertEquals(emptyList(), f.store.copies)
        assertEquals(emptyList(), f.unlocked.conflictCopies)
        assertEquals("from the other device", f.unlocked.vault.entries.first().notes)
        // The session goes on with the copy as its database.
        val entry = f.unlocked.vault.entries.first()
        val next = assertNotNull(f.session.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "after"))))
        runCurrent()
        assertIs<SaveOutcome.Saved>(next.await())
    }

    @Test
    fun aCopyThatDoesNotOpenWithTheDatabasesKeyReplacesNothingAndDoesNotLock() = runTest {
        val f = unlocked(copyBytes = keyFileBytes("keyfile-only.kdbx"))

        val outcome = assertNotNull(f.session.takeConflictCopy(copy))
        runCurrent()

        assertEquals(SaveOutcome.Failed(SaveFailure.CopyNotOpened(OpenFailure.WrongPassword)), outcome.await())
        assertContentEquals(database, f.store.content)
        assertEquals(0, f.store.replaced)
        assertEquals(emptyList(), f.backups.kept)
        assertEquals(listOf(copy), f.unlocked.conflictCopies)
    }

    @Test
    fun aCopyTakenThatCannotBeDeletedAfterwardsIsSaidLeft() = runTest {
        val f = unlocked()
        f.store.failCopyDelete = true

        val outcome = assertNotNull(f.session.takeConflictCopy(copy))
        runCurrent()

        assertEquals(SaveOutcome.Saved(copyLeft = copy.fileName), outcome.await())
        assertContentEquals(copyBytes, f.store.content)
        assertEquals(listOf(copy), f.unlocked.conflictCopies)
    }

    @Test
    fun aCopyGoneMeanwhileReplacesNothing() = runTest {
        val f = unlocked()
        f.store.copyFiles.clear()

        val outcome = assertNotNull(f.session.takeConflictCopy(copy))
        runCurrent()

        assertEquals(SaveOutcome.Failed(SaveFailure.CopyGone), outcome.await())
        assertContentEquals(database, f.store.content)
        assertEquals(emptyList(), f.backups.kept)
    }

    // spec: kdbx.conflict-copies/AC6
    @Test
    fun keepingTheOpenOneDeletesTheCopyKeptAmongTheBackupsFirst() = runTest {
        val f = unlocked()

        val outcome = assertNotNull(f.session.removeConflictCopy(copy))
        runCurrent()

        assertNull(outcome.await())
        assertEquals(listOf(copy.fileName), f.backups.kept.map { it.first })
        assertContentEquals(copyBytes, f.backups.kept.single().second)
        assertEquals(emptyList(), f.unlocked.conflictCopies)
        assertContentEquals(database, f.store.content)
        assertEquals(0, f.store.replaced)
    }

    @Test
    fun aCopyThatCannotBeKeptOrDeletedStaysWithNoBackupOfIt() = runTest {
        val f = unlocked()
        f.backups.fail = true

        val notKept = assertNotNull(f.session.removeConflictCopy(copy))
        runCurrent()
        assertIs<SaveFailure.WriteFailed>(notKept.await())
        assertEquals(listOf(copy), f.unlocked.conflictCopies)

        f.backups.fail = false
        f.store.failCopyDelete = true
        val notDeleted = assertNotNull(f.session.removeConflictCopy(copy))
        runCurrent()
        assertIs<SaveFailure.WriteFailed>(notDeleted.await())
        assertEquals(emptyList(), f.backups.kept, "its backup taken back")
        assertEquals(listOf(copy), f.unlocked.conflictCopies)
    }

    // spec: kdbx.conflict-copies/AC6
    @Test
    fun theCopysContentsAreReadWithTheDatabasesKeyBesideTheOpenOnes() = runTest {
        val f = unlocked()
        val open = VaultSummary.of(f.unlocked.vault)

        val contents = backgroundScope.async { f.session.copyContents(copy) }
        runCurrent()

        val read = assertIs<CopyContents.Read>(contents.await())
        assertEquals(open.entries, read.summary.entries)
        assertEquals(Instant.parse("2026-11-08T10:15:00Z"), read.summary.lastChanged)

        val other = unlocked(copyBytes = keyFileBytes("keyfile-only.kdbx"))
        val notOpened = backgroundScope.async { other.session.copyContents(copy) }
        runCurrent()
        assertEquals(CopyContents.NotOpened(OpenFailure.WrongPassword), notOpened.await())
        assertIs<SessionState.Unlocked>(other.session.state.value)
    }

    @Test
    fun aDatabaseViewedOnlyDoesNotTakeACopy() = runTest {
        val f = Fixture(this, FakeStore(fixtureBytes("kdbx3")).also { it.addCopy(copy, copyBytes) })
        f.session.unlock(Secret.of("pw"))
        runCurrent()

        assertEquals(SaveOutcome.Failed(SaveFailure.ViewOnly), assertNotNull(f.session.takeConflictCopy(copy)).await())
    }
}
