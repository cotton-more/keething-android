package dev.keepassbar.vault.biometric

import dev.keepassbar.domain.biometric.BiometricAvailability
import dev.keepassbar.domain.biometric.BiometricPasswordStore
import dev.keepassbar.domain.biometric.StoredPasswordOpening
import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.settings.SettingsStore
import dev.keepassbar.vault.opening.OpenFailure
import dev.keepassbar.vault.opening.VaultOpener
import dev.keepassbar.vault.session.FakeClock
import dev.keepassbar.vault.session.FakeStore
import dev.keepassbar.vault.session.LockReason
import dev.keepassbar.vault.session.SessionState
import dev.keepassbar.vault.session.VaultSession
import dev.keepassbar.vault.session.fixtureBytes
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The Mac's `TouchIDTests`, for the Keystore in place of the Secure Enclave (SPEC §5). */
class BiometricUnlockTest {
    private class Fixture(scope: TestScope, database: ByteArray?, val settings: MapSettings, fileReads: CoroutineDispatcher) {
        val store = FakePasswordStore()
        val session = VaultSession(
            store = FakeStore(database),
            opener = VaultOpener(),
            backups = { _, _, _ -> },
            clock = FakeClock(scope.testScheduler),
            scope = scope.backgroundScope,
            io = fileReads,
            lockTimeout = { 5.minutes },
        )
        val biometric = BiometricUnlock(session, store, settings, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler))
    }

    /** The fixture database opens with "pw". The session reads the file on [fileReads], by default the test's scheduler. */
    private suspend fun TestScope.fixture(
        enabled: Boolean = true,
        database: ByteArray? = fixtureBytes("kdbx4-aes-aeskdf"),
        settings: MapSettings = MapSettings(),
        fileReads: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): Fixture = Fixture(this, database, settings, fileReads).also {
        if (enabled) it.biometric.setEnabled(true)
        it.biometric.refresh()
    }

    private suspend fun TestScope.storedPassword(text: String, enabled: Boolean = true, database: ByteArray? = fixtureBytes("kdbx4-aes-aeskdf")) =
        fixture(enabled, database).also {
            it.store.stored = Secret.of(text)
            it.biometric.refresh()
        }

    private fun Fixture.typed(text: String): Boolean {
        val typed = Secret.of(text)
        val started = biometric.unlock(typed)
        typed.wipe() // as the lock screen does at once: the rules keep their own copy
        return started
    }

    // The switch

    @Test
    fun itIsOffByDefaultAndTheSwitchIsRemembered() = runTest {
        val settings = MapSettings()
        val f = fixture(enabled = false, settings = settings)
        assertFalse(f.biometric.state.value.enabled)

        f.biometric.setEnabled(true)

        assertTrue(f.biometric.state.value.enabled)
        assertTrue(fixture(enabled = false, settings = settings).biometric.state.value.enabled)
    }

    @Test
    fun turningItOffDeletesTheStoredPassword() = runTest {
        val f = storedPassword("pw")
        assertTrue(f.biometric.state.value.stored)

        assertNull(f.biometric.setEnabled(false))

        assertFalse(f.biometric.state.value.enabled)
        assertFalse(f.biometric.state.value.stored)
        assertNull(f.store.stored)
    }

    @Test
    fun aFailedDeleteIsReportedButTheSwitchStillGoesOff() = runTest {
        val f = storedPassword("pw")
        f.store.deleteError = IOException("disk on fire")

        assertEquals("disk on fire", f.biometric.setEnabled(false))

        assertFalse(f.biometric.state.value.enabled)
    }

    @Test
    fun theStateSaysWhetherItCanUnlockNow() = runTest {
        val f = fixture()
        assertFalse(f.biometric.state.value.canUnlock, "nothing stored yet")

        f.store.stored = Secret.of("pw")
        f.biometric.refresh()
        assertTrue(f.biometric.state.value.canUnlock)

        f.store.availability = BiometricAvailability.Unavailable("no fingerprint enrolled")
        f.biometric.refresh()
        assertFalse(f.biometric.state.value.canUnlock)
        assertEquals(BiometricAvailability.Unavailable("no fingerprint enrolled"), f.biometric.state.value.availability)

        f.store.availability = BiometricAvailability.Available
        f.biometric.setEnabled(false)
        assertFalse(f.biometric.state.value.canUnlock)
    }

    // Storing the password after a typed unlock

    @Test
    fun aTypedUnlockStoresThePassword() = runTest {
        val f = fixture()

        assertTrue(f.typed("pw"))
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(listOf("pw"), f.store.saves)
        assertTrue(f.biometric.state.value.stored)
    }

    @Test
    fun turnedOnOnTheLockScreenTheSameTypedUnlockStoresIt() = runTest {
        val f = fixture(enabled = false)

        f.biometric.setEnabled(true)
        f.typed("pw")
        runCurrent()

        assertEquals(listOf("pw"), f.store.saves)
    }

    @Test
    fun nothingIsStoredWhileTheSwitchIsOff() = runTest {
        val f = fixture(enabled = false)

        f.typed("pw")
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(emptyList(), f.store.saves)
    }

    @Test
    fun aWrongPasswordIsNeverStored() = runTest {
        val f = fixture()

        f.typed("nope")
        runCurrent()

        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongPassword)), f.session.state.value)
        assertEquals(emptyList(), f.store.saves)
    }

    @Test
    fun nothingIsStoredWithoutBiometrics() = runTest {
        val f = fixture()
        f.store.availability = BiometricAvailability.Unavailable("no fingerprint enrolled")

        f.typed("pw")
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(emptyList(), f.store.saves)
    }

    @Test
    fun aPasswordTypedWhileTheVaultIsOpenIsNotStored() = runTest {
        val f = fixture()
        f.typed("pw")
        runCurrent()

        assertFalse(f.typed("typo"))
        runCurrent()

        assertEquals(listOf("pw"), f.store.saves)
    }

    @Test
    fun aLockBeforeTheFileOpenedStoresNothing() = runTest {
        val f = fixture()

        f.typed("pw")
        f.session.lock()
        runCurrent()

        assertEquals(emptyList(), f.store.saves)
    }

    @Test
    fun typingTheNewPasswordAfterADatabasePasswordChangeReplacesTheStoredOne() = runTest {
        val f = storedPassword("old-pw")

        f.typed("pw")
        runCurrent()

        assertEquals("pw", f.store.stored?.reveal())
    }

    @Test
    fun aPasswordThatCouldNotBeStoredDoesNotUndoTheUnlockAndSaysWhy() = runTest {
        val f = fixture()
        f.store.saveError = IOException("no space left")

        f.typed("pw")
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertFalse(f.biometric.state.value.stored)
        assertEquals("no space left", f.biometric.state.value.setupFailure)

        f.session.lock()
        f.store.saveError = null
        f.typed("pw")
        runCurrent()
        assertNull(f.biometric.state.value.setupFailure, "the next success clears it")
        assertTrue(f.biometric.state.value.stored)
    }

    // Unlocking with the fingerprint

    @Test
    fun aFingerprintUnlocksWithTheStoredPassword() = runTest {
        val f = storedPassword("pw")

        assertEquals(FingerprintOutcome.Unlocked, f.biometric.unlockWithFingerprint())

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(1, f.store.opens)
        assertEquals("pw", f.store.stored?.reveal(), "the stored password stays for next time")
    }

    @Test
    fun theOpenedPasswordIsWipedOnceUsed() = runTest {
        val f = storedPassword("pw")

        f.biometric.unlockWithFingerprint()

        assertTrue(f.store.handedOut.single().isWiped)
    }

    @Test
    fun withTheSwitchOffItDoesNotAskForAFingerprint() = runTest {
        val f = storedPassword("pw", enabled = false)

        assertIs<FingerprintOutcome.NotAvailable>(f.biometric.unlockWithFingerprint())

        assertEquals(0, f.store.opens)
        assertEquals(SessionState.Locked(LockReason.Initial), f.session.state.value)
    }

    @Test
    fun withoutBiometricsItIsUnavailableAndSaysWhy() = runTest {
        val f = storedPassword("pw")
        f.store.availability = BiometricAvailability.Unavailable("no fingerprint enrolled")

        assertEquals(FingerprintOutcome.NotAvailable("no fingerprint enrolled"), f.biometric.unlockWithFingerprint())

        assertEquals(0, f.store.opens)
    }

    @Test
    fun nothingStoredMeansNoPrompt() = runTest {
        val f = fixture()

        assertEquals(FingerprintOutcome.NothingStored, f.biometric.unlockWithFingerprint())

        assertEquals(0, f.store.opens)
    }

    @Test
    fun aCancelledOrFailedFingerprintKeepsEverythingAsItWas() = runTest {
        val f = storedPassword("pw")

        f.store.prompt = StoredPasswordOpening.Cancelled
        assertEquals(FingerprintOutcome.Cancelled, f.biometric.unlockWithFingerprint())
        f.store.prompt = StoredPasswordOpening.Failed("Too many attempts")
        assertEquals(FingerprintOutcome.AuthenticationFailed("Too many attempts"), f.biometric.unlockWithFingerprint())

        assertEquals(SessionState.Locked(LockReason.Initial), f.session.state.value)
        assertEquals("pw", f.store.stored?.reveal())
        assertEquals(0, f.store.deletes)
    }

    @Test
    fun anInvalidatedKeyDeletesTheStoredPassword() = runTest {
        val f = storedPassword("pw")
        f.store.prompt = StoredPasswordOpening.KeyInvalidated

        assertEquals(FingerprintOutcome.KeyInvalidated, f.biometric.unlockWithFingerprint())

        assertNull(f.store.stored)
        assertFalse(f.biometric.state.value.stored)
        assertTrue(f.biometric.state.value.enabled, "the next typed unlock sets it up again")
        assertEquals(SessionState.Locked(LockReason.Initial), f.session.state.value)
    }

    @Test
    fun aStoredPasswordTheDatabaseRejectsIsDeleted() = runTest {
        val f = storedPassword("old-pw")

        assertEquals(FingerprintOutcome.StoredPasswordRejected, f.biometric.unlockWithFingerprint())

        assertNull(f.store.stored)
        assertFalse(f.biometric.state.value.stored)
    }

    @Test
    fun otherUnlockFailuresKeepTheStoredPassword() = runTest {
        val f = storedPassword("pw", database = null)

        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.Missing), f.biometric.unlockWithFingerprint())

        assertEquals("pw", f.store.stored?.reveal())
    }

    @Test
    fun aStoredCopyThatCannotBeReadIsReportedAndKept() = runTest {
        val f = storedPassword("pw")
        f.store.openError = IOException("read failed")

        assertEquals(FingerprintOutcome.StorageError("read failed"), f.biometric.unlockWithFingerprint())

        assertEquals("pw", f.store.stored?.reveal())
    }

    @Test
    fun oneFingerprintPromptAtATime() = runTest {
        val f = storedPassword("pw")
        val finger = CompletableDeferred<Unit>()
        f.store.gate = finger

        val first = async { f.biometric.unlockWithFingerprint() }
        runCurrent()
        assertTrue(f.biometric.state.value.prompting)
        assertEquals(FingerprintOutcome.InProgress, f.biometric.unlockWithFingerprint())
        finger.complete(Unit)

        assertEquals(FingerprintOutcome.Unlocked, first.await())
        assertEquals(1, f.store.opens)
        assertFalse(f.biometric.state.value.prompting)
    }

    @Test
    fun noPromptWhileAnotherUnlockRunsOrTheVaultIsOpen() = runTest {
        val f = storedPassword("pw")
        f.typed("pw")

        assertEquals(FingerprintOutcome.InProgress, f.biometric.unlockWithFingerprint(), "a typed unlock is running")
        runCurrent()
        assertEquals(FingerprintOutcome.InProgress, f.biometric.unlockWithFingerprint(), "the vault is open")

        assertEquals(0, f.store.opens)
    }

    @Test
    fun aLockWhileTheStoredPasswordOpensTheFileEndsQuietly() = runTest {
        val fileReads = HeldDispatcher()
        val f = fixture(fileReads = fileReads)
        f.store.stored = Secret.of("pw")
        val result = async { f.biometric.unlockWithFingerprint() }
        runCurrent()
        assertEquals(SessionState.Unlocking, f.session.state.value, "the file is being read")

        f.session.lock()
        repeat(3) {
            fileReads.runAll()
            runCurrent()
        }

        assertEquals(FingerprintOutcome.Cancelled, result.await())
        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value)
        assertEquals("pw", f.store.stored?.reveal())
    }

    // Another database

    @Test
    fun forgettingDeletesTheStoredPasswordButKeepsTheSwitch() = runTest {
        val f = storedPassword("pw")

        f.biometric.forget()

        assertNull(f.store.stored)
        assertFalse(f.biometric.state.value.stored)
        assertTrue(f.biometric.state.value.enabled)
    }
}

/** Runs what is sent to it only when asked: a file read held back. */
private class HeldDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks += block
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

internal class MapSettings : SettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun string(key: String): String? = values[key]

    override fun setString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/**
 * The Keystore in memory. Like the real one it keeps copies: what it was given may be wiped by the caller,
 * and what it hands out is the caller's to wipe. [prompt] is what the fingerprint prompt does (null: the
 * finger is recognised); with [gate] it waits for a finger until the gate completes.
 */
internal class FakePasswordStore : BiometricPasswordStore {
    var availability: BiometricAvailability = BiometricAvailability.Available
    var stored: Secret? = null
    var prompt: StoredPasswordOpening? = null
    var gate: CompletableDeferred<Unit>? = null
    var saveError: Exception? = null
    var deleteError: Exception? = null
    var openError: Exception? = null

    /** The text of every password saved. */
    val saves = mutableListOf<String>()
    val handedOut = mutableListOf<Secret>()
    var opens = 0
        private set
    var deletes = 0
        private set

    override fun availability(): BiometricAvailability = availability

    override fun hasPassword(): Boolean = stored != null

    override fun save(password: Secret) {
        saveError?.let { throw it }
        saves += password.reveal()
        stored?.wipe()
        stored = password.copy()
    }

    override suspend fun open(): StoredPasswordOpening {
        opens++
        gate?.await()
        openError?.let { throw it }
        prompt?.let { return it }
        val kept = stored ?: return StoredPasswordOpening.NothingStored
        return StoredPasswordOpening.Opened(kept.copy().also { handedOut += it })
    }

    override fun delete() {
        deletes++
        deleteError?.let { throw it }
        stored?.wipe()
        stored = null
    }
}
