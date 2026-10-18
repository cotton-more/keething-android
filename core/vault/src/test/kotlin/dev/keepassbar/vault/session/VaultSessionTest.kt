package dev.keepassbar.vault.session

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.storage.ConflictCopy
import dev.keepassbar.vault.opening.OpenFailure
import dev.keepassbar.vault.opening.VaultOpener
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class VaultSessionTest {
    private class Fixture(scope: TestScope, val store: FakeStore) {
        val clock = FakeClock(scope.testScheduler)
        val session = VaultSession(
            store = store,
            opener = VaultOpener(),
            clock = clock,
            scope = scope.backgroundScope,
            io = StandardTestDispatcher(scope.testScheduler),
            lockTimeout = { 5.minutes },
        )
    }

    private fun TestScope.fixture(name: String = "kdbx4-aes-aeskdf") = Fixture(this, FakeStore(fixtureBytes(name)))

    private fun TestScope.unlocked(name: String = "kdbx4-aes-aeskdf"): Fixture = fixture(name).also {
        assertTrue(it.session.unlock(Secret.of("pw")))
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

        assertFalse(f.session.unlock(Secret.of("pw")))
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
        assertEquals(0, f.store.watching)
        f.store.change(fixtureBytes("kdbx4-aes-argon2d"))
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value, "no reload without the key")
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

    // Auto-lock (SPEC §4)

    @Test
    fun locksAfterTheTimeoutInTheBackground() = runTest {
        val f = unlocked()

        f.session.movedToBackground()
        advanceTimeBy(4.minutes)
        runCurrent()
        assertIs<SessionState.Unlocked>(f.session.state.value)
        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(SessionState.Locked(LockReason.Timeout), f.session.state.value)
    }

    @Test
    fun comingBackInTimeKeepsItUnlocked() = runTest {
        val f = unlocked()

        f.session.movedToBackground()
        advanceTimeBy(4.minutes)
        f.session.movedToForeground()
        advanceTimeBy(10.minutes)
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
    }

    @Test
    fun comingBackAfterDeepSleepLocksAlthoughTheTimerDidNotRun() = runTest {
        val f = unlocked()

        f.session.movedToBackground()
        f.clock.sleep(6.minutes)
        f.session.movedToForeground()

        assertEquals(SessionState.Locked(LockReason.Timeout), f.session.state.value)
    }

    @Test
    fun comingBackChecksTheFileForANewVersion() = runTest {
        val f = unlocked()
        val before = f.vault
        f.session.movedToBackground()
        f.store.change(fixtureBytes("kdbx4-aes-argon2d"), notify = false) // the watcher missed it

        f.session.movedToForeground()
        runCurrent()

        assertNotSame(before, f.vault)
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
