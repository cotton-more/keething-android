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
            val biometric = BiometricUnlock(record.id, session, setup, list, scope.backgroundScope, watching, fileExists = { true })
            DatabaseSession(number, record.id, File(record.path), store, session, biometric)
        }

        val databases: CurrentDatabase by lazy { CurrentDatabase(list, scope.backgroundScope, factory, setup, now = clock::now) }

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
    fun anotherChosenWhileTheFingerprintIsAskedOpensNothingAndDeletesNothing() = runTest {
        val f = Fixture(this)
        val personal = f.add("/a/Personal.kdbx")
        val work = f.add("/b/Work.kdbx")
        f.storedPassword("old-pw", personal.id)
        val first = f.shown
        first.biometric.refresh()
        val finger = CompletableDeferred<Unit>()
        f.passwords.gate = finger
        val asking = async { first.biometric.unlockWithFingerprint() }
        runCurrent()

        f.databases.choose(work.id)
        finger.complete(Unit)

        assertEquals(FingerprintOutcome.Cancelled, asking.await())
        assertEquals("old-pw", f.stored(personal.id), "never tried, so never rejected")
        assertEquals(0, f.passwords.deletes)
        assertEquals(SessionState.Locked(LockReason.Initial), f.shown.state(), "Work is not opened with Personal's")
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
    ): CurrentDatabase.Created {
        files[path] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val typed = Secret.of("pw")
        return databases.created(path, keyFilePath, useBiometric, key, typed) { lockedSince }.also { typed.wipe() }
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
        assertTrue(before.session.isClosed, "one open at a time: the one shown before closes")
        assertNotNull(f.list.record(personal.id), "and stays in the list")
        assertNull(created.problem)
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
}
