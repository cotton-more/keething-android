package dev.keething.android.storage

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an ended process left while creating a database goes at the next start, and only what Create made
 * (keething-spec databases.new-database-create AC5; plan AD7).
 */
class CreationNoteTest {
    private val folder: File = Files.createTempDirectory("creation-note-test").toFile()
    private val app = File(folder, "app").apply { mkdirs() }
    private val shared = File(folder, "shared").apply { mkdirs() }
    private val note = CreationNote(File(app, "creating.note"))

    @AfterTest
    fun deleteFolder() {
        folder.walkBottomUp().forEach { it.setWritable(true) }
        folder.deleteRecursively()
    }

    private fun noted(file: File, ownerOnly: Boolean = false) = note.noting(PathNewFile(file, ownerOnly), file.path)

    @Test
    fun nothingNotedNothingToSay() {
        assertNull(note.leftovers())
    }

    @Test
    fun anEndedCreateLeavesAKeyFileAndADatabaseBothGoTheNameIsSaid() {
        val key = File(shared, "Family Shared.keyx")
        val database = File(shared, "Family Shared.kdbx")
        note.begin("Family Shared")
        noted(key).create("key".encodeToByteArray(), readOnly = true)
        noted(database).create("database".encodeToByteArray())

        // The process ends here: a new one starts.
        assertEquals(CreationNote.Leftover("Family Shared", emptyList()), CreationNote(File(app, "creating.note")).leftovers())
        assertFalse(key.exists(), "a read-only key file goes too")
        assertFalse(database.exists())
        assertNull(note.leftovers(), "said once")
    }

    @Test
    fun anEmptyFileMadeJustBeforeTheProcessEndedGoes() {
        val database = File(shared, "New.kdbx")
        note.begin("New")
        note.making(database.path, "the whole file".encodeToByteArray())
        database.createNewFile()
        assertEquals(CreationNote.Leftover("New", emptyList()), note.leftovers())
        assertFalse(database.exists())
    }

    @Test
    fun aFileSomeoneElsePutThereIsNeverRemoved() {
        val database = File(shared, "New.kdbx")
        database.writeText("a sync brought this one")
        note.begin("New")
        assertFailsWith<FileAlreadyExistsException> { noted(database).create("mine".encodeToByteArray()) }
        assertEquals(CreationNote.Leftover("New", emptyList()), note.leftovers(), "refused: not noted, not said")
        assertEquals("a sync brought this one", database.readText(), "refused, so not noted")

        // Noted, then something else took the place before the write (or changed it after): not as written, kept.
        val other = File(shared, "Other.kdbx")
        note.begin("Other")
        note.making(other.path, "mine".encodeToByteArray())
        other.writeText("theirs")
        assertEquals(CreationNote.Leftover("Other", listOf(other.path)), note.leftovers(), "kept, and said: it may be a part written")
        assertEquals("theirs", other.readText())
    }

    @Test
    fun aKeyFileRemovedBecauseItsDatabaseWasNotMadeIsNotNotedAnyMore() {
        val key = File(shared, "work.keyx")
        note.begin("Work")
        val target = noted(key)
        target.create("key".encodeToByteArray())
        target.removeCreated()
        assertFalse(key.exists())
        assertFalse(File(app, "creating.note").readText().contains(key.path), "taken out of the note")
        note.end(made = false)
        assertNull(note.leftovers(), "nothing of it left: no note")
    }

    @Test
    fun aPartWrittenFileIsKeptAndSaid() {
        val database = File(shared, "Half.kdbx")
        note.begin("Half")
        note.making(database.path, "the whole file, longer".encodeToByteArray())
        database.writeText("the whole")
        assertEquals(CreationNote.Leftover("Half", listOf(database.path)), note.leftovers())
        assertTrue(database.exists(), "it cannot be told from someone else's: kept")
    }

    @Test
    fun aCreateThatEndedNormallyLeavesNoNote() {
        val database = File(shared, "Done.kdbx")
        note.begin("Done")
        noted(database).create("database".encodeToByteArray())
        note.end(made = true)
        assertNull(note.leftovers())
        assertTrue(database.exists(), "made and in the list: stays")
    }

    @Test
    fun aFileAnEarlierCreateCouldNotRemoveStaysNotedThroughTheNextCreate() {
        val stuck = File(shared, "Stuck.keyx")
        note.begin("Stuck")
        noted(stuck).create("key".encodeToByteArray())
        note.end(made = false)
        val done = File(shared, "Done.kdbx")
        note.begin("Done")
        noted(done).create("database".encodeToByteArray())
        note.end(made = true)
        assertEquals(CreationNote.Leftover("Stuck", emptyList()), note.leftovers(), "the earlier one's file is cleaned up at the next start")
        assertFalse(stuck.exists())
        assertTrue(done.exists(), "the made one stays")
    }

    @Test
    fun aDatabaseThatJoinedTheListBeforeTheProcessEndedWasMadeItsFilesStayNothingSaid() {
        val key = File(shared, "Family.keyx")
        val database = File(shared, "Family.kdbx")
        note.begin("Family")
        noted(key).create("key".encodeToByteArray(), readOnly = true)
        noted(database).create("database".encodeToByteArray())

        // The record was added, then the process ended before the note did: the next start finds it in the list.
        assertNull(note.leftovers { it == database.path })
        assertTrue(key.exists() && database.exists(), "the list's database and its key file stay")
        assertNull(note.leftovers(), "the note went")

        // An earlier Create that was not made is still cleaned up and said, the made one beside it kept.
        val stuck = File(shared, "Stuck.kdbx")
        note.begin("Stuck")
        noted(stuck).create("stuck".encodeToByteArray())
        note.end(made = false)
        note.begin("Family")
        note.making(database.path, "database".encodeToByteArray())
        assertEquals(CreationNote.Leftover("Stuck", emptyList()), note.leftovers { it == database.path })
        assertFalse(stuck.exists())
        assertTrue(database.exists())
    }

    @Test
    fun aNameAndAPathWithTabsAndLineBreaksKeepTheirLine() {
        val odd = File(shared, "a\tb\\c.kdbx")
        note.begin("Tab\there\nand a line")
        noted(odd).create("x".encodeToByteArray())
        assertEquals("Tab\there\nand a line", note.leftovers()?.name)
        assertFalse(odd.exists())
    }
}
