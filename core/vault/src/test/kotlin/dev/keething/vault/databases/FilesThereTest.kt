package dev.keething.vault.databases

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.FakeGrantedFolder
import dev.keething.domain.storage.GrantedFile
import dev.keething.domain.storage.StoragePlace
import dev.keething.domain.text.english
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.biometric.FakePasswordStores
import dev.keething.vault.biometric.FingerprintOutcome
import dev.keething.vault.biometric.MapSettings
import dev.keething.vault.biometric.WindowFingerprint
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeClock
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.VaultSession
import dev.keething.vault.session.fixtureBytes
import dev.keething.vault.storage.FolderDatabaseStore
import dev.keething.vault.storage.KeyFiles
import dev.keething.vault.storage.SaveNotes
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Whether a database's file and key file are there, looked at through their grants ([FilesThere]), wired as the app's
 * `Sessions` wires it: the fingerprint of one database, the window's one fingerprint and the chooser's run, over a
 * granted folder (keething-spec unlock.key-file AC3, databases.chooser AC6; SPEC §5).
 */
class FilesThereTest {
    private val sync = StoragePlace(StoragePlace.PRIMARY, "Sync")

    private class KeyFile(override val id: String, var bytes: ByteArray?, var granted: Boolean = true) : GrantedFile {
        override fun read(): ByteArray? = if (granted) bytes else throw AccessDeniedException(id)
    }

    private inner class Fixture(scope: TestScope) {
        val folder = FakeGrantedFolder(id = "grant-sync")
        val keyFile = KeyFile("grant-key", "key".encodeToByteArray())
        private val folders = { grant: String -> folder.takeIf { grant == it.id } }
        private val grantedFiles = { grant: String -> keyFile.takeIf { grant == it.id } }
        private val io = StandardTestDispatcher(scope.testScheduler)
        val there = FilesThere(folders, grantedFiles, io)

        val settings = MapSettings()
        val clock = FakeClock(scope.testScheduler)
        private var ids = 0
        val list = DatabaseList(settings, makeId = { DatabaseId("db-${++ids}") })
        val stores = FakePasswordStores()
        val setup = BiometricSetup(stores, list, settings, clock, io)
        private val notes = SaveNotes(Files.createTempDirectory("saves").toFile())
        private val keyFiles = KeyFiles(folders, grantedFiles)

        private val factory = SessionFactory { record, number, keyFile, watching ->
            val file = record.file as FileLocation.InFolder
            val store = FolderDatabaseStore(folder, file.path, notes, clock, sleep = {})
            val session = VaultSession(store, VaultOpener(), { _, _, _ -> }, clock, scope.backgroundScope, io, keyFile = { keyFile()?.let(keyFiles::at) })
            val biometric = BiometricUnlock(record.id, session, setup, list, scope.backgroundScope, watching, fileExists = there::isThere, appLocks = { sessions.lockCount })
            DatabaseSession(number, record.id, record.file, store, session, biometric)
        }

        val sessions: VaultSessions by lazy { VaultSessions(list, scope.backgroundScope, factory, clock, lockTimeout = { 5.minutes }, now = clock::now) }
        val fingerprint: WindowFingerprint by lazy { WindowFingerprint(sessions, setup, list, fileExists = there::isThere) }
        val databases: CurrentDatabase by lazy { CurrentDatabase(list, scope.backgroundScope, sessions, setup) }

        /** A database in the granted folder that opens with "pw", in the window, its fingerprint on and "pw" stored. */
        fun add(name: String, keyFile: FileLocation? = null): DatabaseRecord {
            folder.put(name, fixtureBytes("kdbx4-aes-aeskdf"))
            val record = list.add(FileLocation.InFolder(folder.id, sync, name), useBiometric = true)
            list.update(record.id) { it.copy(keyFile = keyFile) }
            list.setInWindow(record.id, true)
            stores.stored[record.id.fileName] = Secret.of("pw")
            return assertNotNull(list.record(record.id))
        }

        fun state(record: DatabaseRecord): SessionState = assertNotNull(sessions.session(record.id)).session.state.value
    }

    private suspend fun TestScope.fixture(): Fixture = Fixture(this).also {
        it.setup.setEnabled(true)
        it.sessions
        runCurrent()
    }

    @Test
    fun aFileIsThereWhenItsGrantReachesItAndUnknownCountsAsThere() = runTest {
        val f = fixture()
        f.folder.put("Personal.kdbx", "x".encodeToByteArray())
        val inFolder = FileLocation.InFolder(f.folder.id, sync, "Personal.kdbx")
        val granted = FileLocation.Granted(f.keyFile.id, sync.resolve("work.keyx"))

        assertTrue(f.there.isThere(inFolder))
        assertTrue(f.there.isThere(granted))
        assertFalse(f.there.isThere(FileLocation.InFolder(f.folder.id, sync, "Gone.kdbx")), "not found")
        assertFalse(f.there.isThere(FileLocation.InFolder("grant-gone", sync, "Personal.kdbx")), "a grant the app does not hold")
        assertFalse(f.there.isThere(FileLocation.ByPath("/storage/emulated/0/Sync/Personal.kdbx")), "kept by its path: its folder first")
        f.keyFile.bytes = null
        assertFalse(f.there.isThere(granted), "the key file granted alone is gone")
        f.keyFile.granted = false
        assertFalse(f.there.isThere(granted), "its grant ended")
        f.folder.granted = false
        assertFalse(f.there.isThere(inFolder), "the folder's grant ended")
        f.folder.granted = true
        f.folder.beforeCall = { throw IOException("provider died") }
        assertTrue(f.there.isThere(inFolder), "nothing known: the read will say what is wrong")
    }

    // spec: unlock.key-file/AC3
    @Test
    fun noFingerprintIsAskedWhileTheGrantedKeyFileOrFileIsNotThere() = runTest {
        val f = fixture()
        val work = f.add("Work.kdbx", keyFile = FileLocation.InFolder(f.folder.id, sync, "work.keyx"))
        val biometric = assertNotNull(f.sessions.session(work.id)).biometric

        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.KeyFileMissing("work.keyx")), biometric.unlockWithFingerprint())
        f.list.update(work.id) { it.copy(keyFile = null) }
        f.folder.remove("Work.kdbx")
        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.Missing), biometric.unlockWithFingerprint())

        assertEquals(0, f.stores.prompts, "no prompt")
        assertEquals("pw", f.stores.stored[work.id.fileName]?.reveal(), "kept")
    }

    // spec: unlock.biometric-several/AC1
    @Test
    fun theWindowsFingerprintLeavesOutADatabaseWhoseGrantedKeyFileIsNotThereNeverReadingItsPassword() = runTest {
        val f = fixture()
        val personal = f.add("Personal.kdbx")
        val work = f.add("Work.kdbx", keyFile = FileLocation.Granted(f.keyFile.id, sync.resolve("work.keyx")))
        f.keyFile.bytes = null

        assertFalse(f.fingerprint.canUnlockAny(listOf(work.id)), "no offer for it alone")
        val result = f.fingerprint.unlock()
        runCurrent()

        assertEquals(FingerprintOutcome.UnlockFailed(OpenFailure.KeyFileMissing("work.keyx")), result.each[work.id])
        assertEquals(listOf(listOf(personal.id.fileName)), f.stores.severals, "its stored password not asked for")
        assertFalse(work.id.fileName in f.stores.read)
        assertEquals(listOf(personal.id), f.sessions.openIds)
    }

    // spec: databases.chooser/AC6
    @Test
    fun theRunSkipsByItselfADatabaseWhoseGrantedFileIsNotThere() = runTest {
        val f = fixture()
        val personal = f.add("Personal.kdbx")
        val work = f.add("Work.kdbx")
        val ends = mutableListOf<UnlockRun.Result?>()
        f.folder.remove("Personal.kdbx")

        val run = f.databases.run(listOf(personal.id, work.id), locks = { 0 }, fileExists = f.there::isThere)
        run.onEnd = { ends += it }
        run.start()
        runCurrent()

        assertEquals(work.id, run.current, "the one that is there asked")
        run.unlock(work.id, Secret.of("pw"))
        runCurrent()
        assertTrue(f.state(work) is SessionState.Unlocked)
        val result = assertNotNull(ends.single())
        assertEquals("1 of 2 open · Personal: not found", result.notice { assertNotNull(f.list.record(it)).displayName }.english())
    }
}
