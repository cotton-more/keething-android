package dev.keething.vault.storage

import dev.keething.domain.storage.DatabaseChangedException
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.FakeGrantedFolder
import dev.keething.domain.storage.FakeGrantedFolder.Call
import dev.keething.domain.storage.FakeGrantedFolder.Operation
import dev.keething.domain.storage.ProcessEnded
import dev.keething.domain.storage.StoppedSave
import dev.keething.domain.time.Clock
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The database through a granted folder (keething-spec databases.saf AC4–AC8; features/databases/saf/plan.md stage
 * S1), on a fake of the phone's storage provider that behaves as stage S0 measured it.
 */
class FolderDatabaseStoreTest {
    private val time = TestTime(Instant.parse("2026-11-02T12:00:00Z"))
    private val folder = FakeGrantedFolder(now = time::now)
    private val appStorage: File = Files.createTempDirectory("folder-store-test").toFile()
    private val notesDirectory = File(appStorage, "saves")
    private val store = storeAt(DATABASE)

    private val old = "the database before the save".encodeToByteArray()
    private val new = "the database the save writes, longer than before".encodeToByteArray()
    private val theirs = "the database a sync brought".encodeToByteArray()

    /** Long enough before the clock that a save does not wait. */
    private val earlier: Instant = time.now().minusSeconds(10)

    /** A store as a new process makes it: the notes read again from the app's storage. */
    private fun storeAt(path: String, sleep: (Duration) -> Unit = time::sleep) =
        FolderDatabaseStore(folder, path, SaveNotes(notesDirectory), time, sleep)

    @AfterTest
    fun deleteAppStorage() {
        appStorage.deleteRecursively()
    }

    private fun found(read: DatabaseRead): DatabaseRead.Found = assertIs<DatabaseRead.Found>(read)

    private fun mutations(): List<Call> = folder.calls.filter { it.operation in MUTATIONS }

    private fun isSyncthingsOwnTemporaryName(name: String) = name.startsWith(".syncthing.") && name.endsWith(".tmp")

    // Reading (AC2, AC8)

    @Test
    fun readsTheBytesWithTheVersionOfTheFileTheyWereReadFrom() {
        folder.put(DATABASE, old, earlier)

        val read = found(store.read())

        assertContentEquals(old, read.bytes)
        assertEquals(folder.versionOf(DATABASE), read.version)
        assertEquals(store.version(), read.version)
        assertEquals(old.size.toLong(), read.version.size)
        assertNull(read.stoppedSave)
        assertEquals("Personal.kdbx", store.fileName)
    }

    @Test
    fun aFileASyncReplacedKeepingItsSizeAndTimeIsAnotherVersion() {
        folder.put(DATABASE, old, earlier)
        val before = store.version()

        folder.put(DATABASE, old.copyOf(), earlier)

        assertNotEquals(before, store.version())
        assertEquals(folder.versionOf(DATABASE), store.version())
    }

    @Test
    fun noFileOrNoFolderIsMissingAndAGrantGoneIsAccessDenied() {
        assertEquals(DatabaseRead.Missing, store.read(), "no folder")
        folder.makeFolder("Sync")
        assertEquals(DatabaseRead.Missing, store.read(), "no file")
        assertNull(store.version())

        folder.put(DATABASE, old, earlier)
        folder.granted = false
        assertEquals(DatabaseRead.AccessDenied, store.read())
    }

    @Test
    fun aDatabaseChangedWhileItWasReadIsReadAgain() {
        folder.put(DATABASE, old, earlier)
        var changed = false
        folder.beforeCall = {
            if (it.operation == Operation.VERSION && !changed) {
                changed = true
                folder.put(DATABASE, theirs)
            }
        }

        val read = found(store.read())

        assertContentEquals(theirs, read.bytes)
        assertEquals(folder.versionOf(DATABASE), read.version)
    }

    @Test
    fun aDatabaseInAFolderInsideTheGrantIsReachedByItsPath() {
        val store = storeAt("Sync/KeePass/Personal.kdbx")
        folder.put("Sync/KeePass/Personal.kdbx", old, earlier)
        folder.put(DATABASE, theirs, earlier)

        assertContentEquals(old, found(store.read()).bytes)
        store.replace(new)

        assertContentEquals(new, folder.bytesOf("Sync/KeePass/Personal.kdbx"))
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync/KeePass"))
        assertContentEquals(theirs, folder.bytesOf(DATABASE))
    }

    // Saving: the rename aside (AC4)

    @Test
    fun aSaveWritesANewFileRenamesTheDatabaseAsideRenamesTheNewOneInAndDeletesTheOld() {
        folder.put(DATABASE, old, earlier)
        val before = store.version()

        store.replace(new)

        val (create, write, aside, renamedIn, delete) = mutations()
        val temporary = create.name!!
        val asideName = aside.name!!
        assertEquals(Call(Operation.CREATE, "Sync", temporary), create)
        assertEquals(Call(Operation.WRITE, "Sync/$temporary"), write)
        assertEquals(Call(Operation.RENAME, DATABASE, asideName), aside)
        assertEquals(Call(Operation.RENAME, "Sync/$temporary", "Personal.kdbx"), renamedIn)
        assertEquals(Call(Operation.DELETE, "Sync/$asideName"), delete)
        assertEquals(5, mutations().size)
        assertTrue(isSyncthingsOwnTemporaryName(temporary), temporary)
        assertTrue(isSyncthingsOwnTemporaryName(asideName), asideName)
        assertNotEquals(temporary, asideName)

        assertContentEquals(new, folder.bytesOf(DATABASE))
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
        assertNotEquals(before, store.version())
    }

    @Test
    fun atEveryMomentOfASaveTheOldOrTheNewDatabaseIsWholeAndSyncthingSeesNothingElse() {
        folder.put(DATABASE, old, earlier)
        var moments = 0
        folder.beforeCall = {
            moments++
            val contents = folder.names("Sync").map { name -> name to folder.bytesOf("Sync/$name")!! }
            assertTrue(contents.any { (_, bytes) -> bytes.contentEquals(old) || bytes.contentEquals(new) }, "a whole database at $it")
            for ((name, bytes) in contents) {
                if (!isSyncthingsOwnTemporaryName(name)) {
                    assertTrue(bytes.contentEquals(old) || bytes.contentEquals(new), "Syncthing sees $name half-written at $it")
                }
            }
        }

        store.replace(new)

        assertTrue(moments >= 5)
        assertContentEquals(new, folder.bytesOf(DATABASE))
    }

    @Test
    fun aShorterDatabaseKeepsNothingOfTheLongerOneItReplaced() {
        folder.put(DATABASE, new, earlier)

        store.replace(old)

        assertContentEquals(old, folder.bytesOf(DATABASE))
        assertTrue(folder.calls.none { it.operation == Operation.WRITE && it.path == DATABASE }, "never written in place")
    }

    @Test
    fun theTemporaryNamesAreOnesSyncthingNeverSendsNorUsesItself() {
        val name = FolderDatabaseStore.temporaryFileName()

        // Syncthing never scans or sends ".syncthing.*.tmp"; it downloads into ".syncthing.<name>.tmp" itself.
        assertTrue(name.startsWith(".syncthing.keething-") && name.endsWith(".tmp"), name)
        assertNotEquals(".syncthing.Personal.kdbx.tmp", name)
        assertNotEquals(name, FolderDatabaseStore.temporaryFileName())
    }

    @Test
    fun aSaveWithNoDatabaseWritesNothing() {
        folder.makeFolder("Sync")

        assertFailsWith<IOException> { store.replace(new) }

        assertTrue(mutations().isEmpty())
        assertTrue(folder.names("Sync").isEmpty())
    }

    @Test
    fun aSaveThatCannotNoteItselfTouchesNothing() {
        folder.put(DATABASE, old, earlier)
        // The app's storage refuses the note: its folder is a file.
        notesDirectory.writeText("not a folder")

        assertFailsWith<IOException> { store.replace(new) }

        assertTrue(mutations().isEmpty())
        assertContentEquals(old, folder.bytesOf(DATABASE))
    }

    @Test
    fun aDatabaseRenamedAsideToAnotherNameFailsTheSaveAndIsPutBack() {
        folder.put(DATABASE, old, earlier)
        var taken: String? = null
        folder.beforeCall = {
            if (it.operation == Operation.RENAME && it.path == DATABASE && taken == null) {
                taken = it.name
                folder.put("Sync/${it.name}", byteArrayOf(9))
            }
        }

        assertFailsWith<IOException> { store.replace(new) }

        assertContentEquals(old, folder.bytesOf(DATABASE))
        assertEquals(listOf("Personal.kdbx", taken!!).sorted(), folder.names("Sync").sorted(), "nothing of the save is left")
        assertNull(found(storeAt(DATABASE).read()).stoppedSave)
    }

    @Test
    fun aDatabaseASyncRenamedInDuringTheSaveStaysAndTheSaveIsToBeMadeAgainOnIt() {
        folder.put(DATABASE, old, earlier)
        folder.beforeCall = {
            if (it.operation == Operation.RENAME && it.name == "Personal.kdbx" && folder.bytesOf(DATABASE) == null) folder.put(DATABASE, theirs)
        }

        assertFailsWith<DatabaseChangedException> { store.replace(new) }

        assertContentEquals(theirs, folder.bytesOf(DATABASE))
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"), "neither Personal (1).kdbx nor the old one is left")
        assertNull(found(storeAt(DATABASE).read()).stoppedSave)
    }

    @Test
    fun aDatabaseASyncReplacedBeforeItWasRenamedAsideIsNotReplaced() {
        folder.put(DATABASE, old, earlier)
        folder.beforeCall = { if (it.operation == Operation.WRITE) folder.put(DATABASE, theirs) }

        assertFailsWith<DatabaseChangedException> { store.replace(new) }

        assertContentEquals(theirs, folder.bytesOf(DATABASE))
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
        assertTrue(folder.calls.none { it.operation == Operation.RENAME }, "the sync's file never went aside, not even for a moment")
    }

    @Test
    fun aSaveThatFailsBeforeTheNewOneIsInLeavesTheDatabaseAsItWasAndNothingElse() {
        val steps = mapOf<String, (Call) -> Boolean>(
            "making the new file" to { it.operation == Operation.CREATE },
            "writing it" to { it.operation == Operation.WRITE },
            "renaming the database aside" to { it.operation == Operation.RENAME && it.path == DATABASE },
            "renaming the new one in" to { it.operation == Operation.RENAME && it.name == "Personal.kdbx" },
        )
        for ((step, failing) in steps) {
            folder.put(DATABASE, old, earlier)
            var failed = false
            // Once: the old file renamed back in is no step of the save.
            folder.beforeCall = {
                if (!failed && failing(it)) {
                    failed = true
                    throw IOException("the provider failed $step")
                }
            }

            assertFailsWith<IOException>(step) { store.replace(new) }

            folder.beforeCall = {}
            assertContentEquals(old, folder.bytesOf(DATABASE), step)
            assertEquals(listOf("Personal.kdbx"), folder.names("Sync"), step)
            assertNull(found(storeAt(DATABASE).read()).stoppedSave, "$step: nothing to put right")
        }
    }

    @Test
    fun anOldFileThatCouldNotBeDeletedGoesAtTheNextReadWithNothingSaid() {
        folder.put(DATABASE, old, earlier)
        folder.beforeCall = { if (it.operation == Operation.DELETE) throw IOException("the provider failed") }

        store.replace(new)

        folder.beforeCall = {}
        assertContentEquals(new, folder.bytesOf(DATABASE))
        assertEquals(2, folder.names("Sync").size)
        val read = found(storeAt(DATABASE).read())
        assertContentEquals(new, read.bytes)
        assertNull(read.stoppedSave)
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun aSaveWhosePutBackFailedTooIsPutBackAtTheNextReadWithNothingSaid() {
        folder.put(DATABASE, old, earlier)
        // The provider's process ended between the renames: nothing more goes through until it is back.
        var ended = false
        folder.beforeCall = {
            if (it.operation == Operation.RENAME && it.name == "Personal.kdbx") ended = true
            if (ended) throw IOException("the provider died")
        }

        assertFailsWith<IOException> { store.replace(new) }
        assertNull(folder.bytesOf(DATABASE))

        folder.beforeCall = {}
        // The save said it failed: the next read finishes putting the database back as it was (AC4), not the save.
        val read = found(storeAt(DATABASE).read())
        assertContentEquals(old, read.bytes)
        assertNull(read.stoppedSave)
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun aRenameThatWentThroughThoughItsAnswerWasLostIsASave() {
        folder.put(DATABASE, old, earlier)
        var lost = false
        folder.afterCall = {
            if (!lost && it.operation == Operation.RENAME && it.name == "Personal.kdbx") {
                lost = true
                throw IOException("the provider ended before it answered")
            }
        }

        store.replace(new)

        assertContentEquals(new, folder.bytesOf(DATABASE))
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
        assertNull(found(storeAt(DATABASE).read()).stoppedSave)
    }

    @Test
    fun aFailedSaveWhoseOldFileSyncthingRemovedKeepsTheNewOne() {
        folder.put(DATABASE, old, earlier)
        var failed = false
        folder.beforeCall = {
            if (!failed && it.operation == Operation.RENAME && it.name == "Personal.kdbx") {
                failed = true
                // Syncthing's scan removes a ".syncthing.*.tmp" older than a day, and the old file keeps its own time.
                folder.names("Sync").filter { name -> folder.versionOf("Sync/$name")!!.modified == earlier }.forEach { name -> folder.remove("Sync/$name") }
                throw IOException("the provider failed")
            }
        }

        store.replace(new)

        assertContentEquals(new, folder.bytesOf(DATABASE))
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun aFileASyncRenamedInJustBeforeTheDatabaseWentAsideIsNotLost() {
        folder.put(DATABASE, old, earlier)
        var synced = false
        folder.beforeCall = {
            if (!synced && it.operation == Operation.RENAME && it.path == DATABASE) {
                synced = true
                folder.put(DATABASE, theirs)
            }
        }

        assertFailsWith<DatabaseChangedException> { store.replace(new) }

        assertContentEquals(theirs, folder.bytesOf(DATABASE))
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
        assertNull(found(storeAt(DATABASE).read()).stoppedSave)
    }

    @Test
    fun aDatabaseNoLongerAtTheVersionTheSaveWasMadeOnIsNotReplaced() {
        folder.put(DATABASE, old, earlier)
        val expected = store.version()
        folder.put(DATABASE, theirs, earlier)

        assertFailsWith<DatabaseChangedException> { store.replace(new, expected) }

        assertContentEquals(theirs, folder.bytesOf(DATABASE))
        assertTrue(mutations().isEmpty())
    }

    @Test
    fun aCopyLeftUnderANameSyncthingSendsGoesAtTheNextRead() {
        folder.put(DATABASE, old, earlier)
        var stray: String? = null
        folder.beforeCall = {
            if (it.operation == Operation.RENAME && it.name == "Personal.kdbx" && folder.bytesOf(DATABASE) == null) folder.put(DATABASE, theirs)
            if (it.operation == Operation.DELETE && it.path.endsWith(".kdbx")) {
                stray = it.path
                throw IOException("the provider failed")
            }
        }
        assertFailsWith<DatabaseChangedException> { store.replace(new) }
        assertEquals("Sync/Personal (1).kdbx", stray)
        folder.beforeCall = {}

        storeAt(DATABASE).read()

        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
        assertContentEquals(theirs, folder.bytesOf(DATABASE))
    }

    @Test
    fun aFileASyncPutUnderTheNameOfALeftCopySinceIsNotTouched() {
        folder.put(DATABASE, old, earlier)
        folder.beforeCall = {
            if (it.operation == Operation.RENAME && it.name == "Personal.kdbx" && folder.bytesOf(DATABASE) == null) folder.put(DATABASE, theirs)
            if (it.operation == Operation.DELETE && it.path.endsWith(".kdbx")) throw IOException("the provider failed")
        }
        assertFailsWith<DatabaseChangedException> { store.replace(new) }
        folder.beforeCall = {}
        // The user's own copy, brought by a sync under that name meanwhile.
        folder.put("Sync/Personal (1).kdbx", theirs)

        storeAt(DATABASE).read()

        assertEquals(listOf("Personal (1).kdbx", "Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun theOldFileOfAFinishedSaveNeverComesBack() {
        folder.put(DATABASE, old, earlier)
        folder.beforeCall = { if (it.operation == Operation.DELETE) throw IOException("the provider failed") }
        store.replace(new)
        folder.beforeCall = {}
        // Deleted on another device before the next read.
        folder.remove(DATABASE)

        assertEquals(DatabaseRead.Missing, storeAt(DATABASE).read())
        assertTrue(folder.names("Sync").isEmpty())
    }

    @Test
    fun ofSeveralStoppedSavesTheLastOneDecides() {
        val first = "the first save".encodeToByteArray()
        folder.put(DATABASE, old, earlier)
        // The first save stops before its old file goes, and the read after it cannot remove that file.
        folder.beforeCall = { if (it.operation == Operation.DELETE) throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { store.replace(first) }
        folder.beforeCall = { if (it.operation == Operation.DELETE) throw IOException("the provider failed") }
        assertContentEquals(first, found(storeAt(DATABASE).read()).bytes)
        // The second stops between its renames.
        folder.beforeCall = { if (it.operation == Operation.RENAME && it.name == "Personal.kdbx") throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { storeAt(DATABASE).replace(new) }
        folder.beforeCall = {}

        val read = found(storeAt(DATABASE).read())

        assertContentEquals(new, read.bytes)
        assertEquals(StoppedSave.FINISHED, read.stoppedSave)
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    // The 2 s rule (AC6)

    @Test
    fun aSaveRightAfterTheDatabasesTimeWaitsUntilTwoSecondsPastIt() {
        val replacedAt = time.now().minusMillis(500)
        folder.put(DATABASE, old, replacedAt)

        store.replace(new)

        val saved = folder.versionOf(DATABASE)!!.modified
        assertTrue(Duration.between(replacedAt, saved) >= TWO_SECONDS, "saved at $saved")
        assertTrue(time.slept() in Duration.ofMillis(1500)..Duration.ofMillis(1600), "waited ${time.slept()}")
    }

    @Test
    fun aSaveLongAfterTheDatabasesTimeOrLongBeforeItDoesNotWait() {
        for (replacedAt in listOf(time.now().minusSeconds(10), time.now().plusSeconds(10))) {
            folder.put(DATABASE, old, replacedAt)

            store.replace(new)

            assertEquals(Duration.ZERO, time.slept(), "the database at $replacedAt")
            assertEquals(time.now(), folder.versionOf(DATABASE)!!.modified)
        }
    }

    @Test
    fun aDatabaseFromAClockRunningAheadIsWaitedForAtMostAboutFourSeconds() {
        val replacedAt = time.now().plusMillis(1990)
        folder.put(DATABASE, old, replacedAt)

        store.replace(new)

        val waited = time.slept()
        assertTrue(Duration.between(replacedAt, folder.versionOf(DATABASE)!!.modified) >= TWO_SECONDS)
        // Its 2 s on either side, and the margin past them.
        assertTrue(waited <= Duration.ofMillis(4100), "waited $waited")
    }

    @Test
    fun twoSavesInARowAreTwoSecondsApart() {
        folder.put(DATABASE, old, earlier)

        store.replace(new)
        val first = folder.versionOf(DATABASE)!!.modified
        store.replace(old)
        val second = folder.versionOf(DATABASE)!!.modified

        assertTrue(Duration.between(first, second) >= TWO_SECONDS, "$first, then $second")
    }

    @Test
    fun aSecondSaveBeforeASyncAppSawTheFirstStaysTwoSecondsFromTheFileTheFirstReplaced() {
        // From a device whose clock runs 4 s ahead: the sync app holds that time until it scans the first save.
        val synced = time.now().plusSeconds(4)
        folder.put(DATABASE, old, synced)

        store.replace(new)
        val first = folder.versionOf(DATABASE)!!.modified
        time.slept()
        store.replace(old)
        val second = folder.versionOf(DATABASE)!!.modified
        val waited = time.slept()

        assertTrue(Duration.between(first, second) >= TWO_SECONDS, "$first, then $second")
        assertTrue(Duration.between(synced, second).abs() >= TWO_SECONDS, "the synced file's $synced, then $second")
        assertTrue(waited <= Duration.ofMillis(6200), "waited $waited")
    }

    @Test
    fun anEarlierFilesTimeIsKeptClearOfByTheNewFilesOwnTime() {
        folder.stampLag = Duration.ofMillis(100)
        val synced = time.now().plusMillis(3900)
        folder.put(DATABASE, old, synced)

        store.replace(new)
        store.replace(old)

        val second = folder.versionOf(DATABASE)!!.modified
        assertTrue(Duration.between(synced, second).abs() >= TWO_SECONDS, "the synced file's $synced, then $second")
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun theTimesOfTheFilesSavesReplacedAreKeptForEveryStoreOfTheDatabaseOverTheSameNotes() {
        val synced = time.now().plusSeconds(4)
        folder.put(DATABASE, old, synced)
        val notes = SaveNotes(notesDirectory)

        FolderDatabaseStore(folder, DATABASE, notes, time, time::sleep).replace(new)
        FolderDatabaseStore(folder, DATABASE, notes, time, time::sleep).replace(old)

        val second = folder.versionOf(DATABASE)!!.modified
        assertTrue(Duration.between(synced, second).abs() >= TWO_SECONDS, "the synced file's $synced, then $second")
    }

    @Test
    fun theTimesOfTheFilesAnotherDatabasesSavesReplacedAreNotWaitedFor() {
        val other = "Sync/Other.kdbx"
        val notes = SaveNotes(notesDirectory)
        folder.put(other, old, time.now().plusSeconds(4))
        FolderDatabaseStore(folder, other, notes, time, time::sleep).replace(new)
        // Within 2 s of the time the other database's file had.
        time.sleep(Duration.ofSeconds(2))
        time.slept()
        folder.put(DATABASE, old, earlier)

        FolderDatabaseStore(folder, DATABASE, notes, time, time::sleep).replace(new)

        assertEquals(Duration.ZERO, time.slept())
    }

    @Test
    fun earlierTimesTooCloseToClearWithinAboutSixSecondsLeaveTheReplacedFileOnlyToClear() {
        folder.put(DATABASE, old, time.now().plusSeconds(4))
        store.replace(new)
        folder.put(DATABASE, theirs, time.now().plusSeconds(8))
        store.replace(new)
        val replacedAt = folder.versionOf(DATABASE)!!.modified
        time.slept()

        // The replaced file's 2 s, the first synced file's and the second's meet: 10 s to clear them all.
        store.replace(old)

        val waited = time.slept()
        assertTrue(Duration.between(replacedAt, folder.versionOf(DATABASE)!!.modified) >= TWO_SECONDS)
        assertTrue(waited <= Duration.ofMillis(6200), "waited $waited")
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun aNewFileWhoseTimeStaysWithinTwoSecondsFailsTheSaveBeforeTheDatabaseIsTouched() {
        folder.put(DATABASE, old, time.now().minusMillis(500))
        // The wait does not move the clock on (it went back meanwhile): each new file is stamped 0.5 s after the old.
        val store = storeAt(DATABASE, sleep = {})

        assertFailsWith<IOException> { store.replace(new) }

        assertContentEquals(old, folder.bytesOf(DATABASE))
        assertTrue(folder.calls.none { it.operation == Operation.RENAME }, "the database never went aside")
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
        assertNull(found(storeAt(DATABASE).read()).stoppedSave)
    }

    @Test
    fun aFileTheStorageStampedFurtherBehindThanTheClockIsWrittenAgainLater() {
        // Past the 2 s by the clock, but the storage stamps 100 ms behind it.
        folder.stampLag = Duration.ofMillis(100)
        val replacedAt = time.now().minusMillis(2060)
        folder.put(DATABASE, old, replacedAt)

        store.replace(new)

        assertTrue(Duration.between(replacedAt, folder.versionOf(DATABASE)!!.modified) >= TWO_SECONDS)
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun aSlowWriteBeforeTheTimeOfAClockAheadIsWrittenAgainPastIt() {
        val replacedAt = time.now().plusMillis(2100)
        folder.put(DATABASE, old, replacedAt)
        // Each write takes 200 ms: the first new file is stamped 1.9 s before the replaced one's time.
        folder.beforeCall = { if (it.operation == Operation.WRITE) time.sleep(Duration.ofMillis(200)) }

        store.replace(new)

        val saved = folder.versionOf(DATABASE)!!.modified
        assertTrue(Duration.between(replacedAt, saved).abs() >= TWO_SECONDS, "saved at $saved")
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun aSavedFileThatTookTheOldTimeFailsTheSaveOnceItIsReplaced() {
        folder.put(DATABASE, old, earlier)
        // As the Pixel 6's shared storage once gave a renamed file the replaced one's time (android SPEC §3.1).
        folder.beforeCall = { if (it.operation == Operation.DELETE) folder.touch(DATABASE, earlier) }

        assertFailsWith<IOException> { store.replace(new) }

        assertContentEquals(new, folder.bytesOf(DATABASE))
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    // A save stopped in the middle (AC5)

    private class Stop(val step: String, val at: (Call) -> Boolean, val database: String, val way: StoppedSave)

    @Test
    fun aSaveStoppedAtAnyStepIsPutRightAtTheNextReadAndSaidUntilTheAppSaidIt() {
        var deleted = false
        val stops = listOf(
            Stop("before the new file is made", { it.operation == Operation.CREATE }, "old", StoppedSave.UNDONE),
            Stop("before it is written", { it.operation == Operation.WRITE }, "old", StoppedSave.UNDONE),
            Stop("before the database is renamed aside", { it.operation == Operation.RENAME && it.path == DATABASE }, "old", StoppedSave.UNDONE),
            Stop("before the new one is renamed in", { it.operation == Operation.RENAME && it.name == "Personal.kdbx" }, "new", StoppedSave.FINISHED),
            Stop("before the old one is deleted", { it.operation == Operation.DELETE }, "new", StoppedSave.FINISHED),
            Stop("before the new time is checked", { deleted.also { _ -> if (it.operation == Operation.DELETE) deleted = true } }, "new", StoppedSave.FINISHED),
        )
        for (stop in stops) {
            deleted = false
            folder.put(DATABASE, old, earlier)
            folder.beforeCall = { if (stop.at(it)) throw ProcessEnded() }
            assertFailsWith<ProcessEnded>(stop.step) { storeAt(DATABASE).replace(new) }
            folder.beforeCall = {}

            val read = found(storeAt(DATABASE).read())

            assertContentEquals(if (stop.database == "new") new else old, read.bytes, stop.step)
            assertEquals(stop.way, read.stoppedSave, stop.step)
            assertEquals(listOf("Personal.kdbx"), folder.names("Sync"), "${stop.step}: no file of the save is left")
            val next = storeAt(DATABASE)
            assertEquals(stop.way, found(next.read()).stoppedSave, "${stop.step}: a read that showed nothing took nothing away")
            next.stoppedSaveSaid()
            assertNull(found(storeAt(DATABASE).read()).stoppedSave, "${stop.step}: said")
        }
    }

    @Test
    fun aSaveStoppedHalfWayThroughWritingTheNewFileIsUndone() {
        folder.put(DATABASE, old, earlier)
        folder.writeEndsAfter = 5
        assertFailsWith<ProcessEnded> { store.replace(new) }

        val read = found(storeAt(DATABASE).read())

        assertContentEquals(old, read.bytes)
        assertEquals(StoppedSave.UNDONE, read.stoppedSave)
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun aNewFileNotWholeIsNotPutInPlaceTheOldOneIs() {
        folder.put(DATABASE, old, earlier)
        var temporary: String? = null
        folder.beforeCall = {
            if (it.operation == Operation.RENAME && it.name == "Personal.kdbx") {
                temporary = it.path
                throw ProcessEnded()
            }
        }
        assertFailsWith<ProcessEnded> { store.replace(new) }
        folder.beforeCall = {}
        // Cut short on the storage: a power cut before the flush reached it.
        folder.put(temporary!!, new.copyOf(new.size - 1))

        val read = found(storeAt(DATABASE).read())

        assertContentEquals(old, read.bytes)
        assertEquals(StoppedSave.UNDONE, read.stoppedSave)
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun aStoppedSaveWhoseDatabaseASyncReplacedSinceIsUndone() {
        folder.put(DATABASE, old, earlier)
        folder.beforeCall = { if (it.operation == Operation.DELETE) throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { store.replace(new) }
        folder.beforeCall = {}
        folder.put(DATABASE, theirs)

        val read = found(storeAt(DATABASE).read())

        assertContentEquals(theirs, read.bytes)
        assertEquals(StoppedSave.UNDONE, read.stoppedSave, "the save's content is not in the database")
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun aStoppedSaveWhoseDatabaseWasDeletedMeanwhilePutsItsNewFileThereWhenWholeElseLeavesNothing() {
        // Stopped before the database went aside, and then deleted on another device: the database is missing and
        // the save's new file is there (AC5).
        folder.put(DATABASE, old, earlier)
        folder.beforeCall = { if (it.operation == Operation.RENAME) throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { store.replace(new) }
        folder.beforeCall = {}
        folder.remove(DATABASE)

        val read = found(storeAt(DATABASE).read())

        assertContentEquals(new, read.bytes)
        assertEquals(StoppedSave.FINISHED, read.stoppedSave)
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))

        folder.writeEndsAfter = 5
        assertFailsWith<ProcessEnded> { store.replace(old) }
        folder.remove(DATABASE)

        assertEquals(DatabaseRead.Missing, storeAt(DATABASE).read())
        assertTrue(folder.names("Sync").isEmpty())
    }

    @Test
    fun aStoppedSaveOfAnotherDatabaseIsLeftToIt() {
        folder.put(DATABASE, old, earlier)
        folder.put("Sync/Work.kdbx", old, earlier)
        folder.beforeCall = { if (it.operation == Operation.RENAME && it.name == "Work.kdbx") throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { storeAt("Sync/Work.kdbx").replace(new) }
        folder.beforeCall = {}

        assertNull(found(storeAt(DATABASE).read()).stoppedSave)
        assertEquals(3, folder.names("Sync").size, "the work database's files are left")

        val work = found(storeAt("Sync/Work.kdbx").read())
        assertContentEquals(new, work.bytes)
        assertEquals(StoppedSave.FINISHED, work.stoppedSave)
        assertEquals(listOf("Personal.kdbx", "Work.kdbx"), folder.names("Sync"))
    }

    @Test
    fun aStoppedSaveOfADatabaseWithAnOddNameIsPutRightAndAnUnreadableNoteIsLeftAlone() {
        val path = "Sync/Ключи = 2024\t#1\\x.kdbx"
        folder.put(path, old, earlier)
        folder.beforeCall = { if (it.operation == Operation.RENAME && it.name == nameOfPath(path)) throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { storeAt(path).replace(new) }
        folder.beforeCall = {}
        val unreadable = File(notesDirectory, "garbage.save").apply { writeBytes(byteArrayOf(0, -1, 10, 61)) }

        val read = found(storeAt(path).read())

        assertContentEquals(new, read.bytes)
        assertEquals(StoppedSave.FINISHED, read.stoppedSave)
        assertEquals(listOf(nameOfPath(path)), folder.names("Sync"))
        assertTrue(unreadable.exists())
    }

    private fun nameOfPath(path: String) = path.substringAfterLast('/')

    // Names in another case (stage S3: the storage takes names regardless of case)

    @Test
    fun aDatabaseSpelledInAnotherCaseOnTheStorageIsReadAndSavedUnderTheListsSpelling() {
        folder.put("Sync/personal.KDBX", old, earlier)

        assertContentEquals(old, found(store.read()).bytes)
        store.replace(new, store.version())

        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"), "one file, nothing of the save left")
        assertContentEquals(new, folder.bytesOf(DATABASE))
    }

    @Test
    fun aStoppedSaveOfADatabaseSpelledInAnotherCaseIsPutRight() {
        folder.put("Sync/personal.kdbx", old, earlier)
        folder.beforeCall = { if (it.operation == Operation.RENAME && it.name == "Personal.kdbx") throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { store.replace(new) }
        folder.beforeCall = {}

        val read = found(storeAt("Sync/PERSONAL.kdbx").read())

        assertContentEquals(new, read.bytes)
        assertEquals(StoppedSave.FINISHED, read.stoppedSave, "the note found by the path in another case")
        assertEquals(1, folder.names("Sync").size)
    }

    // A stopped save put right at the app's start (S1's handover, stage S3)

    @Test
    fun aStoppedSaveIsPutRightAtTheAppsStartWithoutAReadAndSaidAtTheNextOne() {
        folder.put(DATABASE, old, earlier)
        folder.put("Sync/Work.kdbx", old, earlier)
        folder.beforeCall = { if (it.operation == Operation.RENAME && it.name == "Personal.kdbx") throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { storeAt(DATABASE).replace(new) }
        folder.beforeCall = { if (it.operation == Operation.DELETE) throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { storeAt("Sync/Work.kdbx").replace(new) }
        folder.beforeCall = {}

        FolderDatabaseStore.putRightStoppedSaves(SaveNotes(notesDirectory), { grant -> folder.takeIf { grant == it.id } }, time)

        assertEquals(listOf("Personal.kdbx", "Work.kdbx"), folder.names("Sync").sorted(), "no file of either save is left")
        assertContentEquals(new, folder.bytesOf(DATABASE))
        assertContentEquals(new, folder.bytesOf("Sync/Work.kdbx"))
        val read = found(storeAt(DATABASE).read())
        assertEquals(StoppedSave.FINISHED, read.stoppedSave, "said at the next read")
    }

    @Test
    fun aStoppedSaveWhoseGrantIsGoneOrFailsAtTheStartIsLeftForItsRead() {
        folder.put(DATABASE, old, earlier)
        folder.beforeCall = { if (it.operation == Operation.RENAME && it.name == "Personal.kdbx") throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { storeAt(DATABASE).replace(new) }
        folder.beforeCall = {}
        val files = folder.names("Sync").sorted()

        FolderDatabaseStore.putRightStoppedSaves(SaveNotes(notesDirectory), { null }, time)
        assertEquals(files, folder.names("Sync").sorted(), "no folder: nothing touched")

        folder.beforeCall = { if (it.operation == Operation.RENAME) throw java.nio.file.AccessDeniedException(it.path) }
        FolderDatabaseStore.putRightStoppedSaves(SaveNotes(notesDirectory), { folder }, time)
        folder.beforeCall = {}

        val read = found(storeAt(DATABASE).read())
        assertContentEquals(new, read.bytes)
        assertEquals(StoppedSave.FINISHED, read.stoppedSave)
    }

    @Test
    fun aStoppedSaveWaitsForItsGrantToComeBack() {
        folder.put(DATABASE, old, earlier)
        folder.beforeCall = { if (it.operation == Operation.RENAME && it.name == "Personal.kdbx") throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { store.replace(new) }
        folder.beforeCall = {}

        folder.granted = false
        assertEquals(DatabaseRead.AccessDenied, storeAt(DATABASE).read())
        folder.granted = true

        assertEquals(StoppedSave.FINISHED, found(storeAt(DATABASE).read()).stoppedSave)
    }

    @Test
    fun aReadDuringASaveOfTheSameDatabaseWaitsForItAndPutsNothingRight() {
        folder.put(DATABASE, old, earlier)
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        lateinit var saver: Thread
        folder.beforeCall = {
            if (Thread.currentThread() == saver && it.operation == Operation.RENAME && it.name == "Personal.kdbx") {
                paused.countDown()
                resume.await(5, TimeUnit.SECONDS)
            }
        }
        var saveFailure: Throwable? = null
        saver = thread(start = false) { saveFailure = runCatching { store.replace(new) }.exceptionOrNull() }
        saver.start()
        assertTrue(paused.await(5, TimeUnit.SECONDS))

        // Another store of the same database in the same process, as a second screen makes it.
        var read: DatabaseRead? = null
        val reader = thread { read = storeAt(DATABASE).read() }
        reader.join(300)
        assertTrue(reader.isAlive, "the read waits for the save")
        resume.countDown()
        saver.join(5000)
        reader.join(5000)

        assertNull(saveFailure)
        val after = found(read!!)
        assertContentEquals(new, after.bytes)
        assertNull(after.stoppedSave)
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
    }

    @Test
    fun startingAndClosingAWatchDoNotWaitForASave() {
        folder.put(DATABASE, old, earlier)
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        lateinit var saver: Thread
        folder.beforeCall = {
            if (Thread.currentThread() == saver && it.operation == Operation.WRITE) {
                paused.countDown()
                resume.await(5, TimeUnit.SECONDS)
            }
        }
        saver = thread(start = false) { store.replace(new) }
        saver.start()
        assertTrue(paused.await(5, TimeUnit.SECONDS))

        // The session starts and stops its watch on the main thread (an unlock, a lock).
        val watching = thread { storeAt(DATABASE).watch {}.close() }
        watching.join(2000)
        val waited = watching.isAlive
        resume.countDown()
        saver.join(5000)
        watching.join(5000)

        assertFalse(waited, "the watch waited for the save")
    }

    // A copy kept beside it (keething-spec kdbx.kdbx3 AC7)

    // spec: kdbx.kdbx3/AC7
    @Test
    fun aCopyIsKeptBesideTheDatabaseAndNeverOverAFileThere() {
        folder.put(DATABASE, old, earlier)
        folder.put("Sync/Personal (KDBX 3.1).kdbx", theirs, earlier)
        folder.put("Sync/personal (kdbx 3.1) 2.KDBX", theirs, earlier)

        assertEquals("Personal (KDBX 3.1) 3.kdbx", store.keepBeside(old, "Personal (KDBX 3.1).kdbx"))

        assertContentEquals(old, folder.bytesOf("Sync/Personal (KDBX 3.1) 3.kdbx"))
        assertContentEquals(theirs, folder.bytesOf("Sync/Personal (KDBX 3.1).kdbx"))
        assertContentEquals(theirs, folder.bytesOf("Sync/personal (kdbx 3.1) 2.KDBX"))
        assertContentEquals(old, folder.bytesOf(DATABASE))
        assertEquals("Notes (KDBX 3.1) 2", storeAt("Sync/Notes").also { folder.put("Sync/Notes (KDBX 3.1)", theirs) }.keepBeside(old, "Notes (KDBX 3.1)"))
    }

    @Test
    fun aCopyWithNoFolderKeepsNothing() {
        assertFailsWith<IOException> { storeAt("Gone/Personal.kdbx").keepBeside(old, "Personal (KDBX 3.1).kdbx") }
        assertTrue(mutations().isEmpty())
    }

    // Conflict copies

    @Test
    fun listsTheDatabasesConflictCopiesInItsFolderNewestFirst() {
        folder.put(DATABASE, old, earlier)
        folder.put("Sync/Personal.sync-conflict-20261001-120000-AAAAAAA.kdbx", old)
        folder.put("Sync/Personal.sync-conflict-20261002-120000-BBBBBBB.kdbx", old)
        folder.put("Sync/Work.sync-conflict-20261003-120000-CCCCCCC.kdbx", old)
        folder.put("Personal.sync-conflict-20261004-120000-DDDDDDD.kdbx", old)

        assertEquals(listOf("BBBBBBB", "AAAAAAA"), store.conflictCopies().map { it.deviceId })
    }

    // Noticing changes (AC7's logic)

    @Test
    fun aNoticeCallsOnChangeWhenTheDatabaseOrItsConflictCopiesChanged() {
        folder.put(DATABASE, old, earlier)
        var changes = 0
        store.watch { changes++ }

        folder.put(DATABASE, theirs)
        folder.deliverNotices()
        assertEquals(1, changes, "replaced")

        folder.put("Sync/Personal.sync-conflict-20261001-120000-AAAAAAA.kdbx", old)
        folder.deliverNotices()
        assertEquals(2, changes, "a conflict copy")

        folder.remove(DATABASE)
        folder.deliverNotices()
        assertEquals(3, changes, "deleted")

        folder.put("Sync/notes.txt", old)
        folder.deliverNotices()
        folder.put("Sync/Work.kdbx", old)
        folder.deliverNotices()
        assertEquals(3, changes, "other files")
    }

    @Test
    fun watchesTheDatabasesOwnFolder() {
        folder.put("Sync/KeePass/Personal.kdbx", old, earlier)

        storeAt("Sync/KeePass/Personal.kdbx").watch {}

        assertEquals(listOf(Call(Operation.WATCH, "Sync/KeePass")), folder.calls.filter { it.operation == Operation.WATCH })
    }

    @Test
    fun aWatchTheProvidersEndStoppedStartsAgainAtTheNextReadAndSaysWhatItMissed() {
        folder.put(DATABASE, old, earlier)
        var changes = 0
        store.watch { changes++ }

        folder.endWatches()
        folder.put(DATABASE, theirs)
        folder.deliverNotices()
        assertEquals(0, changes, "nothing comes from a provider that ended")

        store.read()
        assertEquals(1, changes, "what it missed")
        assertEquals(2, folder.calls.count { it.operation == Operation.WATCH })

        folder.put(DATABASE, new)
        folder.deliverNotices()
        assertEquals(2, changes, "notices come again")
    }

    @Test
    fun aWatchTheProvidersEndStoppedStartsAgainAtTheVersionCheckOfTheAppComingBack() {
        folder.put(DATABASE, old, earlier)
        var changes = 0
        store.watch { changes++ }
        folder.endWatches()

        store.version()

        assertEquals(1, folder.liveWatches())
        assertEquals(0, changes, "nothing changed meanwhile")
        folder.put(DATABASE, theirs)
        folder.deliverNotices()
        assertEquals(1, changes)
    }

    @Test
    fun aWatchThatCouldNotStartStartsAtTheNextRead() {
        folder.put(DATABASE, old, earlier)
        folder.beforeCall = { if (it.operation == Operation.WATCH) throw IOException("the provider is not there") }
        var changes = 0
        store.watch { changes++ }
        folder.beforeCall = {}

        store.read()
        folder.put(DATABASE, theirs)
        folder.deliverNotices()

        assertEquals(1, changes)
    }

    @Test
    fun aLiveWatchIsNotStartedAgainAndAClosedOneNotAtAll() {
        folder.put(DATABASE, old, earlier)
        var changes = 0
        val watch = store.watch { changes++ }

        store.read()
        store.version()
        assertEquals(1, folder.calls.count { it.operation == Operation.WATCH })

        watch.close()
        store.read()
        folder.put(DATABASE, theirs)
        folder.deliverNotices()
        assertEquals(1, folder.calls.count { it.operation == Operation.WATCH })
        assertEquals(0, folder.liveWatches())
        assertEquals(0, changes)
    }

    private companion object {
        const val DATABASE = "Sync/Personal.kdbx"
        val TWO_SECONDS: Duration = Duration.ofSeconds(2)
        val MUTATIONS = setOf(Operation.CREATE, Operation.WRITE, Operation.RENAME, Operation.DELETE)
    }
}

/** The wall clock, moved on only by [sleep]. */
private class TestTime(private var at: Instant) : Clock {
    private var waited = Duration.ZERO

    override fun now(): Instant = at

    override fun elapsed(): kotlin.time.Duration = Duration.between(Instant.EPOCH, at).toMillis().milliseconds

    fun sleep(duration: Duration) {
        waited += duration
        at += duration
    }

    /** What [sleep] waited since the last call. */
    fun slept(): Duration = waited.also { waited = Duration.ZERO }
}
