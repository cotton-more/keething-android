package dev.keething.vault.databases

import dev.keething.domain.secret.Secret
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.biometric.FakePasswordStore
import dev.keething.vault.biometric.MapSettings
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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The current database and its session (plan AD4): the Mac's D4 `DatabaseListModelTests` for choosing, opening,
 * locating, a key file and Remove from List, with the phone's one stored password of before AD5.
 */
class CurrentDatabaseTest {
    private class Fixture(scope: TestScope, val settings: MapSettings = MapSettings()) {
        val list = DatabaseList(settings, makeId = { DatabaseId("db-${++ids}") })
        val passwords = FakePasswordStore()
        val clock = FakeClock(scope.testScheduler)

        /** The files there are, by path. */
        val files = mutableMapOf<String, FakeStore>()
        val keyFiles = mutableMapOf<String, FakeKeyFile>()
        private val io = StandardTestDispatcher(scope.testScheduler)
        private var ids = 0

        private val factory = SessionFactory { record, number, keyFilePath, _ ->
            val store = files.getOrPut(record.path) { FakeStore(null) }
            val session = VaultSession(
                store = store,
                opener = VaultOpener(),
                backups = { _, _, _ -> },
                clock = clock,
                scope = scope.backgroundScope,
                io = io,
                lockTimeout = { 5.minutes },
                keyFile = { keyFilePath()?.let { keyFiles.getOrPut(it) { FakeKeyFile(null, File(it).name) } } },
            )
            val biometric = BiometricUnlock(session, passwords, settings, clock, scope.backgroundScope, io)
            DatabaseSession(number, record.id, File(record.path), store, session, biometric)
        }

        val databases: CurrentDatabase by lazy { CurrentDatabase(list, scope.backgroundScope, factory, now = clock::now) }

        val shown: DatabaseSession get() = assertNotNull(databases.current.value)

        /** A database of the list whose file opens with "pw". */
        fun add(path: String, bytes: ByteArray? = fixtureBytes("kdbx4-aes-aeskdf"), keyFile: Pair<String, ByteArray?>? = null): DatabaseRecord {
            files[path] = FakeStore(bytes)
            val record = list.add(path)
            keyFile?.let { (keyPath, content) ->
                keyFiles[keyPath] = FakeKeyFile(content, File(keyPath).name)
                list.update(record.id) { it.copy(keyFilePath = keyPath) }
            }
            return list.record(record.id)!!
        }

        fun storedPassword(text: String) {
            settings.setString("biometricUnlock", "on")
            passwords.stored = Secret.of(text)
        }
    }

    private fun TestScope.typed(f: Fixture, text: String, database: DatabaseSession = f.shown): Boolean {
        val typed = Secret.of(text)
        val started = f.databases.unlock(database, typed)
        typed.wipe()
        runCurrent()
        return started
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
    fun theFirstDatabaseOpenedIntoAnEmptyListStartsWithoutTheStoredPasswordOfBefore() = runTest {
        val f = Fixture(this)
        f.storedPassword("pw")
        assertNull(f.databases.current.value)
        f.files["/storage/emulated/0/Other.kdbx"] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))

        f.databases.open("/storage/emulated/0/Other.kdbx")

        assertFalse(f.shown.biometric.state.value.enabled, "off before its lock screen reads it")
        assertNull(f.settings.string("biometricUnlock"))
        runCurrent()
        assertNull(f.passwords.stored, "it belongs to no database of the list")
    }

    // Choosing another (the lock screen's sheet)

    @Test
    fun anotherChosenIsShownLockedAndNothingIsSaidOfTheLastOne() = runTest {
        val f = Fixture(this)
        f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        val first = f.shown
        typed(f, "typo")
        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongPassword)), first.state())

        f.databases.choose(work.id)

        assertEquals(work.id, f.list.currentId)
        assertEquals(work.id, f.shown.id)
        assertEquals(2, f.shown.number)
        assertEquals(SessionState.Locked(LockReason.Initial), f.shown.state(), "the wrong password was the other one's")
        assertNull(first.session.unlock(Secret.of("pw")), "the one shown before opens no more")

        val same = f.shown
        f.databases.choose(work.id)
        assertSame(same, f.shown, "the same one again changes nothing")
        f.databases.choose(DatabaseId("not in the list"))
        assertSame(same, f.shown)
    }

    @Test
    fun anotherChosenWhileOpenLocksItFirst() = runTest {
        val f = Fixture(this)
        f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        val first = f.shown
        typed(f, "pw")
        assertIs<SessionState.Unlocked>(first.state())

        f.databases.choose(work.id)

        assertIs<SessionState.Locked>(first.state())
    }

    @Test
    fun anotherChosenDeletesTheStoredPasswordAndTurnsTheSwitchOffAsBefore() = runTest {
        val f = Fixture(this)
        f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        f.storedPassword("pw")

        f.databases.choose(work.id)

        assertFalse(f.shown.biometric.state.value.enabled, "the next session starts with it off")
        runCurrent()
        assertNull(f.passwords.stored, "the stored password opens the other database (one until plan AD5)")
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
        assertEquals("pw", f.passwords.stored?.reveal(), "the same database: its stored password stays")
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
        assertEquals("on", f.settings.string("biometricKeyFileChanged"), "a stored password rejected before the next unlock is kept")

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
    fun anUnlockEndedByChoosingAnotherOrWhoseKeyFileChangedMeanwhileTeachesNothing() = runTest {
        // Choosing another closes the session, so that unlock ends Interrupted; the guard on the current database
        // is for several open at once (plan AD8), where a database stops being shown without its session closing.
        val f = Fixture(this)
        val keys = f.add("/k/Keys.kdbx", bytes = keyFileBytes("keyfile-only.kdbx"), keyFile = "/k/keyx.keyx" to keyFileBytes("keyx.keyx"))
        val other = f.add("/o/Other.kdbx")

        val typed = Secret.empty()
        assertTrue(f.databases.unlock(f.shown, typed))
        f.databases.choose(other.id)
        runCurrent()
        assertFalse(f.list.record(keys.id)!!.opensWithoutPassword)

        f.databases.choose(keys.id)
        assertTrue(f.databases.unlock(f.shown, Secret.empty()))
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
        assertFalse(f.databases.unlock(f.shown, Secret.of("pw")), "open already: nothing started")
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

    // Remove from List

    @Test
    fun removingTheOpenOneLocksItFirstItsStoredPasswordGoesTheNextIsCurrentAndTheFileStays() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        f.storedPassword("pw")
        val first = f.shown
        typed(f, "pw")

        assertNull(f.databases.remove(personal.id))

        assertIs<SessionState.Locked>(first.state())
        assertEquals(listOf(work.id), f.list.records.map { it.id })
        assertEquals(work.id, f.shown.id)
        assertNull(f.passwords.stored)
        assertFalse(f.shown.biometric.state.value.enabled)
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

        assertNull(f.databases.remove(work.id))

        assertSame(shown, f.shown)
        assertIs<SessionState.Unlocked>(shown.state())
        assertEquals("pw", f.passwords.stored?.reveal(), "the stored password is the shown one's")
    }

    @Test
    fun aLastOpenedTimeIsTheClocks() = runTest {
        val f = Fixture(this)
        f.add("/a/Personal.kdbx")
        typed(f, "pw")
        assertEquals(Instant.parse("2026-10-18T12:00:00Z"), f.list.current?.lastOpened?.let { Instant.ofEpochSecond(it.epochSecond) })
    }
}
