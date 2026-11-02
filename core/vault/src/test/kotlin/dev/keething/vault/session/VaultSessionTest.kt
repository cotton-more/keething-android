package dev.keething.vault.session

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.opening.HistoryResult
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.saving.VaultSaver
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import dev.keething.domain.storage.StoppedSave
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class VaultSessionTest {
    private class Fixture(scope: TestScope, val store: FakeStore) {
        val clock = FakeClock(scope.testScheduler)
        val backups = mutableListOf<ByteArray>()
        val session = VaultSession(
            store = store,
            opener = VaultOpener(),
            backups = DatabaseBackups { database, _, _ -> backups += database },
            clock = clock,
            scope = scope.backgroundScope,
            io = StandardTestDispatcher(scope.testScheduler),
        )
    }

    private fun TestScope.fixture(name: String = "kdbx4-aes-aeskdf") = Fixture(this, FakeStore(fixtureBytes(name)))

    private fun TestScope.unlocked(name: String = "kdbx4-aes-aeskdf"): Fixture = fixture(name).also {
        assertNotNull(it.session.unlock(Secret.of("pw")))
        runCurrent()
        assertIs<SessionState.Unlocked>(it.session.state.value)
    }

    private val Fixture.vault get() = (session.state.value as SessionState.Unlocked).vault
    private val Fixture.unlockedState get() = session.state.value as SessionState.Unlocked

    // Unlock and lock

    @Test
    fun unlockOpensTheVault() = runTest {
        val f = fixture()

        f.session.unlock(Secret.of("pw"))
        assertEquals(SessionState.Unlocking, f.session.state.value)
        runCurrent()

        assertEquals(2, f.vault.entries.size)
        assertNotNull(f.unlockedState.keyDerivation)
        assertEquals(1, f.store.watching, "the folder is watched while unlocked")
    }

    @Test
    fun aWrongPasswordOrAMissingFileStaysLockedWithTheReason() = runTest {
        val wrong = fixture()
        wrong.session.unlock(Secret.of("nope"))
        runCurrent()
        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongPassword)), wrong.session.state.value)

        val missing = Fixture(this, FakeStore(null))
        missing.session.unlock(Secret.of("pw"))
        runCurrent()
        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.Missing)), missing.session.state.value)
        assertEquals(0, missing.store.watching)
    }

    @Test
    fun unlockOnlyFromLocked() = runTest {
        val f = unlocked()

        assertNull(f.session.unlock(Secret.of("pw")))
    }

    @Test
    fun anUnlockTellsHowItEnded() = runTest {
        val opened = fixture().session.unlock(Secret.of("pw"))!!
        val wrong = fixture().session.unlock(Secret.of("nope"))!!
        val interrupted = fixture().let { f -> f.session.unlock(Secret.of("pw"))!!.also { f.session.lock() } }
        runCurrent()

        assertEquals(UnlockOutcome.Opened, opened.await())
        assertEquals(UnlockOutcome.Failed(OpenFailure.WrongPassword), wrong.await())
        assertEquals(UnlockOutcome.Interrupted, interrupted.await(), "a lock came first")
    }

    @Test
    fun theLockWipesVaultAttachmentsAndKey() = runTest {
        val f = unlocked()
        val vault = f.vault
        val entry = vault.entries.single { it.title == "Second renamed" }
        assertEquals(3000, f.session.attachment(entry.id, "att.bin")?.size)

        f.session.lock()

        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value)
        assertTrue(vault.entries.all { it.password.isWiped })
        assertNull(f.session.attachment(entry.id, "att.bin"))
        runCurrent() // the watch closes on io
        assertEquals(0, f.store.watching)
        f.store.change(fixtureBytes("kdbx4-aes-argon2d"))
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value, "no reload without the key")
    }

    @Test
    fun aLockCausedByTellingTheOpeningEndsTheUnlockWithNothingLeftOpen() = runTest {
        // The coordinator hears of every state at once and may drop the session then (its database left the window).
        val f = fixture()
        var seen: Vault? = null
        f.session.onStateChange = {
            if (it is SessionState.Unlocked) {
                seen = it.vault
                f.session.lock()
            }
        }

        val outcome = assertNotNull(f.session.unlock(Secret.of("pw")))
        runCurrent()

        assertTrue(assertNotNull(seen).entries.all { it.password.isWiped }, "the vault it was told of is wiped")
        assertEquals(UnlockOutcome.Interrupted, outcome.await(), "nothing to store for the fingerprint")
        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value)
        assertEquals(0, f.store.watching, "no watcher left on a locked session")
    }

    @Test
    fun aLockWhileUnlockingWipesWhatArrivesLater() = runTest {
        val f = fixture()
        f.session.unlock(Secret.of("pw"))

        f.session.lock()
        runCurrent()

        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value)
    }

    // Reloads (SPEC §3.2)

    @Test
    fun aSaveTheAppDidNotFinishPutRightIsSaidUntilTheAppSaidIt() = runTest {
        // spec: databases.saf/AC5
        val f = fixture()
        f.store.stoppedSave = StoppedSave.UNDONE
        f.session.unlock(Secret.of("pw"))
        runCurrent()
        assertEquals(StoppedSave.UNDONE, f.unlockedState.stoppedSave, "which way, from the read")

        val entry = f.vault.entries.first()
        assertIs<SaveOutcome.Saved>(assertNotNull(f.session.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "x")))).await())
        assertEquals(StoppedSave.UNDONE, f.unlockedState.stoppedSave, "a save's own reading keeps it")

        f.store.change(fixtureBytes("kdbx4-aes-argon2d"))
        f.session.reload()
        runCurrent()
        assertEquals(StoppedSave.UNDONE, f.unlockedState.stoppedSave, "a read after it keeps saying it")
        assertEquals(0, f.store.stoppedSavesSaid)

        f.session.stoppedSaveSaid()
        runCurrent()
        assertNull(f.unlockedState.stoppedSave)
        assertEquals(1, f.store.stoppedSavesSaid, "the store says it no more")
        f.session.reload()
        runCurrent()
        assertNull(f.unlockedState.stoppedSave)

        f.store.stoppedSave = StoppedSave.FINISHED
        f.session.lock()
        f.session.unlock(Secret.of("pw"))
        runCurrent()
        assertEquals(StoppedSave.FINISHED, f.unlockedState.stoppedSave, "another one, at the next unlock")
    }

    @Test
    fun aChangeIsReadOnceTheFolderIsQuiet() = runTest {
        val f = unlocked()
        val before = f.vault
        val reads = f.store.reads

        f.store.change(fixtureBytes("kdbx4-aes-argon2d"))
        advanceTimeBy(1.seconds)
        f.store.notifyWatchers() // the same change reported again
        advanceTimeBy(1.4.seconds)
        runCurrent()
        assertEquals(reads, f.store.reads, "not before the folder settled")
        advanceTimeBy(0.2.seconds)
        runCurrent()

        assertEquals(reads + 1, f.store.reads, "one read for the change")
        assertNotSame(before, f.vault)
        assertNotNull(f.unlockedState.keyDerivation, "other KDF parameters: derived again")
        assertFalse(before.entries.first().password.isWiped, "an earlier vault stays usable until the lock")
        f.session.lock()
        assertTrue(before.entries.first().password.isWiped, "and is wiped then")
    }

    @Test
    fun theSameFileReadAgainNeedsNoKeyDerivation() = runTest {
        val f = unlocked()

        f.store.change(fixtureBytes("kdbx4-aes-aeskdf"))
        advanceTimeBy(2.seconds)
        runCurrent()

        assertNull(f.unlockedState.keyDerivation)
    }

    @Test
    fun aDamagedFileKeepsTheVaultAndTheNextChangeRecovers() = runTest {
        val f = unlocked()
        val before = f.vault
        val damaged = fixtureBytes("kdbx4-aes-aeskdf").apply { this[size - 100] = (this[size - 100].toInt() xor 1).toByte() }

        f.store.change(damaged)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertSame(before, f.vault)
        assertEquals(OpenFailure.Damaged("block 0 HMAC"), f.unlockedState.reloadFailure)

        val reads = f.store.reads
        f.store.notifyWatchers() // the same damaged version
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(reads, f.store.reads, "the same damaged version is not read again")

        f.store.change(fixtureBytes("kdbx4-aes-aeskdf"))
        advanceTimeBy(2.seconds)
        runCurrent()
        assertNull(f.unlockedState.reloadFailure)
        assertNotSame(before, f.vault)
    }

    @Test
    fun aMissingFileIsWaitedFor() = runTest {
        val f = unlocked()
        val before = f.vault

        f.store.change(null)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertSame(before, f.vault)
        assertNull(f.unlockedState.reloadFailure, "not reported while waiting")
        f.store.change(fixtureBytes("kdbx4-aes-argon2d"), notify = false)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertNotSame(before, f.vault)
        assertNull(f.unlockedState.reloadFailure)
    }

    @Test
    fun aFileMissingForLongIsReported() = runTest {
        val f = unlocked()

        f.store.change(null)
        advanceTimeBy(20.seconds)
        runCurrent()

        assertEquals(OpenFailure.Missing, f.unlockedState.reloadFailure)
    }

    /** Keeps every vault it read, a reload's copy too. */
    private class KeepingOpener : VaultOpener() {
        val read = mutableListOf<Vault>()

        override fun open(store: DatabaseStore, key: DatabaseKey): OpenResult =
            super.open(store, key).also { result -> (result as? OpenResult.Opened)?.let { read += it.opened.vault } }
    }

    @Test
    fun aReloadThatReadsTheSameVaultAgainWipesTheCopyItReadAndKeepsTheOneShown() = runTest {
        // The state keeps an equal one before (a StateFlow), so the copy just read would be nobody's, never wiped.
        val opener = KeepingOpener()
        val store = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val session = VaultSession(store, opener, { _, _, _ -> }, FakeClock(testScheduler), backgroundScope, StandardTestDispatcher(testScheduler))
        assertNotNull(session.unlock(Secret.of("pw")))
        runCurrent()

        repeat(2) {
            store.change(fixtureBytes("kdbx4-aes-aeskdf")) // a new version of the same contents (touched, rewritten)
            advanceTimeBy(2.seconds)
            runCurrent()
        }

        val shown = assertIs<SessionState.Unlocked>(session.state.value).vault
        assertEquals(3, opener.read.size, "the unlock and two reloads")
        val again = opener.read.last()
        assertNotSame(shown, again, "the state kept the equal vault before")
        assertTrue(again.entries.all { it.password.isWiped }, "the copy nobody holds is wiped at once")
        assertFalse(shown.entries.all { it.password.isWiped }, "the one shown works until the lock")
        session.lock()
        assertTrue(shown.entries.all { it.password.isWiped })
    }

    @Test
    fun aKeyThatNoLongerOpensTheFileLocks() = runTest {
        val f = unlocked()
        // A changed stored HMAC: the header checksum still matches, so it reads as a wrong password.
        val otherPassword = fixtureBytes("kdbx4-aes-aeskdf").also { bytes ->
            val hmacOffset = headerEnd(bytes) - 32
            bytes[hmacOffset] = (bytes[hmacOffset].toInt() xor 1).toByte()
        }

        f.store.change(otherPassword)
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(SessionState.Locked(LockReason.PasswordChanged), f.session.state.value)
    }

    @Test
    fun conflictCopiesAreShownAndFollowed() = runTest {
        val copy = ConflictCopy("test.sync-conflict-20261018-143005-ABCDEF2.kdbx", LocalDateTime.of(2026, 10, 18, 14, 30, 5), "ABCDEF2")
        val f = fixture()
        f.store.copies = listOf(copy)
        f.session.unlock(Secret.of("pw"))
        runCurrent()
        assertEquals(listOf(copy), f.unlockedState.conflictCopies)
        val vault = f.vault

        f.store.copies = emptyList()
        f.store.notifyWatchers()
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(emptyList(), f.unlockedState.conflictCopies)
        assertSame(vault, f.vault, "only the copies changed: no reload")
    }

    // Coming back (the lock timeout is the coordinator's, VaultSessionsTest)

    @Test
    fun comingBackChecksTheFileForANewVersion() = runTest {
        val f = unlocked()
        val before = f.vault
        f.store.change(fixtureBytes("kdbx4-aes-argon2d"), notify = false) // the watcher missed it

        f.session.checkFile()
        runCurrent()

        assertNotSame(before, f.vault)
    }

    // Saving

    @Test
    fun aSaveWritesTheFileAndTheVaultBecomesTheFileReadBack() = runTest {
        val f = unlocked()
        val entry = f.vault.entries.first()

        val outcome = assertNotNull(f.session.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "saved"))))
        runCurrent()

        assertEquals(SaveOutcome.Saved(), outcome.await())
        assertEquals("saved", f.vault.entries.single { it.id == entry.id }.notes)
        assertEquals(1, f.store.replaced)
        assertEquals(1, f.backups.size)
        val reads = f.store.reads
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(reads, f.store.reads, "its own write is not read again")
        assertNull(f.unlockedState.keyDerivation, "nothing derived")
    }

    // History

    @Test
    fun theHistoryIsReadFromTheFileWithTheKeyInMemoryOldestFirst() = runTest {
        val f = unlocked()
        val entry = f.vault.entries.first { !it.password.isEmpty() }
        for (notes in listOf("first", "second")) {
            val current = f.vault.entries.single { it.id == entry.id }
            assertNotNull(f.session.save(VaultEdit.Update(entry.id, current.lastModified, EntryChanges(notes = notes))))
            runCurrent()
        }

        val read = async { f.session.history(entry.id) }
        runCurrent()

        val versions = assertIs<HistoryResult.Read>(read.await()).versions
        assertEquals("second", versions.current.notes)
        val saved = versions.history.takeLast(2)
        assertEquals(listOf(entry.notes, "first"), saved.map { it.notes }, "after the versions the fixture has")
        assertEquals(entry.password.reveal(), saved[0].password.reveal(), "its secrets, read")
        assertEquals(entry.groupPath, saved[0].groupPath)
        assertNull(f.unlockedState.keyDerivation, "nothing derived")
        val other = f.vault.entries.first { it.id != entry.id }
        val others = assertIs<HistoryResult.Read>(async { f.session.history(other.id) }.also { runCurrent() }.await()).versions
        assertEquals(other.title, others.current.title)
        assertTrue(others.history.all { it.id == other.id && it.notes != "first" }, "only that entry's own versions")
        assertNull(assertIs<HistoryResult.Failed>(async { f.session.history("gone") }.also { runCurrent() }.await()).failure)
    }

    @Test
    fun aLockWhileTheHistoryIsReadWipesItAndNothingIsReturned() = runTest {
        val f = unlocked()
        val entry = f.vault.entries.first()
        // Started: the file is being read when the lock comes.
        val read = async(start = CoroutineStart.UNDISPATCHED) { f.session.history(entry.id) }
        f.session.lock()
        runCurrent()

        assertNull(read.await())
        assertNull(f.session.history(entry.id), "locked")
    }

    @Test
    fun aRefusedSaveKeepsTheVaultReadsTheFileAgainAndSaysWhy() = runTest {
        val f = unlocked()
        val entry = f.vault.entries.first()
        val reads = f.store.reads

        val outcome = assertNotNull(f.session.save(VaultEdit.Update(entry.id, Instant.EPOCH, EntryChanges(notes = "x"))))
        runCurrent()

        assertEquals(SaveOutcome.Failed(SaveFailure.ChangedElsewhere), outcome.await())
        assertEquals(entry, f.vault.entries.single { it.id == entry.id })
        assertEquals(0, f.store.replaced)
        assertTrue(f.store.reads > reads + 1, "the vault is read again after the save's own read")
    }

    @Test
    fun aGroupChangeIsSavedAndASaveSaysWhichEmptyGroupsItDeleted() = runTest {
        val f = unlocked()
        val top = f.vault.groups.single { it.isRoot }
        val web = f.vault.groups.single { it.name == "Web & Mail" }
        val made = NewGroup(Entry.newId(), "Made")

        val created = assertNotNull(f.session.save(VaultEdit.CreateGroup(top.id, made)))
        runCurrent()
        assertEquals(SaveOutcome.Saved(), created.await())
        assertTrue(VaultGroup(made.id, "Made", listOf("Made")) in f.vault.groups)

        // Both entries of Web & Mail into Made: the second leaves it empty.
        val (first, second) = f.vault.entries.filter { it.groupId == web.id }
        for ((entry, deleted) in listOf(first to emptyList(), second to listOf(web))) {
            val moved = assertNotNull(f.session.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(group = GroupDestination(made.id)))))
            runCurrent()
            assertEquals(SaveOutcome.Saved(deleted), moved.await())
        }
        assertTrue(f.vault.groups.none { it.id == web.id })
        assertEquals(listOf(made.id, made.id), f.vault.entries.map { it.groupId })
    }

    @Test
    fun aGroupChangeRefusedBecauseTheFileChangedMeanwhileSaysWhyAndReadsTheFileAgain() = runTest {
        val f = unlocked()
        val top = f.vault.groups.single { it.isRoot }
        val web = f.vault.groups.single { it.name == "Web & Mail" }
        // Meanwhile another device makes "Mail" at the top level; the watcher has not noticed it yet.
        val elsewhere = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        VaultSaver(elsewhere) { _, _, _ -> }.save(VaultEdit.CreateGroup(top.id, NewGroup(Entry.newId(), "Mail")), DatabaseKey.of(Secret.of("pw")), Instant.now())
        f.store.change(elsewhere.content, notify = false)
        assertTrue(f.vault.groups.none { it.name == "Mail" })

        for ((edit, failure) in listOf(
            VaultEdit.CreateGroup(top.id, NewGroup(Entry.newId(), "mail")) to SaveFailure.GroupNameTaken,
            VaultEdit.DeleteEmptyGroup(web.id) to SaveFailure.GroupNotEmpty,
            VaultEdit.RenameGroup(top.id, "Top") to SaveFailure.GroupNotChangeable,
            VaultEdit.RenameGroup("gone", "x") to SaveFailure.GroupNotFound,
        )) {
            val reads = f.store.reads
            val outcome = assertNotNull(f.session.save(edit))
            runCurrent()
            assertEquals(SaveOutcome.Failed(failure), outcome.await())
            assertTrue(f.store.reads > reads + 1, "$failure: the vault is read again after the save's own read")
        }
        assertTrue(f.vault.groups.any { it.name == "Mail" }, "the vault is the file as it is now")
        assertEquals(0, f.store.replaced)
    }

    @Test
    fun oneSaveAtATimeAndNoneWhileLocked() = runTest {
        val f = unlocked()
        val entry = f.vault.entries.first()
        val edit = VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "x"))

        assertNotNull(f.session.save(edit))
        assertNull(f.session.save(edit), "a second save waits for no one")
        runCurrent()
        assertNotNull(f.session.save(VaultEdit.Update(entry.id, null, EntryChanges(notes = "y"))), "the next one, after")
        runCurrent()
        f.session.lock()
        assertNull(f.session.save(edit))
    }

    @Test
    fun aLockDuringASaveWipesWhatItReadBack() = runTest {
        val f = unlocked()
        val entry = f.vault.entries.first()

        val outcome = assertNotNull(f.session.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "x"))))
        f.session.lock()
        runCurrent()

        assertEquals(SaveOutcome.Interrupted, outcome.await())
        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value)
    }

    @Test
    fun aPasswordChangedElsewhereLocksOnSave() = runTest {
        val f = unlocked()
        val entry = f.vault.entries.first()
        val pw = DatabaseKey.of(Secret.of("pw"))
        f.store.change(
            KdbxReader.openForEditing(fixtureBytes("kdbx4-aes-aeskdf"), pw).write(DatabaseKey.of(Secret.of("other"))).file,
            notify = false,
        )

        val outcome = assertNotNull(f.session.save(VaultEdit.Update(entry.id, null, EntryChanges(notes = "x"))))
        runCurrent()

        assertEquals(SaveOutcome.Failed(SaveFailure.CannotOpen(OpenFailure.WrongPassword)), outcome.await())
        assertEquals(SessionState.Locked(LockReason.PasswordChanged), f.session.state.value)
    }

    /** Where the header ends: after its fields, the SHA-256 and the HMAC (each 32 bytes). */
    private fun headerEnd(file: ByteArray): Int {
        var position = 12
        while (true) {
            val id = file[position].toInt()
            val size = (file[position + 1].toInt() and 0xFF) or ((file[position + 2].toInt() and 0xFF) shl 8) or
                ((file[position + 3].toInt() and 0xFF) shl 16) or ((file[position + 4].toInt() and 0xFF) shl 24)
            position += 5 + size
            if (id == 0) return position + 64
        }
    }
}
