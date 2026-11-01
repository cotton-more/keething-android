package dev.keething.vault.biometric

import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.domain.biometric.SeveralOpening
import dev.keething.domain.secret.Secret
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.SessionFactory
import dev.keething.vault.databases.VaultSessions
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeClock
import dev.keething.vault.session.FakeKeyFile
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.VaultSession
import dev.keething.vault.session.fixtureBytes
import dev.keething.vault.session.keyFileBytes
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * One fingerprint for the window's databases (plan AD13; keething-spec unlock.biometric-several, databases.several-open
 * AC7): the Mac's `TouchIDTests` for several databases, over the keyring (one prompt opens every stored password).
 */
class WindowFingerprintTest {
    /** Holds the work dispatched to it until [release]: a database whose key derivation takes its time. */
    private class Held(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        private var held = true
        private val waiting = mutableListOf<Pair<CoroutineContext, Runnable>>()
        val isWaiting: Boolean get() = waiting.isNotEmpty()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) waiting += context to block else inner.dispatch(context, block)
        }

        fun release() {
            held = false
            waiting.forEach { (context, block) -> inner.dispatch(context, block) }
            waiting.clear()
        }
    }

    private class Fixture(private val scope: TestScope) {
        val settings = MapSettings()
        val clock = FakeClock(scope.testScheduler)
        private var ids = 0
        val list = DatabaseList(settings, makeId = { DatabaseId("db-${++ids}") })
        val stores = FakePasswordStores()
        private val io = StandardTestDispatcher(scope.testScheduler)
        val setup = BiometricSetup(stores, list, settings, clock, io)

        /** The files there are, by path; their key files by path. */
        val files = mutableMapOf<String, FakeStore>()
        val keyFiles = mutableMapOf<String, FakeKeyFile>()

        /** A database's own file work, by path ([Held]); the others share one. */
        val ios = mutableMapOf<String, CoroutineDispatcher>()

        /** The files and key files that are not there, by path. */
        val missing = mutableSetOf<String>()

        private val factory = SessionFactory { record, number, keyFilePath, watching ->
            val store = files.getOrPut(record.path) { FakeStore(null) }
            val session = VaultSession(
                store = store,
                opener = VaultOpener(),
                backups = { _, _, _ -> },
                clock = clock,
                scope = scope.backgroundScope,
                io = ios[record.path] ?: io,
                keyFile = { keyFilePath()?.let { keyFiles[it] } },
            )
            val biometric = BiometricUnlock(
                record.id, session, setup, list, scope.backgroundScope, watching,
                fileExists = { it !in missing }, appLocks = { sessions.lockCount },
            )
            DatabaseSession(number, record.id, File(record.path), store, session, biometric)
        }

        val sessions: VaultSessions by lazy { VaultSessions(list, scope.backgroundScope, factory, clock, lockTimeout = { 5.minutes }, now = clock::now) }
        val fingerprint: WindowFingerprint by lazy { WindowFingerprint(sessions, setup, list, fileExists = { it !in missing }) }

        /**
         * A database of the list whose file opens with "pw" unless [bytes] say otherwise, in the window, its fingerprint
         * on; [stored]: its stored password.
         */
        fun add(
            path: String,
            stored: String? = null,
            bytes: ByteArray? = fixtureBytes("kdbx4-aes-aeskdf"),
            useBiometric: Boolean = true,
            inWindow: Boolean = true,
        ): DatabaseRecord {
            files[path] = FakeStore(bytes)
            val record = list.add(path, useBiometric = useBiometric)
            list.setInWindow(record.id, inWindow)
            stored?.let { stores.stored[record.id.fileName] = Secret.of(it) }
            return assertNotNull(list.record(record.id))
        }

        fun stored(record: DatabaseRecord): String? = stores.stored[record.id.fileName]?.reveal()

        fun session(record: DatabaseRecord): VaultSession = assertNotNull(sessions.session(record.id)).session

        fun state(record: DatabaseRecord): SessionState = session(record).state.value

        /** Its password typed [days] before now. */
        fun typed(record: DatabaseRecord, daysAgo: Long) {
            settings.setString(BiometricSetup.lastTypedKey(record.id), clock.now().minusMillis(daysAgo.days.inWholeMilliseconds).toEpochMilli().toString())
        }
    }

    /** The fingerprint on for the app, the window's sessions made. */
    private suspend fun TestScope.fixture(): Fixture = Fixture(this).also {
        it.setup.setEnabled(true)
        it.sessions
    }

    /** The fixture's database with another password. */
    private fun withPassword(password: String): ByteArray =
        KdbxReader.openForEditing(fixtureBytes("kdbx4-aes-aeskdf"), DatabaseKey.of(Secret.of("pw"))).write(DatabaseKey.of(Secret.of(password))).file

    // spec: unlock.biometric-several/AC1
    // spec: databases.several-open/AC7
    @Test
    fun oneFingerprintOpensEveryDatabaseOfTheWindowThatCanEachWithItsOwnPasswordTheOthersSayWhy() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val work = f.add("/db/Work.kdbx", stored = "work-pw", bytes = withPassword("work-pw"))
        val due = f.add("/db/Due.kdbx", stored = "pw")
        val off = f.add("/db/Off.kdbx", useBiometric = false)
        val gone = f.add("/stick/Gone.kdbx", stored = "pw")
        val noKey = f.add("/db/NoKey.kdbx", stored = "pw")
        val keyOnly = f.add("/db/KeyOnly.kdbx", bytes = keyFileBytes("keyfile-only.kdbx"))
        val empty = f.add("/db/Empty.kdbx")
        val elsewhere = f.add("/db/Elsewhere.kdbx", stored = "pw", inWindow = false)
        f.list.update(noKey.id) { it.copy(keyFilePath = "/stick/taxes.keyx") }
        f.list.update(keyOnly.id) { it.copy(keyFilePath = "/keys/keyx.keyx", opensWithoutPassword = true) }
        f.keyFiles["/keys/keyx.keyx"] = FakeKeyFile(keyFileBytes("keyx.keyx"), "keyx.keyx")
        f.missing += listOf("/stick/Gone.kdbx", "/stick/taxes.keyx")
        f.typed(due, daysAgo = 15)

        val result = f.fingerprint.unlock()
        runCurrent()

        assertEquals(FingerprintOutcome.Unlocked, result.outcome)
        assertEquals(1, f.stores.prompts, "one prompt")
        assertEquals(listOf(listOf(personal.id.fileName, work.id.fileName)), f.stores.severals, "the stored passwords of those that can")
        assertEquals(listOf(personal.id, work.id, keyOnly.id), f.sessions.openIds, "each with its own; the key file alone")
        assertEquals(FingerprintOutcome.PasswordDue, result.each[due.id])
        assertEquals(FingerprintOutcome.NotAvailable("it is turned off for this database"), result.each[off.id])
        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.Missing), result.each[gone.id])
        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.KeyFileMissing("taxes.keyx")), result.each[noKey.id])
        assertEquals(FingerprintOutcome.NothingStored, result.each[empty.id])
        assertEquals(8, result.each.size, "the window's eight; not the one outside it")
        assertNull(result.each[elsewhere.id])
        assertEquals(listOf(personal.id.fileName, work.id.fileName), f.stores.read, "not read: those not going to be opened")
        for (record in listOf(due, off, gone, noKey, empty)) assertEquals(SessionState.Locked(LockReason.Initial), f.state(record))
        assertTrue(f.stores.handedOut.size == 2 && f.stores.handedOut.all { it.isWiped }, "each read password wiped once used")
        for (record in listOf(personal, work, keyOnly)) assertNotNull(f.list.record(record.id)?.lastOpened)
        assertEquals(0, f.stores.deletes + f.stores.deleteAlls)
    }

    // spec: unlock.biometric-several/AC2
    @Test
    fun oneStoredPasswordRejectedAmongSeveralDeletesThatOneTheOthersOpen() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val work = f.add("/db/Work.kdbx", stored = "old-pw")

        val result = f.fingerprint.unlock()

        assertEquals(FingerprintOutcome.Unlocked, result.outcome)
        assertEquals(mapOf(personal.id to FingerprintOutcome.Unlocked, work.id to FingerprintOutcome.StoredPasswordRejected), result.each)
        assertNull(f.stored(work))
        assertEquals("pw", f.stored(personal))
        assertEquals(SetUpAgain.PASSWORD_REJECTED, f.setup.state.value.setUpAgain[work.id], "its chip and lock screen say why")
        assertEquals(0, f.stores.deleteAlls)
    }

    // spec: unlock.biometric-several/AC4
    @Test
    fun aCancelledPromptIsSaidAsCancelledWhateverTheCurrentDatabaseCouldNot() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", useBiometric = false)
        val work = f.add("/db/Work.kdbx", stored = "pw")
        f.stores.several = SeveralOpening.Cancelled

        val result = f.fingerprint.unlock()

        assertEquals(FingerprintOutcome.Cancelled, result.outcome, "not Personal's fingerprint off")
        assertEquals(mapOf(personal.id to FingerprintOutcome.NotAvailable("it is turned off for this database"), work.id to FingerprintOutcome.Cancelled), result.each)
        assertEquals("pw", f.stored(work))
        assertEquals(emptyList(), f.sessions.openIds)

        f.stores.several = SeveralOpening.Failed("Too many attempts. Try again later.")
        assertEquals(FingerprintOutcome.AuthenticationFailed("Too many attempts. Try again later."), f.fingerprint.unlock().outcome)
        assertEquals("pw", f.stored(work), "a failed check keeps it")
    }

    // spec: unlock.biometric-several/AC4
    @Test
    fun theCurrentOnesOutcomeIsSaidWhenNoneOpenedElseTheFirsts() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "old-pw")
        val work = f.add("/db/Work.kdbx", stored = "pw")
        f.files.getValue("/db/Work.kdbx").change(null) // gone after the files were looked at

        val result = f.fingerprint.unlock()

        assertEquals(FingerprintOutcome.StoredPasswordRejected, result.outcome, "Personal's, the current one's")
        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.Missing), result.each[work.id])
        assertEquals(personal.id, f.list.currentId)
    }

    // spec: unlock.biometric-several/AC1
    @Test
    fun changedFingerprintsAmongSeveralOpenNothingAndEveryStoredPasswordGoes() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val work = f.add("/db/Work.kdbx", stored = "pw")
        val keyOnly = f.add("/db/KeyOnly.kdbx", bytes = keyFileBytes("keyfile-only.kdbx"))
        f.list.update(keyOnly.id) { it.copy(keyFilePath = "/keys/keyx.keyx", opensWithoutPassword = true) }
        f.keyFiles["/keys/keyx.keyx"] = FakeKeyFile(keyFileBytes("keyx.keyx"), "keyx.keyx")
        f.stores.several = SeveralOpening.KeyInvalidated

        val result = f.fingerprint.unlock()

        assertEquals(FingerprintOutcome.KeyInvalidated, result.outcome)
        assertEquals(emptyList(), f.sessions.openIds, "not even the one that needs no stored password")
        assertEquals(1, f.stores.deleteAlls)
        assertTrue(f.stores.stored.isEmpty())
        assertEquals(mapOf(personal.id to SetUpAgain.FINGERPRINTS_CHANGED, work.id to SetUpAgain.FINGERPRINTS_CHANGED), f.setup.state.value.setUpAgain)
    }

    @Test
    fun aStoredCopyTheKeyringDoesNotOpenGoesAloneAndOneThatCannotBeReadIsSaid() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val work = f.add("/db/Work.kdbx", stored = "pw")
        val family = f.add("/db/Family.kdbx", stored = "pw")
        f.stores.copyInvalidated += work.id.fileName
        f.stores.unreadable += family.id.fileName

        val result = f.fingerprint.unlock()

        assertEquals(FingerprintOutcome.Unlocked, result.outcome)
        assertEquals(listOf(personal.id), f.sessions.openIds)
        assertEquals(FingerprintOutcome.KeyInvalidated, result.each[work.id])
        assertNull(f.stored(work), "its own goes")
        assertEquals(SetUpAgain.FINGERPRINTS_CHANGED, f.setup.state.value.setUpAgain[work.id])
        assertEquals(FingerprintOutcome.StorageError("could not read it"), result.each[family.id])
        assertEquals("pw", f.stored(family), "kept: it may be for a moment")
    }

    @Test
    fun theKeystoreRefusingTheKeyringAfterTheFingerIsSaidAndCostsNothing() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        f.add("/db/Work.kdbx", stored = "pw")
        f.stores.openError = IOException("the Keystore did not open the keyring")

        val result = f.fingerprint.unlock()

        assertEquals(FingerprintOutcome.StorageError("the Keystore did not open the keyring"), result.outcome)
        assertEquals(emptyList(), f.sessions.openIds)
        assertEquals("pw", f.stored(personal))
        assertEquals(0, f.stores.deletes + f.stores.deleteAlls)
    }

    @Test
    fun aDatabaseAlreadyOpenIsNotAskedAgainAndWithAllOpenThereIsNoPrompt() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val work = f.add("/db/Work.kdbx", stored = "pw")
        f.session(personal).unlock(Secret.of("pw"))
        runCurrent()

        assertEquals(FingerprintOutcome.Unlocked, f.fingerprint.unlock().outcome)
        assertEquals(listOf(listOf(work.id.fileName)), f.stores.severals)
        assertEquals(listOf(personal.id, work.id), f.sessions.openIds)

        assertEquals(FingerprintOutcome.Unlocked, f.fingerprint.unlock().outcome, "nothing left to open")
        assertEquals(1, f.stores.prompts, "all open: no prompt")
    }

    @Test
    fun onlyDatabasesThatOpenWithTheirKeyFileAloneAskForTheFingerWithNothingToOpen() = runTest {
        // keething-spec unlock.biometric-several AC1: nothing is stored for them, the fingerprint is the check.
        val f = fixture()
        val keyOnly = f.add("/db/KeyOnly.kdbx", bytes = keyFileBytes("keyfile-only.kdbx"))
        f.add("/db/Empty.kdbx")
        f.list.update(keyOnly.id) { it.copy(keyFilePath = "/keys/keyx.keyx", opensWithoutPassword = true) }
        f.keyFiles["/keys/keyx.keyx"] = FakeKeyFile(keyFileBytes("keyx.keyx"), "keyx.keyx")
        f.typed(keyOnly, daysAgo = 30)

        assertEquals(FingerprintOutcome.Unlocked, f.fingerprint.unlock().outcome)
        assertEquals(1, f.stores.confirms)
        assertEquals(emptyList(), f.stores.severals)
        assertEquals(listOf(keyOnly.id), f.sessions.openIds, "never due")
        assertTrue(f.stores.saves.isEmpty())
    }

    // spec: unlock.biometric-several/AC3
    // spec: unlock.biometric-several/AC5
    @Test
    fun aDatabaseClosedWhileTheFingerIsAskedIsNotOpenedNorReadTheOthersAre() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val work = f.add("/db/Work.kdbx", stored = "pw")
        val finger = CompletableDeferred<Unit>()
        f.stores.gate = finger
        val asking = async { f.fingerprint.unlock() }
        runCurrent()

        f.sessions.close(work.id)
        finger.complete(Unit)
        val result = asking.await()
        runCurrent()

        assertEquals(FingerprintOutcome.Unlocked, result.outcome)
        assertEquals(FingerprintOutcome.Cancelled, result.each[work.id], "no verdict")
        assertEquals(listOf(personal.id.fileName), f.stores.read, "its password is not read for nothing")
        assertEquals(listOf(personal.id), f.sessions.openIds)
        assertEquals("pw", f.stored(work))
    }

    // spec: unlock.biometric-several/AC3
    @Test
    fun aLockWhileTheStoredPasswordsAreReadOpensNothingAndReadsNoMore() = runTest {
        // Also a Lock with every database already locked: the app's locks count (found by AD8's review).
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val work = f.add("/db/Work.kdbx", stored = "pw")
        f.stores.beforeRead = { database -> if (database == personal.id.fileName) f.sessions.lockAll() }

        val result = f.fingerprint.unlock()
        runCurrent()

        assertEquals(FingerprintOutcome.Cancelled, result.outcome)
        assertEquals(emptyList(), f.sessions.openIds, "nothing behind the lock screen")
        assertEquals(listOf(personal.id.fileName), f.stores.read, "not read after the lock")
        assertTrue(f.stores.handedOut.all { it.isWiped }, "the one read is wiped")
        assertEquals(0, f.files.getValue("/db/Personal.kdbx").reads + f.files.getValue("/db/Work.kdbx").reads, "no file opened")
        assertEquals("pw", f.stored(personal))
        assertEquals("pw", f.stored(work))
    }

    // spec: unlock.biometric-several/AC2
    @Test
    fun eachDatabaseIsJudgedByItsOwnAttemptAtOnceALaterLockAndTypoNeverCostItsStoredPassword() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val held = Held(StandardTestDispatcher(testScheduler))
        f.ios["/db/Work.kdbx"] = held
        val work = f.add("/db/Work.kdbx", stored = "old-pw")
        val asking = async { f.fingerprint.unlock() }
        runCurrent()
        assertTrue(held.isWaiting, "Work deriving its key")
        assertIs<SessionState.Unlocked>(f.state(personal))

        f.sessions.lock(personal.id) // Personal locked alone…
        f.session(personal).unlock(Secret.of("typo")) // …and its password mistyped
        runCurrent()
        held.release()
        val result = asking.await()
        runCurrent()

        assertEquals(FingerprintOutcome.Unlocked, result.each[personal.id], "what its own attempt did")
        assertEquals("pw", f.stored(personal))
        assertEquals(FingerprintOutcome.StoredPasswordRejected, result.each[work.id], "one locked alone does not stop the others")
        assertNull(f.stored(work))
    }

    // spec: unlock.biometric-several/AC3
    @Test
    fun aDatabaseLockedAloneWhileItsKeyIsDerivedGetsNoVerdictTheOthersOpen() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val held = Held(StandardTestDispatcher(testScheduler))
        f.ios["/db/Work.kdbx"] = held
        val work = f.add("/db/Work.kdbx", stored = "old-pw")
        val asking = async { f.fingerprint.unlock() }
        runCurrent()

        f.sessions.lock(work.id)
        held.release()
        val result = asking.await()
        runCurrent()

        assertEquals(mapOf(personal.id to FingerprintOutcome.Unlocked, work.id to FingerprintOutcome.Cancelled), result.each)
        assertEquals(SessionState.Locked(LockReason.Manual), f.state(work))
        assertEquals("old-pw", f.stored(work), "kept: no verdict")
        assertEquals(0, f.stores.deletes)
    }

    // spec: unlock.biometric-several/AC1
    @Test
    fun whatOpenedTheStoredPasswordsIsLetGoBeforeAnyKeyIsDerived() = runTest {
        val f = fixture()
        f.add("/db/Personal.kdbx", stored = "pw")
        f.add("/db/Work.kdbx", stored = "pw")
        val derivedWhileOpen = mutableListOf<Boolean>()
        f.files.values.forEach { store -> store.beforeRead = { derivedWhileOpen += f.stores.keyringOpen } }

        f.fingerprint.unlock()

        assertEquals(listOf(false, false), derivedWhileOpen)
    }

    @Test
    fun aLockAfterTheCheckWithEveryDatabaseStillLockedOpensNoneOfThem() = runTest {
        // The app's lock count (the Mac's VaultSessions.lockCount): lockAll skips the databases already locked, whose own
        // count does not change (found by AD8's review).
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val finger = CompletableDeferred<Unit>()
        f.stores.gate = finger
        val asking = async { f.fingerprint.unlock() }
        runCurrent()

        f.sessions.lockAll()
        finger.complete(Unit)
        val result = asking.await()
        runCurrent()

        assertEquals(FingerprintOutcome.Cancelled, result.outcome)
        assertEquals(SessionState.Locked(LockReason.Initial), f.state(personal))
        assertEquals(emptyList(), f.stores.read)
    }

    @Test
    fun oneFingerprintAtATimeForTheWindowOrADatabasesOwnLockScreen() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        f.add("/db/Work.kdbx", stored = "pw")
        val finger = CompletableDeferred<Unit>()
        f.stores.gate = finger
        val asking = async { f.fingerprint.unlock() }
        runCurrent()
        assertTrue(f.fingerprint.running.value)

        val own = assertNotNull(f.sessions.session(personal.id)).biometric
        own.refresh()
        assertEquals(FingerprintOutcome.InProgress, own.unlockWithFingerprint())
        assertEquals(FingerprintOutcome.InProgress, f.fingerprint.unlock().outcome)
        finger.complete(Unit)
        assertEquals(FingerprintOutcome.Unlocked, asking.await().outcome)
        assertFalse(f.fingerprint.running.value)
        assertEquals(1, f.stores.prompts)
    }

    // spec: databases.several-open/AC8
    @Test
    fun aDatabasesOwnLockScreenOpensItAloneTheOthersThatCouldStayLockedAndUnread() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val work = f.add("/db/Work.kdbx", stored = "pw")
        val own = assertNotNull(f.sessions.session(work.id)).biometric
        own.refresh()

        assertEquals(FingerprintOutcome.Unlocked, own.unlockWithFingerprint())
        runCurrent()

        assertEquals(listOf(work.id), f.sessions.openIds)
        assertEquals(listOf(work.id.fileName), f.stores.opened, "Personal's password is not read for Work's lock screen")
        assertEquals(emptyList(), f.stores.severals)
        assertEquals(SessionState.Locked(LockReason.Initial), f.state(personal))
    }

    @Test
    fun whetherAFingerprintWouldOpenAnyOfTheWindowsLockedDatabasesWithoutReadingAPassword() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx")
        val work = f.add("/db/Work.kdbx")
        f.add("/db/Off.kdbx", stored = "pw", useBiometric = false)
        f.add("/db/Elsewhere.kdbx", stored = "pw", inWindow = false)
        assertFalse(f.fingerprint.canUnlockAny(), "nothing stored")

        f.stores.stored[work.id.fileName] = Secret.of("pw")
        assertTrue(f.fingerprint.canUnlockAny())
        f.typed(work, daysAgo = 15)
        assertFalse(f.fingerprint.canUnlockAny(), "its password is due")
        f.typed(work, daysAgo = 0)
        f.missing += "/db/Work.kdbx"
        assertFalse(f.fingerprint.canUnlockAny(), "its file is not there")
        f.missing.clear()
        f.stores.availability = BiometricAvailability.Unavailable("no fingerprint is set up on this phone")
        assertFalse(f.fingerprint.canUnlockAny(), "the phone cannot")
        f.stores.availability = BiometricAvailability.Available
        f.setup.setEnabled(false)
        assertFalse(f.fingerprint.canUnlockAny(), "the app's switch off")
        assertEquals(0, f.stores.prompts)
        assertEquals(emptyList(), f.stores.read, "asked, never read")
        assertEquals(personal.id, f.list.currentId)
    }

    // spec: databases.chooser/AC5
    @Test
    fun askedForSomeOfTheWindowItOpensAndLooksAtThoseOnly() = runTest {
        val f = fixture()
        val personal = f.add("/db/Personal.kdbx", stored = "pw")
        val work = f.add("/db/Work.kdbx")
        val keyOnly = f.add("/db/KeyOnly.kdbx", bytes = keyFileBytes("keyfile-only.kdbx"))
        f.list.update(keyOnly.id) { it.copy(keyFilePath = "/keys/keyx.keyx", opensWithoutPassword = true) }
        f.keyFiles["/keys/keyx.keyx"] = FakeKeyFile(keyFileBytes("keyx.keyx"), "keyx.keyx")

        assertFalse(f.fingerprint.canUnlockAny(among = listOf(work.id)), "nothing stored for Work, the others not looked at")
        assertTrue(f.fingerprint.canUnlockAny(among = listOf(personal.id)))
        f.stores.stored[work.id.fileName] = Secret.of("pw")
        val result = f.fingerprint.unlock(among = listOf(personal.id, keyOnly.id))
        runCurrent()

        assertEquals(FingerprintOutcome.Unlocked, result.outcome)
        assertEquals(1, f.stores.prompts)
        assertEquals(listOf(listOf(personal.id.fileName)), f.stores.severals, "Work's password not read")
        assertEquals(listOf(personal.id, keyOnly.id), f.sessions.openIds)
        assertEquals(setOf(personal.id, keyOnly.id), result.each.keys)
        assertEquals(SessionState.Locked(LockReason.Initial), f.state(work))
    }
}
