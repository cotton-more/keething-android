package dev.keething.vault.creating

import dev.keething.domain.storage.FakeGrantedFolder
import dev.keething.domain.storage.NewFile
import dev.keething.domain.storage.NewFilePlace
import dev.keething.domain.storage.StoragePlace
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.storage.FolderNewFile
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an ended process left while creating a database goes at the next start, and only what Create made
 * (keething-spec databases.new-database-create AC5; plan AD7), by path and in a granted folder (databases.saf AC11).
 */
class CreationNoteTest {
    private val folder: File = Files.createTempDirectory("creation-note-test").toFile()
    private val app = File(folder, "app").apply { mkdirs() }
    private val shared = File(folder, "shared").apply { mkdirs() }
    private val granted = FakeGrantedFolder("grant-sync")
    private val folders = mutableMapOf("grant-sync" to granted)
    private val note = CreationNote(File(app, "creating.note")) { folders[it] }

    @AfterTest
    fun deleteFolder() {
        folder.walkBottomUp().forEach { it.setWritable(true) }
        folder.deleteRecursively()
    }

    /** A new file by its path, made with `O_EXCL` as the app's own did. */
    private class PathNewFile(private val file: File) : NewFile {
        override val fileName: String = file.name

        override fun check(): NewFilePlace = if (file.exists()) NewFilePlace.TAKEN else NewFilePlace.FREE

        override fun create(bytes: ByteArray, readOnly: Boolean) {
            Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            if (readOnly) file.setReadOnly()
        }

        override fun removeCreated() {
            file.delete()
        }
    }

    private fun at(file: File) = FileLocation.ByPath(file.path)

    private fun noted(file: File) = note.noting(PathNewFile(file), at(file))

    private val syncFolder = StoragePlace(StoragePlace.PRIMARY, "Sync")

    private fun inFolder(path: String) = FileLocation.InFolder("grant-sync", syncFolder, path)

    private fun notedInFolder(path: String) = note.noting(FolderNewFile(granted, path), inFolder(path))

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
        assertEquals(CreationNote.Leftover("Family Shared", emptyList()), CreationNote(File(app, "creating.note")) { folders[it] }.leftovers())
        assertFalse(key.exists(), "a read-only key file goes too")
        assertFalse(database.exists())
        assertNull(note.leftovers(), "said once")
    }

    @Test
    fun anEmptyFileMadeJustBeforeTheProcessEndedGoes() {
        val database = File(shared, "New.kdbx")
        note.begin("New")
        note.making(at(database), "the whole file".encodeToByteArray())
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
        note.making(at(other), "mine".encodeToByteArray())
        other.writeText("theirs")
        assertEquals(CreationNote.Leftover("Other", listOf(at(other))), note.leftovers(), "kept, and said: it may be a part written")
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
        note.making(at(database), "the whole file, longer".encodeToByteArray())
        database.writeText("the whole")
        assertEquals(CreationNote.Leftover("Half", listOf(at(database))), note.leftovers())
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
        assertNull(note.leftovers { it == at(database) })
        assertTrue(key.exists() && database.exists(), "the list's database and its key file stay")
        assertNull(note.leftovers(), "the note went")

        // An earlier Create that was not made is still cleaned up and said, the made one beside it kept.
        val stuck = File(shared, "Stuck.kdbx")
        note.begin("Stuck")
        noted(stuck).create("stuck".encodeToByteArray())
        note.end(made = false)
        note.begin("Family")
        note.making(at(database), "database".encodeToByteArray())
        assertEquals(CreationNote.Leftover("Stuck", emptyList()), note.leftovers { it == at(database) })
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

    // In a granted folder (keething-spec databases.saf AC11)

    @Test
    fun anEndedCreateInAGrantedFolderLeavesFilesThatGoThroughTheGrant() {
        granted.makeFolder("KeePass")
        note.begin("Family Shared")
        notedInFolder("KeePass/Family Shared.keyx").create("key".encodeToByteArray(), readOnly = true)
        notedInFolder("KeePass/Family Shared.kdbx").create("database".encodeToByteArray())

        // The process ends here: a new one starts.
        val again = CreationNote(File(app, "creating.note")) { folders[it] }
        assertEquals(CreationNote.Leftover("Family Shared", emptyList()), again.leftovers())
        assertEquals(emptyList(), granted.names("KeePass"))
        assertNull(again.leftovers(), "said once")
    }

    @Test
    fun anEmptyFileTheProviderMadeBeforeTheProcessEndedGoesAndAPartWrittenOneIsKept() {
        note.begin("New")
        note.making(inFolder("New.kdbx"), "the whole file".encodeToByteArray())
        granted.create("", "New.kdbx")
        note.making(inFolder("Half.kdbx"), "the whole file, longer".encodeToByteArray())
        granted.put("Half.kdbx", "the whole".encodeToByteArray())

        assertEquals(CreationNote.Leftover("New", listOf(inFolder("Half.kdbx"))), note.leftovers())
        assertEquals(listOf("Half.kdbx"), granted.names(), "it cannot be told from someone else's: kept")
    }

    @Test
    fun aFileInAFolderWhoseGrantIsGoneIsKeptAndSaid() {
        note.begin("Gone")
        notedInFolder("Gone.kdbx").create("database".encodeToByteArray())
        folders.clear()

        assertEquals(CreationNote.Leftover("Gone", listOf(inFolder("Gone.kdbx"))), note.leftovers())
        assertEquals(listOf("Gone.kdbx"), granted.names())
    }

    @Test
    fun aNameTheProviderGaveOtherwiseIsNotNotedAndTheFileThereIsNotTouched() {
        granted.put("New.kdbx", "a sync brought this one".encodeToByteArray())
        note.begin("New")
        assertFailsWith<FileAlreadyExistsException> { notedInFolder("New.kdbx").create("mine".encodeToByteArray()) }

        assertEquals(CreationNote.Leftover("New", emptyList()), note.leftovers())
        assertEquals("a sync brought this one", granted.bytesOf("New.kdbx")?.decodeToString())
        assertEquals(listOf("New.kdbx"), granted.names(), "the provider's New (1).kdbx went again")
    }

    @Test
    fun aDatabaseInAGrantedFolderThatJoinedTheListKeepsItsFiles() {
        note.begin("Family")
        notedInFolder("Family.kdbx").create("database".encodeToByteArray())

        // Listed by another grant to a folder that holds it: the same file.
        val listed = FileLocation.InFolder("grant-top", StoragePlace(StoragePlace.PRIMARY, ""), "Sync/family.kdbx")
        assertNull(note.leftovers { it.isSameFile(listed) })
        assertEquals(listOf("Family.kdbx"), granted.names())
    }

    @Test
    fun aNoteOfAnEarlierVersionByPathStillReads() {
        val database = File(shared, "Old.kdbx")
        database.writeText("")
        File(app, "creating.note").writeText("name Old\nmade ${database.path}\t${"0".repeat(64)}\t8\n")

        assertEquals(CreationNote.Leftover("Old", emptyList()), note.leftovers())
        assertFalse(database.exists())
    }
}
