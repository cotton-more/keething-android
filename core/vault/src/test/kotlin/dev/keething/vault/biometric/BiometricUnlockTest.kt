package dev.keething.vault.biometric

import dev.keething.vault.databases.add
import dev.keething.vault.databases.path
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.domain.biometric.BiometricPasswordStores
import dev.keething.domain.biometric.SeveralOpening
import dev.keething.domain.biometric.StoredPasswordOpening
import dev.keething.domain.secret.Secret
import dev.keething.domain.settings.SettingsStore
import dev.keething.domain.storage.KeyFileSource
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeClock
import dev.keething.vault.session.FakeKeyFile
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.UnlockOutcome
import dev.keething.vault.session.VaultSession
import dev.keething.vault.session.fixtureBytes
import dev.keething.vault.session.keyFileBytes
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The Mac's `TouchIDTests`, for the Keystore in place of the Secure Enclave (SPEC §5), one stored password per database. */
class BiometricUnlockTest {
    /** A database of the list with its session and its fingerprint unlock. */
    private class Database(val id: DatabaseId, val session: VaultSession, val biometric: BiometricUnlock)

    private class Fixture(
        private val scope: TestScope,
        database: ByteArray?,
        val settings: MapSettings,
        private val fileReads: CoroutineDispatcher,
        keyFile: KeyFileSource? = null,
    ) {
        val stores = FakePasswordStores()
        val clock = FakeClock(scope.testScheduler)
        private var ids = 0
        val list = DatabaseList(settings, makeId = { DatabaseId("db-${++ids}") })

        /** The files and key files that are not there, by path: no fingerprint is asked for them. */
        val missing = mutableSetOf<String>()
        val setup = BiometricSetup(stores, list, settings, clock, StandardTestDispatcher(scope.testScheduler))
        private val personal = add("/db/Personal.kdbx", database, keyFile)
        val id = personal.id
        val session = personal.session
        val biometric = personal.biometric

        /** Another database of the list, with its own file and session. */
        fun add(path: String, database: ByteArray? = fixtureBytes("kdbx4-aes-aeskdf"), keyFile: KeyFileSource? = null): Database {
            val record = list.add(path, useBiometric = true)
            if (keyFile != null) list.update(record.id) { it.copy(keyFile = FileLocation.ByPath("/keys/${keyFile.fileName}")) }
            val session = VaultSession(
                store = FakeStore(database),
                opener = VaultOpener(),
                backups = { _, _, _ -> },
                clock = clock,
                scope = scope.backgroundScope,
                io = fileReads,
                keyFile = { keyFile },
            )
            val biometric = BiometricUnlock(record.id, session, setup, list, scope.backgroundScope, scope.backgroundScope, fileExists = { (it as FileLocation.ByPath).path !in missing })
            return Database(record.id, session, biometric)
        }

        /** The stored password of [id] (by default this fixture's database). */
        fun stored(id: DatabaseId = this.id): Secret? = stores.stored[id.fileName]

        fun store(text: String, id: DatabaseId = this.id) {
            stores.stored[id.fileName] = Secret.of(text)
        }
    }

    /** The fixture database opens with "pw". The session reads the file on [fileReads], by default the test's scheduler. */
    private suspend fun TestScope.fixture(
        enabled: Boolean = true,
        database: ByteArray? = fixtureBytes("kdbx4-aes-aeskdf"),
        settings: MapSettings = MapSettings(),
        fileReads: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        keyFile: KeyFileSource? = null,
    ): Fixture = Fixture(this, database, settings, fileReads, keyFile).also {
        if (enabled) it.setup.setEnabled(true)
        it.biometric.refresh()
    }

    private suspend fun TestScope.storedPassword(text: String, enabled: Boolean = true, database: ByteArray? = fixtureBytes("kdbx4-aes-aeskdf")) =
        fixture(enabled, database).also {
            it.store(text)
            it.biometric.refresh()
        }

    private fun Fixture.typed(text: String, unlock: BiometricUnlock = biometric): Boolean {
        val typed = Secret.of(text)
        val started = unlock.unlock(typed)
        typed.wipe() // as the lock screen does at once: the rules keep their own copy
        return started != null
    }

    /** What the screens see once the switches and the list have been followed. */
    private fun TestScope.state(f: Fixture): BiometricState {
        runCurrent()
        return f.biometric.state.value
    }

    // The switches

    @Test
    fun itIsOffByDefaultAndTheSwitchIsRemembered() = runTest {
        val settings = MapSettings()
        val f = fixture(enabled = false, settings = settings)
        assertFalse(f.biometric.state.value.enabled)

        f.setup.setEnabled(true)

        assertTrue(state(f).enabled)
        assertTrue(fixture(enabled = false, settings = settings).biometric.state.value.enabled)
    }

    @Test
    fun turningItOffDeletesEveryStoredPassword() = runTest {
        val f = storedPassword("pw")
        val work = f.add("/db/Work.kdbx")
        f.store("work-pw", work.id)
        f.biometric.refresh()
        assertTrue(f.biometric.state.value.stored)

        assertNull(f.setup.setEnabled(false))

        assertFalse(state(f).enabled)
        assertFalse(f.biometric.state.value.stored)
        assertTrue(f.stores.stored.isEmpty())
        assertEquals(1, f.stores.deleteAlls)
    }

    @Test
    fun aFailedDeleteIsReportedButTheSwitchStillGoesOff() = runTest {
        val f = storedPassword("pw")
        f.stores.deleteError = IOException("disk on fire")

        assertEquals("disk on fire", f.setup.setEnabled(false))

        assertFalse(state(f).enabled)
    }

    @Test
    fun aLeftoverOfAFailedDeleteGoesBeforeTheAppsSwitchGoesOnOrTheSwitchStaysOff() = runTest {
        val f = fixture(enabled = false)
        f.store("left by a failed delete")

        f.stores.deleteError = IOException("Keystore busy")
        assertEquals("Keystore busy", f.setup.setEnabled(true))
        assertFalse(f.setup.state.value.enabled, "it would open with nothing typed since")

        f.stores.deleteError = null
        assertNull(f.setup.setEnabled(true))
        assertTrue(f.setup.state.value.enabled)
        assertNull(f.stored())
        assertFalse(f.biometric.refresh().canUnlock)
    }

    @Test
    fun aLeftoverOfAFailedDeleteGoesBeforeADatabasesOwnSwitchGoesOnOrItStaysOff() = runTest {
        val f = fixture()
        f.list.update(f.id) { it.copy(useBiometric = false) }
        f.store("left by a failed delete")

        f.stores.deleteError = IOException("Keystore busy")
        assertEquals("Keystore busy", f.biometric.useNextTime(true))
        assertFalse(f.list.record(f.id)!!.useBiometric)

        f.stores.deleteError = null
        assertNull(f.biometric.useNextTime(true))
        assertNull(f.stored())
        assertFalse(f.biometric.state.value.canUnlock, "set up again by the next typed unlock")
    }

    @Test
    fun aSaveThatFailsForADatabaseTurnedOffMeanwhileLeavesNoFailureToSay() = runTest {
        val f = fixture()
        f.stores.saveError = IOException("no space left")
        f.setup.setUseBiometric(f.id, false)

        assertEquals("no space left", f.setup.save(f.id, Secret.of("pw")))

        assertTrue(f.setup.state.value.setupFailures.isEmpty())
    }

    @Test
    fun theStateSaysWhetherItCanUnlockNow() = runTest {
        val f = fixture()
        assertFalse(f.biometric.state.value.canUnlock, "nothing stored yet")

        f.store("pw")
        f.biometric.refresh()
        assertTrue(f.biometric.state.value.canUnlock)

        f.stores.availability = BiometricAvailability.Unavailable("no fingerprint enrolled")
        f.biometric.refresh()
        assertFalse(f.biometric.state.value.canUnlock)
        assertEquals(BiometricAvailability.Unavailable("no fingerprint enrolled"), f.biometric.state.value.availability)

        f.stores.availability = BiometricAvailability.Available
        f.biometric.refresh()
        f.list.update(f.id) { it.copy(useBiometric = false) }
        assertFalse(state(f).canUnlock, "its own switch off")
        f.list.update(f.id) { it.copy(useBiometric = true) }
        assertTrue(state(f).canUnlock)
        f.setup.setEnabled(false)
        assertFalse(state(f).canUnlock, "the app's switch off")
    }

    // Storing the password after a typed unlock

    @Test
    fun aTypedUnlockStoresThePassword() = runTest {
        val f = fixture()

        assertTrue(f.typed("pw"))
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(listOf("pw"), f.stores.saves)
        assertEquals(listOf(f.id.fileName), f.stores.savedFor)
        assertTrue(f.biometric.state.value.stored)
    }

    @Test
    fun turnedOnOnTheLockScreenTheSameTypedUnlockStoresIt() = runTest {
        val f = fixture()
        f.list.update(f.id) { it.copy(useBiometric = false) }

        assertNull(f.biometric.useNextTime(true))
        assertTrue(f.biometric.state.value.enabled)
        f.typed("pw")
        runCurrent()

        assertEquals(listOf("pw"), f.stores.saves)
    }

    @Test
    fun theAppsSwitchAllowsADatabasesOwnAndTurnsNoneOnItself() = runTest {
        val f = fixture(enabled = false)
        f.list.update(f.id) { it.copy(useBiometric = false) }

        f.biometric.useNextTime(true)
        assertFalse(f.setup.state.value.enabled, "a database's switch never turns the app's on")
        f.typed("pw")
        runCurrent()
        assertTrue(f.stores.saves.isEmpty(), "the app's switch off: nothing is stored")

        f.session.lock()
        f.setup.setEnabled(true)
        val work = f.add("/db/Work.kdbx")
        f.list.update(work.id) { it.copy(useBiometric = false) } // as a database opened into the list starts
        f.typed("pw", work.biometric)
        f.typed("pw")
        runCurrent()
        assertEquals(listOf(f.id.fileName), f.stores.savedFor, "only the one it was turned on for")
    }

    @Test
    fun nothingIsStoredWhileTheSwitchIsOff() = runTest {
        val f = fixture(enabled = false)

        f.typed("pw")
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(emptyList(), f.stores.saves)
    }

    @Test
    fun aWrongPasswordIsNeverStored() = runTest {
        val f = fixture()

        f.typed("nope")
        runCurrent()

        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongPassword)), f.session.state.value)
        assertEquals(emptyList(), f.stores.saves)
    }

    @Test
    fun nothingIsStoredWithoutBiometrics() = runTest {
        val f = fixture()
        f.stores.availability = BiometricAvailability.Unavailable("no fingerprint enrolled")

        f.typed("pw")
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(emptyList(), f.stores.saves)
    }

    @Test
    fun aPasswordTypedWhileTheVaultIsOpenIsNotStored() = runTest {
        val f = fixture()
        f.typed("pw")
        runCurrent()

        assertFalse(f.typed("typo"))
        runCurrent()

        assertEquals(listOf("pw"), f.stores.saves)
    }

    @Test
    fun aLockBeforeTheFileOpenedStoresNothing() = runTest {
        val f = fixture()

        f.typed("pw")
        f.session.lock()
        runCurrent()

        assertEquals(emptyList(), f.stores.saves)
    }

    @Test
    fun typingTheNewPasswordAfterADatabasePasswordChangeReplacesTheStoredOne() = runTest {
        val f = storedPassword("old-pw")

        f.typed("pw")
        runCurrent()

        assertEquals("pw", f.stored()?.reveal())
    }

    @Test
    fun aPasswordThatCouldNotBeStoredDoesNotUndoTheUnlockAndSaysWhy() = runTest {
        val f = fixture()
        f.stores.saveError = IOException("no space left")

        f.typed("pw")
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertFalse(f.biometric.state.value.stored)
        assertEquals("no space left", f.biometric.state.value.setupFailure)
        assertEquals(mapOf(f.id to "no space left"), f.setup.state.value.setupFailures, "its page in Settings says it")

        f.session.lock()
        f.stores.saveError = null
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
        assertEquals(listOf(f.id.fileName), f.stores.opened)
        assertEquals("pw", f.stored()?.reveal(), "the stored password stays for next time")
    }

    @Test
    fun theOpenedPasswordIsWipedOnceUsed() = runTest {
        val f = storedPassword("pw")

        f.biometric.unlockWithFingerprint()

        assertTrue(f.stores.handedOut.single().isWiped)
    }

    @Test
    fun withTheSwitchOffItDoesNotAskForAFingerprint() = runTest {
        val f = storedPassword("pw", enabled = false)

        assertIs<FingerprintOutcome.NotAvailable>(f.biometric.unlockWithFingerprint())

        assertEquals(0, f.stores.prompts)
        assertEquals(SessionState.Locked(LockReason.Initial), f.session.state.value)
    }

    @Test
    fun withoutBiometricsItIsUnavailableAndSaysWhy() = runTest {
        val f = storedPassword("pw")
        f.stores.availability = BiometricAvailability.Unavailable("no fingerprint enrolled")

        assertEquals(FingerprintOutcome.NotAvailable("no fingerprint enrolled"), f.biometric.unlockWithFingerprint())

        assertEquals(0, f.stores.prompts)
    }

    @Test
    fun nothingStoredMeansNoPrompt() = runTest {
        val f = fixture()

        assertEquals(FingerprintOutcome.NothingStored, f.biometric.unlockWithFingerprint())

        assertEquals(0, f.stores.prompts)
    }

    @Test
    fun aCancelledOrFailedFingerprintKeepsEverythingAsItWas() = runTest {
        val f = storedPassword("pw")

        f.stores.prompt = StoredPasswordOpening.Cancelled
        assertEquals(FingerprintOutcome.Cancelled, f.biometric.unlockWithFingerprint())
        f.stores.prompt = StoredPasswordOpening.Failed("Too many attempts")
        assertEquals(FingerprintOutcome.AuthenticationFailed("Too many attempts"), f.biometric.unlockWithFingerprint())

        assertEquals(SessionState.Locked(LockReason.Initial), f.session.state.value)
        assertEquals("pw", f.stored()?.reveal())
        assertEquals(0, f.stores.deletes + f.stores.deleteAlls)
    }

    @Test
    fun changedFingerprintsDeleteEveryDatabasesStoredPassword() = runTest {
        val f = storedPassword("pw")
        val work = f.add("/db/Work.kdbx")
        f.store("work-pw", work.id)
        f.stores.prompt = StoredPasswordOpening.KeyInvalidated

        assertEquals(FingerprintOutcome.KeyInvalidated, f.biometric.unlockWithFingerprint())

        assertTrue(f.stores.stored.isEmpty(), "they all depended on those fingerprints")
        assertEquals(1, f.stores.deleteAlls)
        assertFalse(state(f).stored)
        assertTrue(f.biometric.state.value.enabled, "the next typed unlock sets it up again")
        assertEquals(SessionState.Locked(LockReason.Initial), f.session.state.value)
    }

    @Test
    fun oneStoredCopyThatNoLongerOpensDeletesThatOneTheOthersStay() = runTest {
        val f = storedPassword("pw")
        val work = f.add("/db/Work.kdbx")
        f.store("work-pw", work.id)
        f.stores.prompt = StoredPasswordOpening.CopyInvalidated

        assertEquals(FingerprintOutcome.KeyInvalidated, f.biometric.unlockWithFingerprint(), "said as the fingerprints changed")

        assertNull(f.stored())
        assertEquals("work-pw", f.stored(work.id)?.reveal())
        assertEquals(0, f.stores.deleteAlls)
    }

    @Test
    fun aStoredPasswordTheDatabaseRejectsIsDeleted() = runTest {
        val f = storedPassword("old-pw")

        assertEquals(FingerprintOutcome.StoredPasswordRejected, f.biometric.unlockWithFingerprint())

        assertNull(f.stored())
        assertFalse(state(f).stored)
    }

    // spec: databases.not-open/AC7
    @Test
    fun whyAStoredPasswordWentIsKeptPerDatabaseUntilOneIsStoredAgain() = runTest {
        val f = storedPassword("pw")
        val work = f.add("/db/Work.kdbx")
        val club = f.add("/db/Club.kdbx")
        f.store("work-pw", work.id)
        work.biometric.refresh()
        assertNull(state(f).setUpAgain)

        f.stores.prompt = StoredPasswordOpening.KeyInvalidated
        f.biometric.unlockWithFingerprint()
        runCurrent()
        assertEquals(SetUpAgain.FINGERPRINTS_CHANGED, f.biometric.state.value.setUpAgain)
        assertEquals(SetUpAgain.FINGERPRINTS_CHANGED, work.biometric.state.value.setUpAgain, "every stored one went")
        assertNull(club.biometric.state.value.setUpAgain, "nothing was stored for it: nothing to set up again")

        // A typed unlock that stores the password again: set up.
        f.stores.prompt = null
        assertTrue(f.typed("pw"))
        runCurrent()
        assertTrue(state(f).stored)
        assertNull(f.biometric.state.value.setUpAgain)
        assertEquals(SetUpAgain.FINGERPRINTS_CHANGED, work.biometric.state.value.setUpAgain, "the other one still")

        // Its own switch off: nothing is to be set up for it any more.
        f.setup.setUseBiometric(work.id, false)
        runCurrent()
        assertNull(work.biometric.state.value.setUpAgain)
        f.setup.setUpAgain(setOf(club.id), SetUpAgain.FINGERPRINTS_CHANGED)
        f.setup.forget(club.id)
        runCurrent()
        assertNull(club.biometric.state.value.setUpAgain, "forgotten with the database")
        f.setup.setUpAgain(setOf(work.id), SetUpAgain.FINGERPRINTS_CHANGED)

        // The switch off and on: nothing is said any more (its stored passwords went with the switch).
        f.setup.setEnabled(false)
        f.setup.setEnabled(true)
        runCurrent()
        assertNull(work.biometric.state.value.setUpAgain)
    }

    @Test
    fun aStoredPasswordTheDatabaseRejectedIsSaidUntilOneIsStoredAgain() = runTest {
        val f = storedPassword("old-pw")
        assertEquals(FingerprintOutcome.StoredPasswordRejected, f.biometric.unlockWithFingerprint())
        assertEquals(SetUpAgain.PASSWORD_REJECTED, state(f).setUpAgain)
        f.session.lock(LockReason.Manual)
        assertTrue(f.typed("pw"))
        runCurrent()
        assertNull(state(f).setUpAgain)
    }

    @Test
    fun aStoredPasswordRejectedWithAKeyFileIsKeptTheKeyFileMayBeTheWrongPart() = runTest {
        val keyFile = FakeKeyFile(keyFileBytes("raw32.key"))
        val f = fixture(database = keyFileBytes("keyfile-keyx.kdbx"), keyFile = keyFile)
        f.store("pw")
        f.biometric.refresh()

        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.WrongPasswordOrKeyFile), f.biometric.unlockWithFingerprint())
        assertEquals("pw", f.stored()?.reveal())

        keyFile.content = null
        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.KeyFileMissing("work.keyx")), f.biometric.unlockWithFingerprint())
        assertEquals("pw", f.stored()?.reveal(), "a missing key file keeps it too")

        keyFile.content = keyFileBytes("keyx.keyx")
        assertEquals(FingerprintOutcome.Unlocked, f.biometric.unlockWithFingerprint())
    }

    @Test
    fun aStoredPasswordRejectedAfterTheKeyFileChangedIsKeptUntilSomethingOpensTheDatabase() = runTest {
        // The database needs keyx.keyx and its key file was just forgotten in Settings: the stored password is still right.
        val f = fixture(database = keyFileBytes("keyfile-keyx.kdbx"))
        f.store("pw")
        f.biometric.refresh()
        f.setup.keyFileChanged(f.id)

        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.WrongPassword), f.biometric.unlockWithFingerprint())
        assertEquals("pw", f.stored()?.reveal(), "its key file changed since: that may be what is wrong")
        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.WrongPassword), f.biometric.unlockWithFingerprint())
        assertEquals("pw", f.stored()?.reveal(), "until something opens it")
    }

    @Test
    fun aKeyFileChangeIsNotedPerDatabase() = runTest {
        val f = storedPassword("old")
        val work = f.add("/db/Work.kdbx")
        f.store("old", work.id)
        f.setup.keyFileChanged(work.id)

        assertEquals(FingerprintOutcome.StoredPasswordRejected, f.biometric.unlockWithFingerprint(), "Personal's key file did not change")
        assertNull(f.stored())
        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.WrongPassword), work.biometric.unlockWithFingerprint())
        assertEquals("old", f.stored(work.id)?.reveal())
    }

    @Test
    fun aFingerprintUnlockConfirmsAKeyFileChange() = runTest {
        val f = storedPassword("pw")
        f.setup.keyFileChanged(f.id)
        assertEquals(FingerprintOutcome.Unlocked, f.biometric.unlockWithFingerprint())
        f.session.lock()
        f.store("old")

        assertEquals(FingerprintOutcome.StoredPasswordRejected, f.biometric.unlockWithFingerprint())
        assertNull(f.stored())
    }

    @Test
    fun aTypedUnlockConfirmsAKeyFileChangeAndTurningItOffForgetsOne() = runTest {
        val f = storedPassword("old")
        f.setup.keyFileChanged(f.id)
        assertTrue(f.typed("pw"))
        runCurrent()
        f.session.lock()
        f.store("old")
        assertEquals(FingerprintOutcome.StoredPasswordRejected, f.biometric.unlockWithFingerprint(), "the typed unlock confirmed it")

        f.setup.keyFileChanged(f.id)
        f.setup.setEnabled(false)
        assertNull(f.settings.string(BiometricSetup.keyFileChangedKey(f.id)))
    }

    @Test
    fun nothingTypedWithAKeyFileOpensAndStoresNothing() = runTest {
        val f = fixture(database = keyFileBytes("keyfile-only.kdbx"), keyFile = FakeKeyFile(keyFileBytes("keyx.keyx")))

        val outcome = f.biometric.unlock(Secret.empty())
        runCurrent()

        assertEquals(UnlockOutcome.Opened, outcome?.await())
        runCurrent()
        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertTrue(f.stores.saves.isEmpty(), "there is no password to store")
        assertFalse(f.biometric.state.value.stored)
        assertNull(f.biometric.unlock(Secret.empty()), "not locked: nothing started")
    }

    @Test
    fun otherUnlockFailuresKeepTheStoredPassword() = runTest {
        val f = storedPassword("pw", database = null)

        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.Missing), f.biometric.unlockWithFingerprint())

        assertEquals("pw", f.stored()?.reveal())
    }

    @Test
    fun aStoredCopyThatCannotBeReadIsReportedAndKept() = runTest {
        val f = storedPassword("pw")
        f.stores.openError = IOException("read failed")

        assertEquals(FingerprintOutcome.StorageError("read failed"), f.biometric.unlockWithFingerprint())

        assertEquals("pw", f.stored()?.reveal())
    }

    @Test
    fun oneFingerprintPromptAtATime() = runTest {
        val f = storedPassword("pw")
        val finger = CompletableDeferred<Unit>()
        f.stores.gate = finger

        val first = async { f.biometric.unlockWithFingerprint() }
        runCurrent()
        assertTrue(f.biometric.state.value.prompting)
        assertEquals(FingerprintOutcome.InProgress, f.biometric.unlockWithFingerprint())
        finger.complete(Unit)

        assertEquals(FingerprintOutcome.Unlocked, first.await())
        assertEquals(1, f.stores.prompts)
        assertFalse(f.biometric.state.value.prompting)
    }

    @Test
    fun anAcceptedFingerprintSaysOpeningBeforeTheStoredPasswordIsOpen() = runTest {
        val f = storedPassword("pw")
        val finger = CompletableDeferred<Unit>()
        val decrypted = CompletableDeferred<Unit>()
        f.stores.gate = finger
        f.stores.decrypting = decrypted

        val unlock = async { f.biometric.unlockWithFingerprint() }
        runCurrent()
        assertFalse(f.biometric.state.value.opening, "the prompt is up: nothing opens yet")
        finger.complete(Unit)
        runCurrent()
        assertTrue(f.biometric.state.value.opening, "accepted: the lock screen says it is opening")
        assertIs<SessionState.Locked>(f.session.state.value, "while the stored password is still being opened")
        decrypted.complete(Unit)

        assertEquals(FingerprintOutcome.Unlocked, unlock.await())
        assertFalse(f.biometric.state.value.opening)
    }

    @Test
    fun aCancelledPromptNeverSaysOpening() = runTest {
        val f = storedPassword("pw")
        f.stores.prompt = StoredPasswordOpening.Cancelled
        val seen = mutableListOf<Boolean>()
        val watch = launch { f.biometric.state.collect { seen += it.opening } }
        runCurrent()

        assertEquals(FingerprintOutcome.Cancelled, f.biometric.unlockWithFingerprint())
        runCurrent()
        watch.cancel()
        assertFalse(true in seen)
    }

    @Test
    fun noPromptWhileAnotherUnlockRunsOrTheVaultIsOpen() = runTest {
        val f = storedPassword("pw")
        f.typed("pw")

        assertEquals(FingerprintOutcome.InProgress, f.biometric.unlockWithFingerprint(), "a typed unlock is running")
        runCurrent()
        assertEquals(FingerprintOutcome.InProgress, f.biometric.unlockWithFingerprint(), "the vault is open")

        assertEquals(0, f.stores.prompts)
    }

    @Test
    fun noPromptWhileTheFileOrItsKeyFileIsNotThere() = runTest {
        val f = fixture(database = keyFileBytes("keyfile-keyx.kdbx"), keyFile = FakeKeyFile(keyFileBytes("keyx.keyx")))
        f.store("pw")
        f.biometric.refresh()

        f.missing += "/keys/work.keyx"
        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.KeyFileMissing("work.keyx")), f.biometric.unlockWithFingerprint())
        f.missing += "/db/Personal.kdbx"
        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.Missing), f.biometric.unlockWithFingerprint())

        assertEquals(0, f.stores.prompts)
        assertEquals("pw", f.stored()?.reveal())
    }

    @Test
    fun aLockWhileTheStoredPasswordOpensTheFileEndsQuietly() = runTest {
        val fileReads = HeldDispatcher()
        val f = fixture(fileReads = fileReads)
        f.store("pw")
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
        assertEquals("pw", f.stored()?.reveal())
    }

    @Test
    fun aLockAfterTheFingerOpensNothingAndDeletesNothing() = runTest {
        // Sleep or Lock while the stored password is opened in StrongBox (keething-spec unlock.biometric-invalidation AC6).
        val f = storedPassword("old-pw")
        val decrypted = CompletableDeferred<Unit>()
        f.stores.decrypting = decrypted
        val result = async { f.biometric.unlockWithFingerprint() }
        runCurrent()
        assertTrue(f.biometric.state.value.opening, "the finger was accepted")

        f.session.lock()
        decrypted.complete(Unit)

        assertEquals(FingerprintOutcome.Cancelled, result.await())
        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value)
        assertEquals("old-pw", f.stored()?.reveal(), "never tried, so never rejected")
        assertTrue(f.stores.handedOut.single().isWiped)
    }

    @Test
    fun aLockWhileTheStoredPasswordIsReadDeletesNothingNotEvenACopyThatNoLongerOpens() = runTest {
        val f = storedPassword("pw")
        val finger = CompletableDeferred<Unit>()
        f.stores.gate = finger
        f.stores.prompt = StoredPasswordOpening.CopyInvalidated
        val result = async { f.biometric.unlockWithFingerprint() }
        runCurrent()

        f.session.lock()
        finger.complete(Unit)

        assertEquals(FingerprintOutcome.Cancelled, result.await())
        assertEquals("pw", f.stored()?.reveal(), "the next fingerprint finds it again")
        assertEquals(0, f.stores.deletes + f.stores.deleteAlls)
    }

    @Test
    fun aSessionClosedWhileThePromptIsUpOpensNothingAndDeletesNothing() = runTest {
        // Another database was chosen meanwhile: the stored password, wrong for nothing, is never tried.
        val f = storedPassword("old-pw")
        val finger = CompletableDeferred<Unit>()
        f.stores.gate = finger
        val result = async { f.biometric.unlockWithFingerprint() }
        runCurrent()

        f.session.close()
        finger.complete(Unit)

        assertEquals(FingerprintOutcome.Cancelled, result.await())
        assertEquals("old-pw", f.stored()?.reveal())
        assertEquals(0, f.stores.deletes)
        assertIs<SessionState.Locked>(f.session.state.value)
        assertEquals(FingerprintOutcome.Cancelled, f.biometric.unlockWithFingerprint(), "closed: nothing is asked again")
        assertEquals(1, f.stores.prompts)
    }

    // Each database its own (keething-spec unlock.biometric AC10, unlock.biometric-invalidation AC7–AC9)

    @Test
    fun eachDatabaseHasItsOwnStoredPasswordAndTheFingerprintOpensItWithItsOwn() = runTest {
        val f = storedPassword("pw")
        val work = f.add("/db/Work.kdbx")
        f.store("work-pw", work.id)
        work.biometric.refresh()

        assertEquals(FingerprintOutcome.Unlocked, f.biometric.unlockWithFingerprint())
        assertEquals(FingerprintOutcome.StoredPasswordRejected, work.biometric.unlockWithFingerprint(), "Work's own, which its file rejects")

        assertEquals(listOf(f.id.fileName, work.id.fileName), f.stores.opened)
        assertNull(f.stored(work.id), "a stored password its database rejects deletes only that database's")
        assertEquals("pw", f.stored()?.reveal())
        assertEquals(0, f.stores.deleteAlls)
    }

    @Test
    fun aTypedPasswordIsStoredForItsOwnDatabaseAlone() = runTest {
        val f = fixture()
        val work = f.add("/db/Work.kdbx")

        f.typed("pw", work.biometric)
        runCurrent()

        assertIs<SessionState.Unlocked>(work.session.state.value)
        assertIs<SessionState.Locked>(f.session.state.value)
        assertEquals("pw", f.stored(work.id)?.reveal())
        assertNull(f.stored())
        assertTrue(work.biometric.state.value.stored)
        assertFalse(state(f).stored, "what Personal's lock screen says is its own")
    }

    @Test
    fun theDaysAreCountedPerDatabase() = runTest {
        val f = storedPassword("pw")
        val work = f.add("/db/Work.kdbx")
        f.store("pw", work.id)
        f.settings.setString(BiometricSetup.lastTypedKey(f.id), f.clock.now().minusSeconds(15L * 86_400).toEpochMilli().toString())
        f.settings.setString(BiometricSetup.lastTypedKey(work.id), f.clock.now().minusSeconds(1L * 86_400).toEpochMilli().toString())

        assertTrue(f.biometric.refresh().passwordDue, "Personal: 15 days")
        assertEquals(FingerprintOutcome.PasswordDue, f.biometric.unlockWithFingerprint())
        assertFalse(work.biometric.refresh().passwordDue, "Work: 1 day")

        f.typed("pw", work.biometric)
        runCurrent()
        assertTrue(f.biometric.refresh().passwordDue, "typing Work's password does not count for Personal")
    }

    @Test
    fun aDatabaseNewToTheListCountsItsDaysFromWhenItWasAdded() = runTest {
        val f = storedPassword("pw")
        f.clock.sleep(20.days)
        val work = f.add("/db/Work.kdbx")
        f.store("pw", work.id)

        assertFalse(work.biometric.refresh().passwordDue)
        assertTrue(work.biometric.state.value.canUnlock)
    }

    @Test
    fun aDatabasesOwnSwitchOffMeansNoFingerprintForItAndDeletesItsOwnOnly() = runTest {
        val f = storedPassword("pw")
        val work = f.add("/db/Work.kdbx")
        f.store("pw", work.id)

        assertNull(f.setup.setUseBiometric(f.id, false))
        runCurrent()

        assertFalse(f.list.record(f.id)!!.useBiometric)
        assertNull(f.stored())
        assertEquals("pw", f.stored(work.id)?.reveal())
        assertNull(f.settings.string(BiometricSetup.lastTypedKey(f.id)), "its days go too")
        assertFalse(state(f).enabled)
        assertEquals(FingerprintOutcome.NotAvailable("it is turned off for this database"), f.biometric.unlockWithFingerprint())
        assertEquals(0, f.stores.prompts)
        f.typed("pw")
        runCurrent()
        assertTrue(f.stores.saves.isEmpty(), "not stored while its own switch is off")

        f.session.lock()
        assertNull(f.biometric.useNextTime(true))
        f.typed("pw")
        runCurrent()
        assertEquals("pw", f.stored()?.reveal(), "on again: the next typed unlock stores it")
    }

    @Test
    fun forgettingADatabaseDeletesItsStoredPasswordItsDaysAndItsKeyFileNoteNobodyElses() = runTest {
        val f = storedPassword("pw")
        val work = f.add("/db/Work.kdbx")
        f.store("pw", work.id)
        f.setup.keyFileChanged(work.id)
        f.typed("pw", work.biometric)
        runCurrent()
        f.setup.keyFileChanged(work.id)

        f.list.remove(work.id) // as Remove from List does first
        assertNull(f.setup.forget(work.id))
        runCurrent()

        assertNull(f.stored(work.id))
        assertEquals("pw", f.stored()?.reveal())
        assertNull(f.settings.string(BiometricSetup.lastTypedKey(work.id)))
        assertNull(f.settings.string(BiometricSetup.keyFileChangedKey(work.id)))
        assertTrue(f.biometric.refresh().stored)
    }

    @Test
    fun theAppsSwitchOnStartsEveryDatabasesDays() = runTest {
        val f = fixture(enabled = false)
        val work = f.add("/db/Work.kdbx")
        f.clock.sleep(30.days)

        f.setup.setEnabled(true)

        assertEquals(f.clock.now().toEpochMilli().toString(), f.settings.string(BiometricSetup.lastTypedKey(f.id)))
        assertEquals(f.clock.now().toEpochMilli().toString(), f.settings.string(BiometricSetup.lastTypedKey(work.id)))
    }

    // A database that opens with its key file alone (keething-spec unlock.key-file AC6)

    @Test
    fun aKeyFileOnlyDatabaseOpensAfterTheFingerprintWithNothingStoredAndIsNeverDue() = runTest {
        val f = fixture(database = keyFileBytes("keyfile-only.kdbx"), keyFile = FakeKeyFile(keyFileBytes("keyx.keyx")))
        f.list.update(f.id) { it.copy(opensWithoutPassword = true) }
        f.clock.sleep(30.days)

        val state = f.biometric.refresh()
        assertTrue(state.canUnlock && state.passwordless && !state.stored && !state.passwordDue, "no password to forget")
        assertEquals(FingerprintOutcome.Unlocked, f.biometric.unlockWithFingerprint())

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(1, f.stores.confirms, "the fingerprint is the check that it is the user")
        assertTrue(f.stores.opened.isEmpty() && f.stores.saves.isEmpty())

        f.session.lock()
        f.setup.setUseBiometric(f.id, false)
        assertEquals(FingerprintOutcome.NotAvailable("it is turned off for this database"), f.biometric.unlockWithFingerprint())
        assertEquals(1, f.stores.confirms)
    }

    @Test
    fun aKeyFileOnlyDatabaseTheFingerprintNoLongerOpensBringsItsFieldBackAndDeletesNothing() = runTest {
        // A password was added elsewhere: the key file alone is rejected.
        val f = fixture(database = keyFileBytes("keyfile-keyx.kdbx"), keyFile = FakeKeyFile(keyFileBytes("keyx.keyx")))
        f.list.update(f.id) { it.copy(opensWithoutPassword = true) }
        f.store("pw") // stored before it opened with its key file alone

        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.WrongKeyFile), f.biometric.unlockWithFingerprint())

        assertFalse(f.list.record(f.id)!!.opensWithoutPassword, "the field comes back")
        assertEquals(0, f.stores.deletes)
        assertTrue(f.stores.opened.isEmpty())
        assertEquals("pw", f.stored()?.reveal())
    }

    @Test
    fun aKeyFileOnlyDatabaseWhoseKeyFileIsNotThereIsAskedNoFingerprint() = runTest {
        val f = fixture(database = keyFileBytes("keyfile-only.kdbx"), keyFile = FakeKeyFile(keyFileBytes("keyx.keyx")))
        f.list.update(f.id) { it.copy(opensWithoutPassword = true) }
        f.missing += "/keys/work.keyx"

        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.KeyFileMissing("work.keyx")), f.biometric.unlockWithFingerprint())
        assertEquals(0, f.stores.prompts)
    }

    // Asking for the typed password now and then

    @Test
    fun afterFourteenDaysWithoutATypedUnlockThePasswordIsDueAndTheFingerprintIsNotOffered() = runTest {
        val f = storedPassword("pw")
        assertEquals(14, f.biometric.state.value.reminderDays, "the default")

        f.clock.sleep(13.days)
        f.biometric.refresh()
        assertTrue(f.biometric.state.value.canUnlock)

        f.clock.sleep(1.days)
        f.biometric.refresh()
        assertTrue(f.biometric.state.value.passwordDue)
        assertTrue(f.biometric.state.value.isSetUp)
        assertFalse(f.biometric.state.value.canUnlock)
        assertEquals(FingerprintOutcome.PasswordDue, f.biometric.unlockWithFingerprint())
        assertTrue(f.session.state.value is SessionState.Locked)
        assertEquals("pw", f.stored()?.reveal(), "the stored copy stays")
    }

    @Test
    fun aTypedUnlockStartsTheDaysAgainAndAFingerprintUnlockDoesNot() = runTest {
        val f = storedPassword("pw")
        f.clock.sleep(10.days)
        assertEquals(FingerprintOutcome.Unlocked, f.biometric.unlockWithFingerprint())
        f.session.lock()

        f.clock.sleep(4.days)
        f.biometric.refresh()
        assertTrue(f.biometric.state.value.passwordDue, "the fingerprint unlock did not count")

        assertTrue(f.typed("pw"))
        runCurrent()
        assertFalse(f.biometric.state.value.passwordDue)
        f.session.lock()

        f.clock.sleep(13.days)
        f.biometric.refresh()
        assertTrue(f.biometric.state.value.canUnlock)
    }

    @Test
    fun aWrongTypedPasswordDoesNotStartTheDaysAgain() = runTest {
        val f = storedPassword("pw")
        f.clock.sleep(14.days)
        f.biometric.refresh()

        f.typed("wrong")
        runCurrent()
        f.biometric.refresh()

        assertTrue(f.biometric.state.value.passwordDue)
    }

    @Test
    fun theDaysCanBeChosenOrTurnedOffAndAreRemembered() = runTest {
        val f = storedPassword("pw")
        f.clock.sleep(8.days)
        f.biometric.refresh()
        assertFalse(f.biometric.state.value.passwordDue)

        f.setup.setReminderDays(7)
        assertTrue(state(f).passwordDue)

        f.setup.setReminderDays(null)
        assertFalse(state(f).passwordDue)
        f.clock.sleep(365.days)
        f.biometric.refresh()
        assertTrue(f.biometric.state.value.canUnlock)

        val next = BiometricSetup(f.stores, f.list, f.settings, f.clock, StandardTestDispatcher(testScheduler))
        assertNull(next.state.value.reminderDays)
    }

    @Test
    fun aSavedChoiceThatIsNotOneOfTheChoicesReadsAsTheDefault() = runTest {
        val settings = MapSettings()
        settings.setString("passwordReminderDays", "10")

        val f = fixture(settings = settings)

        assertEquals(14, f.setup.state.value.reminderDays)
        assertEquals(14, f.biometric.state.value.reminderDays)
    }

    @Test
    fun theDaysCountFromTurningItOn() = runTest {
        val f = fixture(enabled = false)
        assertTrue(f.typed("pw"))
        runCurrent()
        f.session.lock()

        f.clock.sleep(20.days)
        f.setup.setEnabled(true)
        f.store("pw")
        f.biometric.refresh()

        assertFalse(f.biometric.state.value.passwordDue)
    }

    @Test
    fun noTimeSavedYetOrOneInTheFutureCountsFromNow() = runTest {
        val settings = MapSettings()
        settings.setString("biometricUnlock", "on") // no time saved for the database
        val f = fixture(enabled = false, settings = settings)
        f.store("pw")
        f.clock.sleep(13.days)
        f.biometric.refresh()
        assertTrue(f.biometric.state.value.canUnlock)

        // The clock was set back.
        settings.setString(BiometricSetup.lastTypedKey(f.id), f.clock.now().plusSeconds(30L * 86_400).toEpochMilli().toString())
        f.biometric.refresh()
        f.clock.sleep(13.days)
        f.biometric.refresh()
        assertTrue(f.biometric.state.value.canUnlock)
    }

    // The one stored password of before (plan AD5)

    @Test
    fun theOneTimeAndKeyFileNoteOfBeforeGoAndTheCurrentDatabaseTakesTheSwitch() = runTest {
        val settings = MapSettings()
        val list = DatabaseList(settings)
        val personal = list.add("/db/Personal.kdbx")
        val work = list.add("/db/Work.kdbx")
        settings.setString("biometricUnlock", "on")
        settings.setString("lastTypedUnlock", "1")
        settings.setString("biometricKeyFileChanged", "on")

        BiometricSetup(FakePasswordStores(), list, settings, FakeClock(testScheduler), StandardTestDispatcher(testScheduler))

        assertTrue(list.record(personal.id)!!.useBiometric, "the switch of then was the current database's")
        assertFalse(list.record(work.id)!!.useBiometric, "it went off whenever another became current")
        assertNull(settings.string("lastTypedUnlock"))
        assertNull(settings.string("biometricKeyFileChanged"))

        val off = MapSettings()
        val offList = DatabaseList(off)
        val other = offList.add("/db/Other.kdbx")
        off.setString("lastTypedUnlock", "1")
        BiometricSetup(FakePasswordStores(), offList, off, FakeClock(testScheduler), StandardTestDispatcher(testScheduler))
        assertFalse(offList.record(other.id)!!.useBiometric, "off: nothing to take")
        assertNull(off.string("lastTypedUnlock"))
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
 * The Keystore and the keyring in memory, a password per database's name. Like the real one it keeps copies: what it
 * was given may be wiped by the caller, and what it hands out is the caller's to wipe. [prompt] is what the fingerprint
 * prompt does (null: the finger is recognised); with [gate] it waits for a finger until the gate completes. [several] is
 * what one prompt for several databases does instead (null: recognised, the keyring opened).
 */
internal class FakePasswordStores : BiometricPasswordStores {
    var availability: BiometricAvailability = BiometricAvailability.Available
    val stored = mutableMapOf<String, Secret>()
    var prompt: StoredPasswordOpening? = null
    var several: SeveralOpening? = null
    var gate: CompletableDeferred<Unit>? = null

    /** The databases whose stored copy the keyring does not open, and those that cannot be read (by name). */
    val copyInvalidated = mutableSetOf<String>()
    val unreadable = mutableSetOf<String>()

    /** Runs before each database's stored password is read under one prompt for several. */
    var beforeRead: (String) -> Unit = {}

    /** The databases each prompt for several was asked for, and those whose stored password it then read, in order. */
    val severals = mutableListOf<List<String>>()
    val read = mutableListOf<String>()

    /** The keyring's private key is open: from the accepted finger until the stored passwords are read. */
    var keyringOpen = false
        private set

    /** With it the recognised finger's password waits to be opened (StrongBox is slow) until it completes. */
    var decrypting: CompletableDeferred<Unit>? = null
    var saveError: Exception? = null
    var deleteError: Exception? = null
    var openError: Exception? = null

    /** The text of every password saved, and whose it was. */
    val saves = mutableListOf<String>()
    val savedFor = mutableListOf<String>()
    val handedOut = mutableListOf<Secret>()

    /** The databases whose stored password a prompt was asked for. */
    val opened = mutableListOf<String>()
    var confirms = 0
        private set
    var deletes = 0
        private set
    var deleteAlls = 0
        private set

    /** Every fingerprint prompt. */
    val prompts: Int get() = opened.size + confirms + severals.size

    override fun availability(): BiometricAvailability = availability

    override fun hasPassword(database: String): Boolean = database in stored

    override fun save(database: String, password: Secret) {
        saveError?.let { throw it }
        saves += password.reveal()
        savedFor += database
        stored.remove(database)?.wipe()
        stored[database] = password.copy()
    }

    override suspend fun open(database: String, onRecognised: () -> Unit): StoredPasswordOpening {
        opened += database
        gate?.await()
        openError?.let { throw it }
        prompt?.let { return it }
        val kept = stored[database] ?: return StoredPasswordOpening.NothingStored
        onRecognised()
        decrypting?.await()
        return StoredPasswordOpening.Opened(kept.copy().also { handedOut += it })
    }

    override suspend fun openSeveral(databases: List<String>, onRecognised: () -> Unit, stillWanted: (String) -> Boolean): SeveralOpening {
        severals += databases
        gate?.await()
        several?.let { return it }
        onRecognised()
        openError?.let { throw it }
        keyringOpen = true
        try {
            val each = linkedMapOf<String, StoredPasswordOpening>()
            val failures = linkedMapOf<String, String>()
            for (database in databases) {
                if (!stillWanted(database)) continue
                beforeRead(database)
                read += database
                when (database) {
                    in unreadable -> failures[database] = "could not read it"
                    in copyInvalidated -> each[database] = StoredPasswordOpening.CopyInvalidated
                    else -> each[database] = stored[database]?.let { StoredPasswordOpening.Opened(it.copy().also { copy -> handedOut += copy }) }
                        ?: StoredPasswordOpening.NothingStored
                }
            }
            return SeveralOpening.Opened(each, failures)
        } finally {
            keyringOpen = false
        }
    }

    override suspend fun confirm(onRecognised: () -> Unit): StoredPasswordOpening {
        confirms++
        gate?.await()
        prompt?.let { return it }
        onRecognised()
        return StoredPasswordOpening.Opened(Secret.empty())
    }

    override fun delete(database: String) {
        deletes++
        deleteError?.let { throw it }
        stored.remove(database)?.wipe()
    }

    override fun deleteAll() {
        deleteAlls++
        deleteError?.let { throw it }
        stored.values.forEach(Secret::wipe)
        stored.clear()
    }
}
