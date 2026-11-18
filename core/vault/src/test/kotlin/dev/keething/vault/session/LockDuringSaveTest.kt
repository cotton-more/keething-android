package dev.keething.vault.session

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.opening.VaultOpener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * keething-spec kdbx.hardening AC3: a lock while a save runs. The file work runs on a thread of its own, which the test
 * holds right before the save's backup (its second read of the file); the session's own time is the test's.
 */
class LockDuringSaveTest {
    private val io = Executors.newSingleThreadExecutor()

    @AfterTest
    fun stopIo() {
        io.shutdownNow()
    }

    private class Held(scope: TestScope, io: kotlinx.coroutines.CoroutineDispatcher) {
        val store = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val backups = mutableListOf<ByteArray>()
        val session = VaultSession(
            store = store,
            opener = VaultOpener(),
            backups = DatabaseBackups { database, _, _ -> backups += database },
            clock = FakeClock(scope.testScheduler),
            scope = scope.backgroundScope,
            io = io,
        )
        private val reached = CountDownLatch(1)
        private val release = CountDownLatch(1)

        /** Holds the next save at its check of the file, the last read before the backup and the replace. */
        fun holdTheNextSave() {
            var reads = 0
            store.beforeRead = {
                if (++reads == 2) {
                    reached.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        }

        /** Holds the first listing of the conflict copies after a read of the file: a reload's, the file opened. */
        fun holdTheNextCopies() {
            var read = false
            var held = false
            store.beforeRead = { read = true }
            store.beforeCopies = {
                if (read && !held) {
                    held = true
                    reached.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        }

        fun awaitHeld() = assertTrue(reached.await(10, TimeUnit.SECONDS), "the save reached its check")

        /** Whether the read held has been reached, waiting a little for it. */
        fun held(): Boolean = reached.await(10, TimeUnit.MILLISECONDS)

        fun release() = release.countDown()

        /**
         * Blocks the test's thread (so its virtual time cannot jump to the lock's deadline meanwhile) until the store
         * replaced the file [times] times.
         */
        fun awaitReplaced(times: Int) {
            val until = System.nanoTime() + 10_000_000_000L
            while (store.replaced < times && System.nanoTime() < until) Thread.sleep(5)
            assertEquals(times, store.replaced)
        }

        val vault get() = (session.state.value as SessionState.Unlocked).vault

        fun notes(id: String): FieldValue? =
            KdbxReader.open(store.content!!, DatabaseKey.of(Secret.of("pw"))).document.entries.single { it.uuid == id }.fields.lastOrNull { it.key == "Notes" }?.value
    }

    private suspend fun TestScope.unlocked(): Held = Held(this, io.asCoroutineDispatcher()).also {
        assertEquals(UnlockOutcome.Opened, it.session.unlock(Secret.of("pw"))!!.await())
    }

    // spec: kdbx.hardening/AC3
    @Test
    fun aLockHidesTheVaultAtOnceAndASaveThatFinishesWithinTenSecondsIsInTheFile() = runTest {
        val f = unlocked()
        val entry = f.vault.entries.first()
        val vault = f.vault
        f.holdTheNextSave()
        val outcome = assertNotNull(f.session.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "kept"))))
        runCurrent()
        f.awaitHeld()

        f.session.lock()
        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value, "hidden at once")
        assertTrue(vault.entries.all { it.password.isWiped })
        assertNull(f.session.save(VaultEdit.Update(entry.id, null, EntryChanges(notes = "later"))), "no new save starts")
        advanceTimeBy(9.seconds)
        runCurrent()
        f.release()
        f.awaitReplaced(1)

        assertEquals(SaveOutcome.Interrupted, outcome.await())
        assertEquals(1, f.store.replaced)
        assertEquals(FieldValue.Plain("kept"), f.notes(entry.id))
        assertEquals(UnlockOutcome.Opened, f.session.unlock(Secret.of("pw"))!!.await())
        assertEquals(emptyList(), (f.session.state.value as SessionState.Unlocked).unsaved, "nothing to say: it was saved")
    }

    // spec: kdbx.external-changes/AC7
    @Test
    fun aReloadThatEndsAfterALockIsDiscardedAndTheSessionStaysLocked() = runTest {
        val f = unlocked()
        val vault = f.vault
        // Held after the file is read and opened, before the reload looks whether a lock came meanwhile.
        f.holdTheNextCopies()
        f.store.change(fixtureBytes("kdbx4-aes-aeskdf"))
        advanceTimeBy(2.seconds)
        // The reload goes to the file's thread and back: the session's side is run meanwhile.
        repeat(500) { if (!f.held()) runCurrent() }
        f.awaitHeld()

        f.session.lock()
        f.release()
        // Until the file work on its thread is over and the session has seen how the reload ended.
        repeat(3) {
            io.submit {}.get(10, TimeUnit.SECONDS)
            runCurrent()
        }

        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value)
        assertTrue(vault.entries.all { it.password.isWiped })
        assertEquals(0, f.store.watching, "no longer watched")
    }

    // spec: kdbx.hardening/AC3
    @Test
    fun aSaveNotFinishedTenSecondsAfterTheLockStopsBeforeTheBackupAndTheReplaceAndTheNextUnlockSaysWhichChange() = runTest {
        val f = unlocked()
        val entry = f.vault.entries.first()
        val before = f.store.content!!
        f.holdTheNextSave()
        val outcome = assertNotNull(f.session.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "lost"))))
        runCurrent()
        f.awaitHeld()

        f.session.lock()
        advanceTimeBy(10.seconds + 1.milliseconds)
        runCurrent()
        f.release()

        assertEquals(SaveOutcome.Interrupted, outcome.await())
        assertEquals(0, f.store.replaced)
        assertContentEquals(before, f.store.content, "the file as it was")
        assertEquals(0, f.backups.size, "the backups as they were")

        assertEquals(UnlockOutcome.Opened, f.session.unlock(Secret.of("pw"))!!.await())
        val unlocked = f.session.state.value as SessionState.Unlocked
        assertEquals(listOf(UnsavedChange(UnsavedChange.Kind.ENTRY_CHANGED, entry.id)), unlocked.unsaved)
        assertEquals(entry.notes, unlocked.vault.entries.single { it.id == entry.id }.notes, "the notes as they were")

        f.session.unsavedSaid()
        assertEquals(emptyList(), (f.session.state.value as SessionState.Unlocked).unsaved)
        f.session.lock()
        f.session.unlock(Secret.of("pw"))!!.await()
        assertEquals(emptyList(), (f.session.state.value as SessionState.Unlocked).unsaved, "said once")
    }

    // spec: kdbx.hardening/AC3
    @Test
    fun aSaveWaitingItsTurnWhenTheLockCameDoesNotStartAndIsSaidAfterTheUnlock() = runTest {
        val f = unlocked()
        val (one, two) = f.vault.entries.take(2)
        f.holdTheNextSave()
        val first = assertNotNull(f.session.save(VaultEdit.Update(one.id, one.lastModified, EntryChanges(notes = "first"))))
        val second = assertNotNull(f.session.save(VaultEdit.MoveToRecycleBin(two.id, two.lastModified)))
        runCurrent()
        f.awaitHeld()

        f.session.lock()
        f.release()
        f.awaitReplaced(1)

        assertEquals(SaveOutcome.Interrupted, first.await())
        assertEquals(SaveOutcome.Interrupted, second.await())
        assertEquals(1, f.store.replaced, "the first finished; the second never started")
        f.session.unlock(Secret.of("pw"))!!.await()
        assertEquals(listOf(UnsavedChange(UnsavedChange.Kind.ENTRY_TO_RECYCLE_BIN, two.id)), (f.session.state.value as SessionState.Unlocked).unsaved)
    }
}
