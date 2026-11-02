package dev.keething.vault.databases

import dev.keething.domain.storage.FakeGrantedFolder
import dev.keething.domain.storage.FakeGrantedFolder.Operation
import dev.keething.domain.storage.Grants
import dev.keething.domain.storage.ProcessEnded
import dev.keething.domain.storage.StoragePlace
import dev.keething.domain.time.Clock
import dev.keething.vault.biometric.MapSettings
import dev.keething.vault.storage.FolderDatabaseStore
import dev.keething.vault.storage.SaveNotes
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A grant nothing of the list needs any more is given back (keething-spec databases.saf AC14). */
class GrantKeeperTest {
    private class FakeGrants(vararg held: String) : Grants {
        val held = held.toMutableSet()
        val givenBack = mutableListOf<String>()

        override fun held(): Set<String> = held.toSet()

        override fun giveBack(grant: String) {
            held -= grant
            givenBack += grant
        }
    }

    private val appStorage: File = Files.createTempDirectory("grant-keeper-test").toFile()
    private val notes = SaveNotes(File(appStorage, "saves"))
    private val sync = StoragePlace(StoragePlace.PRIMARY, "Sync")
    private val keePass = StoragePlace(StoragePlace.PRIMARY, "Sync/KeePass")

    @AfterTest
    fun deleteAppStorage() {
        appStorage.deleteRecursively()
    }

    private class Fixture(scope: TestScope, val grants: FakeGrants, notes: SaveNotes) {
        private var ids = 0
        val list = DatabaseList(MapSettings(), makeId = { DatabaseId("db-${++ids}") })
        val keeper = GrantKeeper(list, grants, notes, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler))
    }

    private fun TestScope.fixture(vararg held: String) = Fixture(this, FakeGrants(*held), notes)

    @Test
    fun aFolderIsGivenBackWhenItsLastDatabaseOrKeyFileLeavesTheList() = runTest {
        val f = fixture("grant-sync", "grant-key")
        val personal = f.list.add(FileLocation.InFolder("grant-sync", sync, "Personal.kdbx"))
        val work = f.list.add(FileLocation.InFolder("grant-sync", sync, "Work.kdbx"))
        f.list.update(work.id) { it.copy(keyFile = FileLocation.Granted("grant-key", StoragePlace("primary", "Download/w.keyx"))) }

        f.list.remove(personal.id)
        runCurrent()
        assertEquals(emptyList(), f.grants.givenBack, "Work still needs its folder")

        f.list.update(work.id) { it.copy(keyFile = null) }
        runCurrent()
        assertEquals(listOf("grant-key"), f.grants.givenBack, "a key file changed")

        f.list.update(work.id) { it.copy(file = FileLocation.ByPath("/storage/emulated/0/Work.kdbx")) }
        runCurrent()
        assertEquals(listOf("grant-key", "grant-sync"), f.grants.givenBack, "located elsewhere")
    }

    @Test
    fun aKeyFileInTheDatabasesFolderKeepsTheFolderAfterTheDatabaseLeaves() = runTest {
        val f = fixture("grant-sync")
        val personal = f.list.add(FileLocation.InFolder("grant-sync", sync, "Personal.kdbx"))
        val work = f.list.add(FileLocation.ByPath("/storage/emulated/0/Work.kdbx"))
        f.list.update(work.id) { it.copy(keyFile = FileLocation.InFolder("grant-sync", sync, "work.keyx")) }

        f.list.remove(personal.id)
        runCurrent()

        assertEquals(emptyList(), f.grants.givenBack)
    }

    @Test
    fun aGrantOfferedThatTheListDidNotTakeGoesAtItsNextChangeOneItTookStays() = runTest {
        val f = fixture("grant-sync", "grant-keepass", "grant-key")
        val personal = f.list.add(FileLocation.InFolder("grant-sync", sync, "KeePass/Personal.kdbx"))
        runCurrent()

        f.keeper.offered("grant-keepass")
        f.list.add(FileLocation.InFolder("grant-keepass", keePass, "Personal.kdbx")) // the same file: no change
        f.keeper.offered("grant-key")
        f.list.update(personal.id) { it.copy(keyFile = FileLocation.Granted("grant-key", StoragePlace("primary", "Download/p.keyx"))) }
        runCurrent()

        assertEquals(listOf("grant-keepass"), f.grants.givenBack)
        assertEquals(setOf("grant-sync", "grant-key"), f.grants.held)
    }

    @Test
    fun aGrantTheListTookAgainBeforeItWasGivenBackStays() = runTest {
        val f = fixture("grant-sync")
        val personal = f.list.add(FileLocation.InFolder("grant-sync", sync, "Personal.kdbx"))
        runCurrent()

        f.list.remove(personal.id)
        f.list.add(FileLocation.InFolder("grant-sync", sync, "Personal.kdbx"))
        runCurrent()

        assertEquals(emptyList(), f.grants.givenBack)
    }

    @Test
    fun aGrantOfferedThenGivenAgainInAPickerIsNotGivenBackUntilOfferedAgain() = runTest {
        // spec: databases.saf/AC14
        val f = fixture("grant-sync", "grant-keepass")
        val personal = f.list.add(FileLocation.InFolder("grant-sync", sync, "Personal.kdbx"))
        runCurrent()

        f.keeper.offered("grant-keepass") // a form closed without using it, no change of the list since
        f.keeper.taken("grant-keepass") // the same folder chosen in the next form
        f.list.update(personal.id) { it.copy(name = "Mine") }
        runCurrent()
        assertEquals(emptyList(), f.grants.givenBack, "the form still uses it")

        f.keeper.offered("grant-keepass")
        f.list.update(personal.id) { it.copy(name = "Personal") }
        runCurrent()
        assertEquals(listOf("grant-keepass"), f.grants.givenBack)
    }

    @Test
    fun theSweepAtTheStartGivesBackEveryGrantHeldThatTheListDoesNotUse() = runTest {
        val f = fixture("grant-sync", "grant-left-in-a-picker", "grant-key")
        f.list.add(FileLocation.InFolder("grant-sync", sync, "Personal.kdbx")).also { personal ->
            f.list.update(personal.id) { it.copy(keyFile = FileLocation.Granted("grant-key", StoragePlace("primary", "Download/p.keyx"))) }
        }
        runCurrent()

        f.keeper.sweep()

        assertEquals(listOf("grant-left-in-a-picker"), f.grants.givenBack)
    }

    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now

        override fun elapsed(): kotlin.time.Duration = kotlin.time.Duration.ZERO
    }
    private val folder = FakeGrantedFolder("grant-sync", now = { now })

    /** A save of Personal.kdbx stopped before its new file was renamed in: left to put right at its next read. */
    private fun stoppedSave() {
        folder.put("Personal.kdbx", "old".encodeToByteArray(), now.minusSeconds(10))
        folder.beforeCall = { if (it.operation == Operation.RENAME && it.name == "Personal.kdbx") throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { FolderDatabaseStore(folder, "Personal.kdbx", notes, clock, sleep = {}).replace("new".encodeToByteArray()) }
        folder.beforeCall = {}
    }

    @Test
    fun aDatabaseLeavingTheListTakesTheNoteOfASavePutRightAndNotYetSaidAndItsFolderGoes() = runTest {
        stoppedSave()
        FolderDatabaseStore(folder, "Personal.kdbx", notes, clock, sleep = {}).read()
        val f = fixture("grant-sync")
        val personal = f.list.add(FileLocation.InFolder("grant-sync", sync, "Personal.kdbx"))
        runCurrent()

        f.list.remove(personal.id)
        runCurrent()

        assertTrue(notes.left("grant-sync", "Personal.kdbx").isEmpty(), "nothing to say it of")
        assertEquals(listOf("grant-sync"), f.grants.givenBack)
    }

    @Test
    fun aDatabaseLeavingTheListTakesItsSettledNotesAndAFolderASaveMustStillPutRightKeepsItsGrant() = runTest {
        stoppedSave()
        val f = fixture("grant-sync")
        val personal = f.list.add(FileLocation.InFolder("grant-sync", sync, "Personal.kdbx"))
        runCurrent()

        f.list.remove(personal.id)
        runCurrent()
        assertEquals(emptyList(), f.grants.givenBack, "the folder still needs putting right")
        assertEquals(1, notes.left("grant-sync", "Personal.kdbx").size)

        // The next start: the save put right, then the sweep.
        FolderDatabaseStore(folder, "Personal.kdbx", notes, clock, sleep = {}).read()
        f.keeper.sweep()
        assertEquals(listOf("grant-sync"), f.grants.givenBack)
        assertTrue(notes.left("grant-sync", "Personal.kdbx").isEmpty(), "put right and nothing to say: no database to say it of")
    }
}
