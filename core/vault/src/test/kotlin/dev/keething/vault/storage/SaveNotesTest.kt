package dev.keething.vault.storage

import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.FakeGrantedFolder
import dev.keething.domain.storage.FakeGrantedFolder.Operation
import dev.keething.domain.storage.ProcessEnded
import dev.keething.domain.storage.StoppedSave
import dev.keething.domain.time.Clock
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The notes of saves beyond one database's reads and saves (keething-spec databases.saf AC5, AC14; stage S1's handover
 * to S2): one database however its name is spelled, and what is left of a database that leaves the list.
 */
class SaveNotesTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now

        override fun elapsed(): kotlin.time.Duration = kotlin.time.Duration.ZERO
    }
    private val folder = FakeGrantedFolder(now = { now })
    private val appStorage: File = Files.createTempDirectory("save-notes-test").toFile()
    private val notes = SaveNotes(File(appStorage, "saves"))
    private val old = "the database before the save".encodeToByteArray()
    private val new = "the database the save writes".encodeToByteArray()

    @AfterTest
    fun deleteAppStorage() {
        appStorage.deleteRecursively()
    }

    private fun store(path: String = DATABASE) = FolderDatabaseStore(folder, path, notes, clock, sleep = {})

    /** A save of [DATABASE] stopped before its new file was renamed in: left to put right. */
    private fun stoppedSave() {
        folder.put(DATABASE, old, now.minusSeconds(10))
        folder.beforeCall = { if (it.operation == Operation.RENAME && it.name == "Personal.kdbx") throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { store().replace(new) }
        folder.beforeCall = {}
    }

    @Test
    fun oneDatabaseSpelledInAnotherCaseIsOneLockAndOneDatabasesNotes() {
        assertSame(SaveNotes.lockOf(folder.id, DATABASE), SaveNotes.lockOf(folder.id, "sync/PERSONAL.kdbx"), "the storage takes names regardless of case")
        stoppedSave()

        assertEquals(1, notes.left(folder.id, "SYNC/personal.KDBX").size)
        assertEquals(0, notes.left("another grant", DATABASE).size, "the grant is its address: not folded")
    }

    // spec: app.upgrade-safety/AC2
    @Test
    fun aNoteCarriesItsVersionOneWithoutIsTheFirstVersionsAndANewerOneIsLeftAlone() {
        stoppedSave()
        val note = File(appStorage, "saves").listFiles()!!.single()
        val written = note.readLines()
        assertTrue("version=1" in written)

        note.writeText(written.filterNot { it.startsWith("version=") }.joinToString("\n"))
        assertEquals(1, notes.left(folder.id, DATABASE).size, "as 0.0.1 wrote it")

        note.writeText(written.map { if (it.startsWith("version=")) "version=2" else it }.joinToString("\n"))
        assertEquals(0, notes.left(folder.id, DATABASE).size, "not this version's to put right")
        assertTrue(note.exists())
    }

    // spec: app.upgrade-safety/AC3
    @Test
    fun unreadableNotesAreToldThenKeptUnderAnotherNameNeverDeleted() {
        stoppedSave()
        assertEquals(emptyList(), notes.unreadable(), "a readable note is put right, not told")
        val saves = File(appStorage, "saves")
        File(saves, "damaged.save").writeText("folder=only half of it")
        File(saves, "later.save").writeText(saves.listFiles()!!.first { it.name != "damaged.save" }.readText().replace("version=1", "version=2"))

        assertEquals(listOf("damaged.save", "later.save"), notes.unreadable())
        notes.setUnreadableAside()

        assertEquals(emptyList(), notes.unreadable(), "told once")
        assertEquals("folder=only half of it", File(saves, "damaged.save.unreadable").readText())
        assertTrue(File(saves, "later.save.unreadable").exists())
        assertEquals(1, notes.left(folder.id, DATABASE).size, "the readable one stays")
    }

    @Test
    fun aDatabaseLeavingTheListTakesTheNotesOfItsSavesThatLeaveNothingToPutRight() {
        stoppedSave()
        assertTrue(notes.unsettled(folder.id), "a stopped save not yet put right")

        notes.forget(folder.id, DATABASE)
        assertEquals(1, notes.left(folder.id, DATABASE).size, "its folder still needs it: kept")

        assertEquals(StoppedSave.FINISHED, assertIs<DatabaseRead.Found>(store().read()).stoppedSave)
        assertFalse(notes.unsettled(folder.id), "put right, only to be said")

        notes.forget(folder.id, DATABASE)
        assertEquals(0, notes.left(folder.id, DATABASE).size)
        assertEquals(null, assertIs<DatabaseRead.Found>(store().read()).stoppedSave, "nothing said any more")
    }

    @Test
    fun theNotesOfDatabasesNotInTheListGoAtTheStartTheListedOnesStay() {
        stoppedSave()
        store().read()
        folder.put(WORK, old, now.minusSeconds(10))
        folder.beforeCall = { if (it.operation == Operation.RENAME && it.name == "Work.kdbx") throw ProcessEnded() }
        assertFailsWith<ProcessEnded> { store(WORK).replace(new) }
        folder.beforeCall = {}
        store(WORK).read()

        notes.forgetAllBut { grant, path -> grant == folder.id && path == WORK }

        assertEquals(0, notes.left(folder.id, DATABASE).size)
        assertEquals(1, notes.left(folder.id, WORK).size, "still to be said")
    }

    @Test
    fun aSaveWhoseOldCopyStayedInTheFolderIsNotForgottenUntilTheCopyIsGone() {
        folder.put(DATABASE, old, now.minusSeconds(10))
        folder.beforeCall = { if (it.operation == Operation.DELETE) throw java.io.IOException("the provider failed") }
        store().replace(new)
        folder.beforeCall = {}
        assertEquals(2, folder.names("Sync").size, "the old database aside, a whole copy")

        assertTrue(notes.unsettled(folder.id))
        notes.forget(folder.id, DATABASE)
        assertEquals(1, notes.left(folder.id, DATABASE).size, "kept: its folder keeps the grant")

        store().read()
        assertEquals(listOf("Personal.kdbx"), folder.names("Sync"))
        assertFalse(notes.unsettled(folder.id))
    }

    private companion object {
        const val DATABASE = "Sync/Personal.kdbx"
        const val WORK = "Sync/Work.kdbx"
    }
}
