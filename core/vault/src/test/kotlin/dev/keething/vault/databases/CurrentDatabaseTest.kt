package dev.keething.vault.databases

import dev.keething.domain.encryption.DatabaseEncryption
import dev.keething.domain.encryption.DatabaseEncryption.Cipher
import dev.keething.domain.encryption.DatabaseEncryption.KeyDerivation
import dev.keething.domain.encryption.MeasuredEncryption
import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.NewFile
import dev.keething.domain.storage.NewFilePlace
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.biometric.FakePasswordStores
import dev.keething.vault.biometric.FingerprintOutcome
import dev.keething.vault.biometric.MapSettings
import dev.keething.vault.creating.DatabaseCreator
import dev.keething.vault.creating.DerivedKey
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
import java.io.File
import java.io.IOException
import java.time.Instant
import dev.keething.domain.storage.StoragePlace
import dev.keething.domain.storage.Grants
import dev.keething.vault.storage.SaveNotes
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The current database and its session (plan AD4): the Mac's D4 `DatabaseListModelTests` for choosing, opening,
 * locating, a key file and Remove from List, each database with its own stored password (plan AD5).
 */
/** What the fakes are found by: a path, or the location written out. */
private fun key(file: FileLocation): String = (file as? FileLocation.ByPath)?.path ?: file.toString()

class CurrentDatabaseTest {
    private class Fixture(scope: TestScope, val settings: MapSettings = MapSettings()) {
        val list = DatabaseList(settings, makeId = { DatabaseId("db-${++ids}") })
        val passwords = FakePasswordStores()
        val clock = FakeClock(scope.testScheduler)

        /** The files there are, by path. */
        val files = mutableMapOf<String, FakeStore>()
        val keyFiles = mutableMapOf<String, FakeKeyFile>()
        private val io = StandardTestDispatcher(scope.testScheduler)
        private var ids = 0

        val setup = BiometricSetup(passwords, list, settings, clock, io)

        private val factory = SessionFactory { record, number, keyFilePath, watching ->
            val store = files.getOrPut(key(record.file)) { FakeStore(null) }
            val session = VaultSession(
                store = store,
                opener = VaultOpener(),
                backups = { _, _, _ -> },
                clock = clock,
                scope = scope.backgroundScope,
                io = io,
                keyFile = { keyFilePath()?.let { keyFiles.getOrPut(key(it)) { FakeKeyFile(null, it.fileName) } } },
            )
            val biometric = BiometricUnlock(
                record.id, session, setup, list, scope.backgroundScope, watching, fileExists = { true }, appLocks = { sessions.lockCount },
            )
            DatabaseSession(number, record.id, record.file, store, session, biometric)
        }

        val sessions: VaultSessions by lazy { VaultSessions(list, scope.backgroundScope, factory, clock, lockTimeout = { 5.minutes }, now = clock::now) }
        /** The grants told to the keeper, in order. */
        val offered = mutableListOf<String>()
        var keeper: GrantKeeper? = null
        val databases: CurrentDatabase by lazy {
            CurrentDatabase(list, scope.backgroundScope, sessions, setup, offered = { grant -> offered += grant; keeper?.offered(grant) })
        }

        val shown: DatabaseSession get() = assertNotNull(databases.current.value)

        /** A database of the list whose file opens with "pw". */
        fun add(path: String, bytes: ByteArray? = fixtureBytes("kdbx4-aes-aeskdf"), keyFile: Pair<String, ByteArray?>? = null): DatabaseRecord {
            files[path] = FakeStore(bytes)
            val record = list.add(path)
            keyFile?.let { (keyPath, content) ->
                keyFiles[keyPath] = FakeKeyFile(content, File(keyPath).name)
                list.update(record.id) { it.copy(keyFile = FileLocation.ByPath(keyPath)) }
            }
            return list.record(record.id)!!
        }

        /** The fingerprint on, with [text] stored for [id] (by default the current database). */
        suspend fun storedPassword(text: String, id: DatabaseId = list.currentId!!) {
            setup.setEnabled(true)
            list.update(id) { it.copy(useBiometric = true) }
            passwords.stored[id.fileName] = Secret.of(text)
        }

        fun stored(id: DatabaseId): String? = passwords.stored[id.fileName]?.reveal()
    }

    private fun TestScope.typed(f: Fixture, text: String, database: DatabaseSession = f.shown): Boolean {
        val typed = Secret.of(text)
        val started = f.databases.unlock(database, typed)
        typed.wipe()
        runCurrent()
        return started != null
    }

    private fun DatabaseSession.state(): SessionState = session.state.value

    // At the start

    @Test
    fun theSessionIsTheListsCurrentDatabaseAndAStoredPasswordStays() = runTest {
        val f = Fixture(this)
        val personal = f.add("/storage/emulated/0/Sync/Personal.kdbx")
        f.add("/storage/emulated/0/Sync/Work.kdbx")
        f.storedPassword("pw")

        assertEquals(personal.id, f.shown.id)
        assertEquals("/storage/emulated/0/Sync/Personal.kdbx", f.shown.file.path)
        assertEquals(1, f.shown.number)
        f.shown.biometric.refresh()
        assertTrue(f.shown.biometric.state.value.isSetUp, "a start with the list keeps the current one's")
    }

    @Test
    fun anEmptyListHasNoSession() = runTest {
        val f = Fixture(this)
        assertNull(f.databases.current.value)
    }

    @Test
    fun aDatabaseOpenedIntoTheListStartsWithItsFingerprintOffWhateverTheAppsSwitch() = runTest {
        // The owner's choice at plan AD5: no password is stored for a database nobody turned the fingerprint on for.
        val f = Fixture(this)
        f.files["/storage/emulated/0/Personal.kdbx"] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        f.files["/storage/emulated/0/Work.kdbx"] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        f.setup.setEnabled(true)
        val personal = f.databases.open("/storage/emulated/0/Personal.kdbx")
        assertFalse(f.list.record(personal)!!.useBiometric, "the app's switch allows it, and turns none on")
        assertNull(f.shown.biometric.useNextTime(true))

        val work = f.databases.open("/storage/emulated/0/Work.kdbx")
        typed(f, "pw")

        assertTrue(f.list.record(personal)!!.useBiometric)
        assertFalse(f.list.record(work)!!.useBiometric)
        assertFalse(f.shown.biometric.state.value.enabled)
        assertTrue(f.passwords.saves.isEmpty(), "Work's typed password is not stored: nobody turned it on for Work")
    }

    // Choosing another (the lock screen's sheet)

    @Test
    fun anotherChosenJoinsTheWindowLockedAndTheOneShownBeforeStaysAsItWas() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        val first = f.shown
        typed(f, "typo")
        val wrong = SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongPassword))
        assertEquals(wrong, first.state())

        f.databases.choose(work.id)

        assertEquals(work.id, f.list.currentId)
        assertEquals(work.id, f.shown.id)
        assertEquals(2, f.shown.number)
        assertEquals(SessionState.Locked(LockReason.Initial), f.shown.state(), "the wrong password was the other one's")
        assertEquals(listOf(personal.id, work.id), f.sessions.windowIds, "both in the window")
        assertEquals(wrong, first.state(), "Personal keeps its own reason")
        assertFalse(first.session.isClosed)

        val same = f.shown
        f.databases.choose(work.id)
        assertSame(same, f.shown, "the same one again changes nothing")
        f.databases.choose(DatabaseId("not in the list"))
        assertSame(same, f.shown)
        f.databases.choose(personal.id)
        assertSame(first, f.shown, "back to the one before: its session of before")
    }

    @Test
    fun anotherChosenWhileOneIsOpenClosesNothing() = runTest {
        // keething-spec databases.list AC4, databases.several-open AC4.
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        val first = f.shown
        typed(f, "pw")
        advanceTimeBy(1.seconds)
        val open = assertIs<SessionState.Unlocked>(first.state())

        f.databases.choose(work.id)

        assertSame(open, first.state(), "Personal stays open")
        assertEquals(work.id, f.shown.id)
        assertEquals(SessionsState.Open(listOf(OpenVault(personal.id, open.vault))), f.sessions.state.value)
        typed(f, "pw")
        advanceTimeBy(1.seconds)
        assertEquals(listOf(personal.id, work.id), f.sessions.openIds, "both open, in the window's order")
    }

    // spec: databases.several-open/AC4
    // spec: databases.not-open/AC4
    @Test
    fun closingOneTakesItOutOfTheWindowLockedTheWindowKeepsOneAndPutBackReturnsIt() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        val crypto = f.add("/c/Crypto.kdbx")
        f.storedPassword("pw", work.id)
        typed(f, "pw")
        f.databases.choose(work.id)
        typed(f, "pw")
        f.databases.choose(crypto.id)
        advanceTimeBy(1.seconds)
        assertEquals(listOf(personal.id, work.id), f.sessions.openIds)
        assertEquals(crypto.id, f.list.currentId)

        // The current one closed: the window's first open one is current, it leaves the window locked and stays listed.
        assertTrue(f.databases.close(crypto.id))
        assertEquals(personal.id, f.list.currentId, "an open one, so the vault stays")
        assertEquals(listOf(personal.id, work.id), f.list.window.map { it.id })
        assertNotNull(f.list.record(crypto.id))

        // An open one closed: it locks and leaves; its stored password stays.
        assertTrue(f.databases.close(work.id))
        assertEquals(listOf(personal.id), f.sessions.openIds)
        assertNull(f.sessions.session(work.id))
        assertEquals("pw", f.stored(work.id), "its stored password stays with it in the list")

        // The window's last one stays.
        assertFalse(f.databases.close(personal.id))
        assertEquals(listOf(personal.id), f.list.window.map { it.id })
        assertIs<SessionState.Unlocked>(f.shown.state())

        // Undo: back into the window, locked; the open one stays open and current.
        f.databases.putBack(work.id)
        assertEquals(listOf(personal.id, work.id), f.list.window.map { it.id })
        assertEquals(SessionState.Locked(LockReason.Initial), assertNotNull(f.sessions.session(work.id)).state())
        assertEquals(personal.id, f.list.currentId)
        assertEquals(listOf(personal.id), f.sessions.openIds)
    }

    @Test
    fun anotherChosenKeepsEachOnesStoredPasswordAndEachIsUsedForItsOwn() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        f.storedPassword("pw", personal.id)
        f.storedPassword("pw", work.id)

        f.databases.choose(work.id)
        f.shown.biometric.refresh()
        assertEquals(FingerprintOutcome.Unlocked, f.shown.biometric.unlockWithFingerprint())
        f.databases.choose(personal.id)
        f.shown.biometric.refresh()

        assertTrue(f.shown.biometric.state.value.canUnlock)
        assertEquals("pw", f.stored(personal.id))
        assertEquals("pw", f.stored(work.id))
        assertEquals(listOf(work.id.fileName), f.passwords.opened)
        assertEquals(0, f.passwords.deletes + f.passwords.deleteAlls)
    }

    @Test
    fun aLockOfAllWhileALockedDatabasesStoredPasswordIsReadOpensNothing() = runTest {
        // Found by AD8's review: Lock all skips the databases already locked, so their own lock count does not change;
        // the app's is counted too (the Mac's VaultSessions.lockCount).
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        f.add("/b/Work.kdbx")
        f.storedPassword("pw")
        f.shown.biometric.refresh()
        val decrypted = CompletableDeferred<Unit>()
        f.passwords.decrypting = decrypted
        val asking = async { f.shown.biometric.unlockWithFingerprint() }
        runCurrent()
        assertTrue(f.shown.biometric.state.value.opening, "the finger was accepted")

        f.sessions.lockAll()
        decrypted.complete(Unit)

        assertEquals(FingerprintOutcome.Cancelled, asking.await())
        runCurrent()
        assertEquals(SessionState.Locked(LockReason.Initial), f.shown.state())
        assertEquals("pw", f.stored(personal.id))
    }

    @Test
    fun anotherChosenWhileTheFingerprintIsAskedOpensTheFirstWithItsOwnAndNothingElse() = runTest {
        // The Mac's D4 rule (never the first one's stored password on another) holds by itself now that choosing
        // closes nothing: the fingerprint's unlock is its own database's, which stays in the window.
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        f.storedPassword("pw", personal.id)
        val first = f.shown
        first.biometric.refresh()
        val finger = CompletableDeferred<Unit>()
        f.passwords.gate = finger
        val asking = async { first.biometric.unlockWithFingerprint() }
        runCurrent()

        f.databases.choose(work.id)
        finger.complete(Unit)

        assertEquals(FingerprintOutcome.Unlocked, asking.await())
        advanceTimeBy(1.seconds)
        assertIs<SessionState.Unlocked>(first.state())
        assertEquals(SessionState.Locked(LockReason.Initial), f.shown.state(), "Work is not opened with Personal's")
        assertEquals(work.id, f.shown.id)
        assertEquals("pw", f.stored(personal.id))
        assertEquals(0, f.passwords.deletes)
    }

    // Opening a file

    @Test
    fun aFileOpenedJoinsTheListOnceAndBecomesCurrent() = runTest {
        val f = Fixture(this)
        f.add("/a/Personal.kdbx")
        f.files["/b/Work.kdbx"] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))

        val work = f.databases.open("/b/Work.kdbx")

        assertEquals(work, f.list.currentId)
        assertEquals(work, f.shown.id)
        val shown = f.shown
        assertEquals(work, f.databases.open("/b/./Work.kdbx"))
        assertEquals(2, f.list.records.size)
        assertSame(shown, f.shown, "already current")
    }

    // Locate…

    @Test
    fun locatingKeepsTheDatabaseItsIdAndStoredPasswordAndTheOldNotFoundGoes() = runTest {
        val f = Fixture(this)
        val crypto = f.add("/storage/1234-5678/Crypto.kdbx", bytes = null)
        f.storedPassword("pw")
        typed(f, "pw")
        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.Missing)), f.shown.state())
        f.files["/storage/emulated/0/Crypto.kdbx"] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))

        assertNull(f.databases.locate(crypto.id, "/storage/emulated/0/./Crypto.kdbx"))

        assertEquals("/storage/emulated/0/Crypto.kdbx", f.list.record(crypto.id)?.path)
        assertEquals(crypto.id, f.list.currentId)
        assertEquals("/storage/emulated/0/Crypto.kdbx", f.shown.file.path)
        assertEquals(SessionState.Locked(LockReason.Initial), f.shown.state())
        runCurrent()
        assertEquals("pw", f.stored(crypto.id), "the same database: its stored password stays")
        assertTrue(f.shown.biometric.state.value.enabled)
        assertTrue(typed(f, "pw"))
        assertIs<SessionState.Unlocked>(f.shown.state())
    }

    @Test
    fun locatingAnOpenDatabaseLocksItFirst() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val first = f.shown
        typed(f, "pw")
        f.files["/c/Personal.kdbx"] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))

        f.databases.locate(personal.id, "/c/Personal.kdbx")

        assertIs<SessionState.Locked>(first.state())
        assertEquals(SessionState.Locked(LockReason.Initial), f.shown.state())
        assertEquals(personal.id, f.shown.id)
    }

    @Test
    fun aPathAnotherDatabaseOfTheListHasIsRefused() = runTest {
        val f = Fixture(this)
        f.add("/a/Personal.kdbx")
        val crypto = f.add("/s/Crypto.kdbx", bytes = null)

        assertEquals("That file is already in the list as “Personal”.", f.databases.locate(crypto.id, "/a/../a/Personal.kdbx"))

        assertEquals("/s/Crypto.kdbx", f.list.record(crypto.id)?.path)
        assertNull(f.databases.locate(crypto.id, "/s/Crypto.kdbx"), "its own path is no other's")
    }

    @Test
    fun locatingAnotherDatabaseThanTheOneShownChangesOnlyItsRecord() = runTest {
        val f = Fixture(this)
        f.add("/a/Personal.kdbx")
        val crypto = f.add("/s/Crypto.kdbx", bytes = null)
        val shown = f.shown

        f.databases.locate(crypto.id, "/t/Crypto.kdbx")

        assertSame(shown, f.shown)
        assertEquals("/t/Crypto.kdbx", f.list.record(crypto.id)?.path)
    }

    // The key file

    @Test
    fun aKeyFileChosenChangedOrForgottenAndWhatWasLearntOfOpeningWithoutAPasswordGoesWithIt() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val id = personal.id

        f.databases.setKeyFile(id, "/k/./personal.keyx")
        assertEquals("/k/personal.keyx", f.list.record(id)?.keyFilePath)
        assertEquals("on", f.settings.string(BiometricSetup.keyFileChangedKey(id)), "a stored password rejected before the next unlock is kept")
        val work = f.add("/b/Work.kdbx")
        f.databases.setKeyFile(work.id, "/k/work.keyx")
        assertEquals("on", f.settings.string(BiometricSetup.keyFileChangedKey(work.id)), "also of a database not shown")

        f.list.update(id) { it.copy(opensWithoutPassword = true) }
        f.databases.setKeyFile(id, "/k/other.keyx")
        assertFalse(f.list.record(id)!!.opensWithoutPassword)

        f.list.update(id) { it.copy(opensWithoutPassword = true) }
        f.databases.setKeyFile(id, null)
        assertNull(f.list.record(id)?.keyFilePath)
        assertFalse(f.list.record(id)!!.opensWithoutPassword)
    }

    @Test
    fun aKeyFileChosenOnTheLockScreenReplacesWhatItSaidWithoutIt() = runTest {
        val f = Fixture(this)
        val work = f.add("/w/Work.kdbx", bytes = keyFileBytes("keyfile-keyx.kdbx"))
        typed(f, "pw")
        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongPassword)), f.shown.state())
        val shown = f.shown

        f.keyFiles["/k/keyx.keyx"] = FakeKeyFile(keyFileBytes("keyx.keyx"), "keyx.keyx")
        f.databases.setKeyFile(work.id, "/k/keyx.keyx")

        assertSame(shown, f.shown, "the same session: its key file is read at the unlock")
        assertEquals(SessionState.Locked(LockReason.Initial), f.shown.state())
        assertTrue(typed(f, "pw"))
        assertIs<SessionState.Unlocked>(f.shown.state())
    }

    @Test
    fun aKeyFileChangedWhileOpenKeepsItOpen() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        typed(f, "pw")

        f.databases.setKeyFile(personal.id, "/k/a.keyx")

        assertIs<SessionState.Unlocked>(f.shown.state())
    }

    // Nothing typed with a key file

    @Test
    fun aDatabaseOpenedWithItsKeyFileAloneIsRememberedSoUntilThatNoLongerOpensIt() = runTest {
        val f = Fixture(this)
        val keys = f.add("/k/Keys.kdbx", bytes = keyFileBytes("keyfile-only.kdbx"), keyFile = "/k/keyx.keyx" to keyFileBytes("keyx.keyx"))
        val id = keys.id

        assertTrue(typed(f, ""))
        assertIs<SessionState.Unlocked>(f.shown.state())
        assertTrue(f.list.record(id)!!.isPasswordless)

        // A password was added elsewhere: nothing typed no longer opens it, and the field comes back.
        f.shown.session.lock()
        f.files.getValue("/k/Keys.kdbx").change(keyFileBytes("keyfile-keyx.kdbx"))
        typed(f, "")
        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongKeyFile)), f.shown.state())
        assertFalse(f.list.record(id)!!.isPasswordless)

        // A typed password that opens it says it needs one.
        f.list.update(id) { it.copy(opensWithoutPassword = true) }
        typed(f, "pw")
        assertIs<SessionState.Unlocked>(f.shown.state())
        assertFalse(f.list.record(id)!!.opensWithoutPassword)
    }

    @Test
    fun aKeyFileNotThereTeachesNothing() = runTest {
        val f = Fixture(this)
        val keys = f.add("/k/Keys.kdbx", bytes = keyFileBytes("keyfile-only.kdbx"), keyFile = "/k/keyx.keyx" to null)
        f.list.update(keys.id) { it.copy(opensWithoutPassword = true) }

        typed(f, "")

        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.KeyFileMissing("keyx.keyx"))), f.shown.state())
        assertTrue(f.list.record(keys.id)!!.opensWithoutPassword)
    }

    @Test
    fun anUnlockInterruptedOrWhoseKeyFileChangedMeanwhileTeachesNothingAnotherChosenDoesNotStopIt() = runTest {
        val f = Fixture(this)
        val keys = f.add("/k/Keys.kdbx", bytes = keyFileBytes("keyfile-only.kdbx"), keyFile = "/k/keyx.keyx" to keyFileBytes("keyx.keyx"))
        val other = f.add("/o/Other.kdbx")

        // Another chosen meanwhile: the unlock goes on in the window, and what it learns is its own database's.
        val first = f.shown
        assertNotNull(f.databases.unlock(first, Secret.empty()))
        f.databases.choose(other.id)
        runCurrent()
        assertIs<SessionState.Unlocked>(first.state())
        assertTrue(f.list.record(keys.id)!!.opensWithoutPassword)
        first.session.lock()
        f.list.update(keys.id) { it.copy(opensWithoutPassword = false) }

        // Its file located meanwhile: a new session; the old one locked, so its unlock ended interrupted.
        f.files["/k2/Keys.kdbx"] = FakeStore(keyFileBytes("keyfile-only.kdbx"))
        assertNotNull(f.databases.unlock(first, Secret.empty()))
        assertNull(f.databases.locate(keys.id, "/k2/Keys.kdbx"))
        runCurrent()
        assertFalse(f.list.record(keys.id)!!.opensWithoutPassword)

        f.databases.choose(keys.id)
        assertNotNull(f.databases.unlock(f.shown, Secret.empty()))
        f.keyFiles["/k/same.keyx"] = FakeKeyFile(keyFileBytes("keyx.keyx"), "same.keyx")
        f.databases.setKeyFile(keys.id, "/k/same.keyx")
        runCurrent()
        assertIs<SessionState.Unlocked>(f.shown.state(), "the key file read at the start opened it")
        assertFalse(f.list.record(keys.id)!!.opensWithoutPassword)
    }

    @Test
    fun withoutAKeyFileNothingIsLearnt() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")

        assertTrue(typed(f, "pw"))

        assertFalse(f.list.record(personal.id)!!.opensWithoutPassword)
        assertNull(f.databases.unlock(f.shown, Secret.of("pw")), "open already: nothing started")
    }

    @Test
    fun anyUnlockThatOpensItIsNotedAsItsLastOpened() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        f.storedPassword("pw")
        runCurrent()
        assertNull(f.list.record(personal.id)?.lastOpened)

        typed(f, "pw")
        val typedAt = assertNotNull(f.list.record(personal.id)?.lastOpened)
        f.shown.session.lock()
        f.clock.sleep(60.minutes)
        f.shown.biometric.refresh()
        f.shown.biometric.unlockWithFingerprint()

        assertEquals(typedAt.plusSeconds(3600), f.list.record(personal.id)?.lastOpened)
    }

    @Test
    fun anOpenAndAReloadCopyTheFilesNameAndColourIntoItsRecord() = runTest {
        // The Mac's "sessions: an open and a reload copy the file's name and colour into its record" (D7b).
        val f = Fixture(this)
        val family = f.add("/a/p.kdbx", fixtureBytes("names/family-shared"))
        assertEquals("p", family.displayName, "nothing read yet: the file name")

        typed(f, "pw")
        assertEquals("Family Shared", f.list.record(family.id)?.name, "its outer spaces aside")
        assertEquals("Family Shared", f.list.record(family.id)?.displayName)
        assertEquals("#FF8000", f.list.record(family.id)?.color, "as #RRGGBB")

        f.files.getValue("/a/p.kdbx").change(fixtureBytes("names/family"))
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals("Family", f.list.record(family.id)?.name, "renamed elsewhere: the file says")
        assertNull(f.list.record(family.id)?.color, "a colour taken out of the file goes")

        f.shown.session.lock()
        runCurrent()
        assertEquals("Family", f.list.record(family.id)?.displayName, "kept while it is locked")
    }

    @Test
    fun aFailedReloadAndAnotherChosenKeepTheNameAndColourLastRead() = runTest {
        val f = Fixture(this)
        val family = f.add("/a/p.kdbx", fixtureBytes("names/family-shared"))
        val work = f.add("/a/Work.kdbx")
        typed(f, "pw")

        f.files.getValue("/a/p.kdbx").change(byteArrayOf(1, 2, 3))
        advanceTimeBy(10.seconds)
        runCurrent()
        assertIs<SessionState.Unlocked>(f.shown.state()).let { assertNotNull(it.reloadFailure, "the reload failed") }
        assertEquals("Family Shared", f.list.record(family.id)?.name)
        assertEquals("#FF8000", f.list.record(family.id)?.color)

        f.databases.choose(work.id)
        runCurrent()
        typed(f, "pw")
        assertEquals("Family Shared", f.list.record(family.id)?.name, "kept for its lock screen and the sheet")
        assertEquals("#FF8000", f.list.record(family.id)?.color)
        assertNull(f.list.record(work.id)?.name, "the fixture names none")
    }

    @Test
    fun keePassXcsDefaultNameIsNoNameAndAColourNotWrittenAsKeePassDoesIsNone() = runTest {
        val f = Fixture(this)
        val w = f.add("/a/w.kdbx", fixtureBytes("names/passwoerter"))
        f.list.update(w.id) { it.copy(name = "Old", color = "#00FF00") }

        typed(f, "pw")

        assertNull(f.list.record(w.id)?.name, "KeePassXC's default name is no name")
        assertEquals("w", f.list.record(w.id)?.displayName, "the file name says it")
        assertNull(f.list.record(w.id)?.color, "\"red\" is not a colour KeePass writes")
    }

    // Remove from List

    @Test
    fun removingTheOpenOneLocksItFirstItsStoredPasswordGoesTheNextIsCurrentAndTheFileStays() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        f.storedPassword("pw", personal.id)
        f.storedPassword("pw", work.id)
        val first = f.shown
        typed(f, "pw")

        assertNull(f.databases.remove(personal.id))

        assertIs<SessionState.Locked>(first.state())
        assertEquals(listOf(work.id), f.list.records.map { it.id })
        assertEquals(work.id, f.shown.id)
        assertNull(f.stored(personal.id))
        assertNull(f.settings.string(BiometricSetup.lastTypedKey(personal.id)), "its days go too")
        assertEquals("pw", f.stored(work.id), "nobody else's")
        assertTrue(f.shown.biometric.state.value.enabled, "the app's switch stays on")
        assertNotNull(f.files.getValue("/a/Personal.kdbx").content, "the file is not touched")
    }

    @Test
    fun removingTheLastLeavesNoneAndAFailedDeleteIsSaid() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        f.storedPassword("pw")
        f.passwords.deleteError = IOException("Keystore busy")

        assertEquals("Keystore busy", f.databases.remove(personal.id))

        assertNull(f.databases.current.value)
        assertTrue(f.list.records.isEmpty())
    }

    @Test
    fun removingOneThatIsNotShownChangesNothingElse() = runTest {
        val f = Fixture(this)
        f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        f.storedPassword("pw")
        val shown = f.shown
        typed(f, "pw")

        f.storedPassword("work-pw", work.id)

        assertNull(f.databases.remove(work.id))

        assertSame(shown, f.shown)
        assertIs<SessionState.Unlocked>(shown.state())
        assertNull(f.stored(work.id), "its own stored password goes")
        assertEquals("pw", f.stored(shown.id), "the shown one's stays")
    }

    @Test
    fun aLastOpenedTimeIsTheClocks() = runTest {
        val f = Fixture(this)
        f.add("/a/Personal.kdbx")
        typed(f, "pw")
        assertEquals(Instant.parse("2026-10-18T12:00:00Z"), f.list.current?.lastOpened?.let { Instant.ofEpochSecond(it.epochSecond) })
    }

    // A new database (plan AD7; the Mac's NewDatabaseWindowTests "After Create")

    /** The key a database was made with; this fixture's file opens with "pw". */
    private fun madeWith(text: String = "pw") = DerivedKey(DatabaseKey.of(Secret.of(text)))

    private suspend fun Fixture.create(
        path: String,
        key: DerivedKey = madeWith(),
        useBiometric: Boolean = false,
        keyFilePath: String? = null,
        lockedSince: Boolean = false,
        fingerprintChosen: Boolean = useBiometric,
    ): CurrentDatabase.Created {
        files[path] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val typed = Secret.of("pw")
        return databases.created(path, keyFilePath, useBiometric, key, typed, fingerprintChosen) { lockedSince }.also { typed.wipe() }
    }

    // spec: unlock.biometric/AC4
    @Test
    fun aDatabaseMadeWithTheFingerprintOfferedHasItChosenEitherWay() = runTest {
        val f = Fixture(this)
        val declined = f.create("/a/Declined.kdbx", fingerprintChosen = true)
        val notOffered = f.create("/a/NotOffered.kdbx")

        assertTrue(assertNotNull(f.list.record(declined.id)).fingerprintAsked, "unchecked on the form: no, not asked again")
        assertFalse(assertNotNull(f.list.record(notOffered.id)).fingerprintAsked, "the app's switch off: never offered")
    }

    @Test
    fun createdItJoinsTheListIsCurrentOpensAndItsPasswordIsStoredForTheFingerprint() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val before = f.shown
        f.setup.setEnabled(true)
        val created = f.create("/a/Family Shared.kdbx", useBiometric = true, keyFilePath = "/keys/family.keyx")
        runCurrent()
        val record = assertNotNull(f.list.record(created.id))
        assertEquals("/a/Family Shared.kdbx", record.path)
        assertEquals("/keys/family.keyx", record.keyFilePath)
        assertTrue(record.useBiometric)
        assertEquals(created.id, f.list.currentId)
        assertEquals(created.id, f.shown.id)
        assertEquals(UnlockOutcome.Opened, created.opened?.await())
        assertIs<SessionState.Unlocked>(f.shown.state())
        assertEquals("pw", f.stored(created.id), "a typed unlock: stored for the fingerprint")
        assertNotNull(f.settings.string(BiometricSetup.lastTypedKey(created.id)), "its days start")
        assertFalse(before.session.isClosed, "nothing closes: the one shown before stays in the window")
        assertEquals(listOf(personal.id, created.id), f.sessions.windowIds)
        assertNull(created.problem)
    }

    @Test
    fun createdWhileAnotherIsOpenThatOneStaysOpen() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val before = f.shown
        typed(f, "pw")
        advanceTimeBy(1.seconds)
        val created = f.create("/a/New.kdbx")
        runCurrent()
        assertEquals(UnlockOutcome.Opened, created.opened?.await())
        assertIs<SessionState.Unlocked>(before.state())
        assertEquals(listOf(personal.id, created.id), f.sessions.openIds)
        assertEquals(created.id, f.shown.id)
    }

    @Test
    fun createdWithoutTheFingerprintNothingIsStored() = runTest {
        val f = Fixture(this)
        f.setup.setEnabled(true)
        val created = f.create("/a/New.kdbx", useBiometric = false)
        runCurrent()
        assertEquals(UnlockOutcome.Opened, created.opened?.await())
        assertNull(f.stored(created.id))
        assertFalse(f.list.record(created.id)!!.useBiometric)
    }

    @Test
    fun createdWhereADatabaseOfTheListWasNotFoundThatOneGoesWithItsStoredPassword() = runTest {
        val f = Fixture(this)
        f.add("/a/Personal.kdbx")
        val old = f.add("/a/Old.kdbx", bytes = null)
        f.storedPassword("old-pw", old.id)
        f.settings.setString(BiometricSetup.lastTypedKey(old.id), "1")
        val created = f.create("/a/Old.kdbx")
        runCurrent()
        assertTrue(created.id != old.id, "a new database, not the old record")
        assertNull(f.list.record(old.id))
        assertNull(f.stored(old.id), "the old database's stored password is gone")
        assertNull(f.settings.string(BiometricSetup.lastTypedKey(old.id)), "and its days")
        assertFalse(f.list.record(created.id)!!.useBiometric)
        assertEquals(UnlockOutcome.Opened, created.opened?.await())
        assertEquals(2, f.list.records.size)
    }

    @Test
    fun createdWhereTheOpenDatabasesFileWasDeletedThatOneClosesAndGoesTheNewOneOpensWithItsOwnKey() = runTest {
        val f = Fixture(this)
        val old = f.add("/a/Old.kdbx")
        typed(f, "pw")
        advanceTimeBy(1.seconds)
        val oldSession = f.shown
        assertIs<SessionState.Unlocked>(oldSession.state())
        val created = f.create("/a/Old.kdbx")
        runCurrent()
        assertTrue(oldSession.session.isClosed)
        assertIs<SessionState.Locked>(oldSession.state(), "its vault is wiped")
        assertNull(f.list.record(old.id))
        assertEquals(UnlockOutcome.Opened, created.opened?.await())
        assertEquals(listOf(created.id), f.list.records.map { it.id })
    }

    @Test
    fun aLockSinceCreateWasTappedLeavesItInTheListLockedItsKeyWipedNothingStored() = runTest {
        val f = Fixture(this)
        f.setup.setEnabled(true)
        val key = madeWith()
        val created = f.create("/a/New.kdbx", key = key, useBiometric = true, lockedSince = true)
        runCurrent()
        assertNull(created.opened)
        assertEquals(created.id, f.list.currentId)
        assertIs<SessionState.Locked>(f.shown.state())
        assertFailsWith<IllegalStateException>("the key is wiped") { key.key.copy() }
        assertNull(f.stored(created.id))
    }

    @Test
    fun madeByTheRealCreatorTheFirstOpeningDerivesNothing() = runTest {
        val f = Fixture(this)
        var written: ByteArray? = null
        val place = object : NewFile {
            override val fileName = "Family Shared.kdbx"
            override fun check() = NewFilePlace.FREE
            override fun create(bytes: ByteArray, readOnly: Boolean) {
                written = bytes.copyOf()
            }
            override fun removeCreated() = Unit
        }
        val measured = MeasuredEncryption(DatabaseEncryption(Cipher.CHACHA20, KeyDerivation.ARGON2D, 1, 1, 1.0), units = 2, seconds = 0.1)
        val typed = Secret.of("pw-family")
        val key = DatabaseCreator(now = { Instant.EPOCH }).create(place, "Family Shared", typed, null, measured)
        val path = "/a/Family Shared.kdbx"
        f.files[path] = FakeStore(assertNotNull(written))
        val created = f.databases.created(path, null, false, key, typed) { false }
        typed.wipe()
        runCurrent()
        assertEquals(UnlockOutcome.Opened, created.opened?.await())
        val unlocked = assertIs<SessionState.Unlocked>(f.shown.state())
        assertEquals("Family Shared", unlocked.vault.name)
        assertNull(unlocked.keyDerivation, "the creator's key, its derived key kept: nothing derived again")

        f.shown.session.lock()
        assertTrue(typed(f, "pw-family"))
        advanceTimeBy(1.seconds)
        assertNotNull(assertIs<SessionState.Unlocked>(f.shown.state()).keyDerivation, "a typed password derives")
    }

    // Folders the user granted (keething-spec databases.saf AC9, AC13, AC14)

    private val sync = StoragePlace(StoragePlace.PRIMARY, "Sync")

    private fun Fixture.addInFolder(file: FileLocation.InFolder): DatabaseRecord {
        files[key(file)] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        return list.add(file)
    }

    @Test
    fun aKeyFileChosenAloneInAFolderTheListReachesIsReachedThroughItAndItsOwnGrantIsOffered() = runTest {
        val f = Fixture(this)
        val personal = f.addInFolder(FileLocation.InFolder("grant-sync", sync, "KeePass/Personal.kdbx"))

        f.databases.setKeyFile(personal.id, FileLocation.Granted("grant-key", StoragePlace("primary", "sync/Keys/personal.keyx")))

        assertEquals(FileLocation.InFolder("grant-sync", sync, "Keys/personal.keyx"), f.list.record(personal.id)?.keyFile)
        assertEquals(listOf("grant-key"), f.offered, "given back: the list does not take it")

        val outside = FileLocation.Granted("grant-stick", StoragePlace("1234-5678", "keys/personal.keyx"))
        f.databases.setKeyFile(personal.id, outside)
        assertEquals(outside, f.list.record(personal.id)?.keyFile, "in no folder of the list: its own grant")
    }

    @Test
    fun anOldRecordsFolderMustHoldItsFileAndThenReachesItAndItsKeyFileWithEverythingElseKept() = runTest {
        val f = Fixture(this)
        val old = f.add(
            "/storage/emulated/0/Sync/KeePass/Personal.kdbx",
            keyFile = "/storage/emulated/0/Sync/Keys/personal.keyx" to keyFileBytes("keyx.keyx"),
        )
        f.list.update(old.id) { it.copy(name = "Mine", color = "#112233", useBiometric = true, opensWithoutPassword = true) }
        assertEquals(old.id, f.shown.id)
        val before = f.list.record(old.id)!!

        assertEquals("That folder does not hold Personal.kdbx.", f.databases.folderGranted(old.id, "grant-download", StoragePlace("primary", "Download")))
        assertEquals(before, f.list.record(old.id), "nothing changed")
        assertEquals(
            "That folder does not hold Personal.kdbx.",
            f.databases.folderGranted(old.id, "grant-keepass", StoragePlace("primary", "Sync/KeePass/Personal.kdbx")),
            "the file itself is no folder",
        )

        assertNull(f.databases.folderGranted(old.id, "grant-sync", StoragePlace("primary", "sync")))

        val after = f.list.record(old.id)!!
        assertEquals(FileLocation.InFolder("grant-sync", StoragePlace("primary", "sync"), "KeePass/Personal.kdbx"), after.file)
        assertEquals(FileLocation.InFolder("grant-sync", StoragePlace("primary", "sync"), "Keys/personal.keyx"), after.keyFile, "asked once: its key file too")
        assertEquals(before.copy(file = after.file, keyFile = after.keyFile), after, "its id, name, colour and choices")
        assertEquals(listOf("grant-download", "grant-keepass", "grant-sync"), f.offered)
    }

    @Test
    fun anOldRecordsKeyFileInNoFolderOfTheListStaysByItsPath() = runTest {
        val f = Fixture(this)
        val old = f.add("/storage/emulated/0/Sync/Personal.kdbx", keyFile = "/storage/1234-5678/personal.keyx" to keyFileBytes("keyx.keyx"))

        assertNull(f.databases.folderGranted(old.id, "grant-sync", sync))

        assertEquals(FileLocation.InFolder("grant-sync", sync, "Personal.kdbx"), f.list.record(old.id)?.file)
        assertEquals(FileLocation.ByPath("/storage/1234-5678/personal.keyx"), f.list.record(old.id)?.keyFile, "Choose key file… then (AC10)")
    }

    @Test
    fun everyOldRecordAFolderOfTheListHoldsIsReachedThroughItWithNothingAsked() = runTest {
        // spec: databases.saf/AC13
        val f = Fixture(this)
        val personal = f.add("/storage/emulated/0/Sync/KeePass/Personal.kdbx")
        val work = f.add("/storage/emulated/0/Sync/KeePass/Work.kdbx")
        val crypto = f.add("/storage/emulated/0/Download/Crypto.kdbx", keyFile = "/storage/emulated/0/Sync/Keys/crypto.keyx" to keyFileBytes("keyx.keyx"))
        f.list.update(work.id) { it.copy(name = "Job", useBiometric = true) }
        val workBefore = f.list.record(work.id)!!

        assertNull(f.databases.folderGranted(personal.id, "grant-sync", sync))

        assertEquals(FileLocation.InFolder("grant-sync", sync, "KeePass/Work.kdbx"), f.list.record(work.id)?.file, "not asked again")
        assertEquals(workBefore.copy(file = f.list.record(work.id)!!.file), f.list.record(work.id), "its id, name and choices")
        assertEquals(FileLocation.InFolder("grant-sync", sync, "Keys/crypto.keyx"), f.list.record(crypto.id)?.keyFile)
        assertEquals(FileLocation.ByPath("/storage/emulated/0/Download/Crypto.kdbx"), f.list.record(crypto.id)?.file, "in no folder of the list")
    }

    @Test
    fun aDatabaseOpenedThroughAFolderTakesTheOldRecordsItHolds() = runTest {
        val f = Fixture(this)
        val old = f.add("/storage/emulated/0/Sync/KeePass/Personal.kdbx")
        f.databases.adoptOldRecords()
        assertEquals(FileLocation.ByPath("/storage/emulated/0/Sync/KeePass/Personal.kdbx"), f.list.record(old.id)?.file, "no folder holds it yet")

        val family = FileLocation.InFolder("grant-keepass", StoragePlace("primary", "Sync/KeePass"), "Family.kdbx")
        f.files[key(family)] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        f.databases.open(family)

        assertEquals(FileLocation.InFolder("grant-keepass", StoragePlace("primary", "Sync/KeePass"), "Personal.kdbx"), f.list.record(old.id)?.file)
        assertEquals(setOf("grant-keepass"), f.list.grantsInUse)
    }

    @Test
    fun anOldRecordsOwnFileOpenedThroughAFolderIsThatRecordReachedThroughIt() = runTest {
        // spec: databases.saf/AC13
        val f = Fixture(this)
        val old = f.add("/storage/emulated/0/Sync/KeePass/Personal.kdbx", keyFile = "/storage/emulated/0/Sync/Keys/personal.keyx" to keyFileBytes("keyx.keyx"))
        f.list.update(old.id) { it.copy(useBiometric = true) }
        val there = FileLocation.InFolder("grant-sync", sync, "KeePass/personal.kdbx")

        assertEquals(old.id, f.databases.open(there))

        assertEquals(there, f.list.record(old.id)?.file, "not kept by its path any more")
        assertEquals(FileLocation.InFolder("grant-sync", sync, "Keys/personal.keyx"), f.list.record(old.id)?.keyFile)
        assertEquals(true, f.list.record(old.id)?.useBiometric)
        assertEquals(setOf("grant-sync"), f.list.grantsInUse, "the grant is taken")
    }

    @Test
    fun anOldRecordWhoseFolderCannotBeGivenIsLocatedWithEverythingKept() = runTest {
        // spec: databases.saf/AC13 (a database directly in Download: Android gives no app Download itself)
        val f = Fixture(this)
        val old = f.add("/storage/emulated/0/Download/Personal.kdbx")
        f.list.update(old.id) { it.copy(useBiometric = true, name = "Mine") }
        val moved = FileLocation.InFolder("grant-keepass", StoragePlace("primary", "Download/KeePass"), "Personal.kdbx")
        f.files[key(moved)] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))

        assertNull(f.databases.locate(old.id, moved))

        assertEquals(moved, f.list.record(old.id)?.file)
        assertEquals("Mine", f.list.record(old.id)?.name)
        assertEquals(true, f.list.record(old.id)?.useBiometric)
    }

    @Test
    fun anOldRecordOffThePhonesStorageCannotBeGivenAFolder() = runTest {
        val f = Fixture(this)
        val odd = f.add("/a/Personal.kdbx")
        assertEquals("Personal.kdbx is not on the phone's storage.", f.databases.folderGranted(odd.id, "grant-sync", sync))
        assertEquals(FileLocation.ByPath("/a/Personal.kdbx"), f.list.record(odd.id)?.file)
    }

    @Test
    fun theSameFileThroughAnotherGrantIsTheDatabaseThereIsAndCannotBeLocatedTwice() = runTest {
        val f = Fixture(this)
        val personal = f.addInFolder(FileLocation.InFolder("grant-sync", sync, "KeePass/Personal.kdbx"))
        val crypto = f.add("/storage/emulated/0/Crypto.kdbx", bytes = null)

        assertEquals(personal.id, f.databases.open(FileLocation.InFolder("grant-keepass", StoragePlace("primary", "Sync/KeePass"), "personal.kdbx")))
        assertEquals(2, f.list.records.size)
        assertEquals(
            "That file is already in the list as “Personal”.",
            f.databases.locate(crypto.id, FileLocation.InFolder("grant-top", StoragePlace("primary", ""), "Sync/KeePass/Personal.kdbx")),
        )
        assertEquals(listOf("grant-keepass", "grant-top"), f.offered, "neither taken: both given back")
    }

    @Test
    fun locatingIntoAGrantedFolderStartsTheSessionOfTheFileThere() = runTest {
        val f = Fixture(this)
        val crypto = f.add("/storage/1234-5678/Crypto.kdbx", bytes = null)
        val there = FileLocation.InFolder("grant-sync", sync, "Crypto.kdbx")
        f.files[key(there)] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val before = f.shown

        assertNull(f.databases.locate(crypto.id, there))

        assertEquals(there, f.list.record(crypto.id)?.file)
        assertTrue(f.shown !== before, "a new session for the file there")
        assertEquals(there, f.shown.location)
        assertTrue(typed(f, "pw"))
    }

    @Test
    fun aNewDatabaseOverAGoneOnesFileKeepsItsFolderGrantAndItsKeyFileGoesThroughTheFolder() = runTest {
        val f = Fixture(this)
        val grants = object : Grants {
            val givenBack = mutableListOf<String>()

            override fun held(): Set<String> = setOf("grant-sync", "grant-key")

            override fun giveBack(grant: String) {
                givenBack += grant
            }
        }
        val notesFolder = java.nio.file.Files.createTempDirectory("current-database-test").toFile()
        // The keeper's io runs at once, as a thread of its own may while the main thread waits in Create.
        f.keeper = GrantKeeper(f.list, grants, SaveNotes(notesFolder), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val file = FileLocation.InFolder("grant-sync", sync, "Personal.kdbx")
        val gone = f.addInFolder(file)
        f.storedPassword("old")
        f.files[key(file)] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))

        val typed = Secret.of("pw")
        val created = f.databases.created(
            FileLocation.InFolder("grant-sync", sync, "personal.kdbx"),
            FileLocation.Granted("grant-key", StoragePlace("primary", "Sync/Keys/personal.keyx")),
            useBiometric = false,
            key = madeWith(),
            password = typed,
        ) { false }
        typed.wipe()
        runCurrent()

        val record = f.list.record(created.id)!!
        assertTrue(created.id != gone.id, "another database")
        assertEquals(listOf(created.id), f.list.records.map { it.id })
        assertEquals(FileLocation.InFolder("grant-sync", sync, "Keys/personal.keyx"), record.keyFile, "through the folder the list reaches (AC9)")
        assertEquals(listOf("grant-key"), grants.givenBack, "the folder's grant stays; the key file's own goes")
        assertEquals(listOf("grant-sync", "grant-key"), f.offered)
        assertNull(f.stored(gone.id), "the gone one's stored password went")
        notesFolder.deleteRecursively()
    }
}

