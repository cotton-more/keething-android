package dev.keething.vault.storage

import dev.keething.domain.storage.FakeGrantedFolder
import dev.keething.domain.storage.FakeGrantedFolder.Operation
import dev.keething.domain.storage.GrantedFile
import dev.keething.domain.storage.KeyFileRead
import dev.keething.domain.storage.KeyFileSource
import dev.keething.domain.storage.NewFilePlace
import dev.keething.domain.storage.StoragePlace
import dev.keething.vault.creating.CreateFailure
import dev.keething.vault.creating.DatabaseCreator
import dev.keething.vault.databases.FileLocation
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * New files and key files in a granted folder, and key files through a grant (keething-spec databases.saf AC9, AC11;
 * kdbx.new-file AC8, kdbx.new-key-file AC3), on a fake of the phone's storage provider.
 */
class FolderNewFileTest {
    private val folder = FakeGrantedFolder("grant-sync")
    private val bytes = "a new database".encodeToByteArray()

    // A new file (AC11)

    @Test
    fun aNewFileIsMadeUnderItsOwnNameWithItsBytes() {
        folder.makeFolder("KeePass")
        val target = FolderNewFile(folder, "KeePass/Family.kdbx")

        assertEquals(NewFilePlace.FREE, target.check())
        target.create(bytes)

        assertContentEquals(bytes, folder.bytesOf("KeePass/Family.kdbx"))
        assertEquals(listOf("Family.kdbx"), folder.names("KeePass"))
        assertEquals("Family.kdbx", target.fileName)
    }

    @Test
    fun aNameTakenIsSaidBeforeAndTheProvidersOtherNameIsRemovedAgain() {
        folder.put("Family.kdbx", "theirs".encodeToByteArray())
        val target = FolderNewFile(folder, "Family.kdbx")

        assertEquals(NewFilePlace.TAKEN, target.check())
        assertFailsWith<FileAlreadyExistsException> { target.create(bytes) }

        assertEquals(listOf("Family.kdbx"), folder.names(), "Family (1).kdbx went again")
        assertContentEquals("theirs".encodeToByteArray(), folder.bytesOf("Family.kdbx"), "never over a file there")
    }

    @Test
    fun aNameTakenInAnotherCaseIsTaken() {
        folder.put("family.KDBX", "theirs".encodeToByteArray())
        assertEquals(NewFilePlace.TAKEN, FolderNewFile(folder, "Family.kdbx").check(), "the storage takes names regardless of case")
    }

    @Test
    fun theProvidersOtherNameTakenByASyncMeanwhileIsNotTouched() {
        folder.put("Family.kdbx", "theirs".encodeToByteArray())
        folder.afterCall = { if (it.operation == Operation.CREATE) folder.put("Family (1).kdbx", "a sync's".encodeToByteArray()) }

        assertFailsWith<FileAlreadyExistsException> { FolderNewFile(folder, "Family.kdbx").create(bytes) }

        assertContentEquals("a sync's".encodeToByteArray(), folder.bytesOf("Family (1).kdbx"))
    }

    @Test
    fun noFolderIsSaidAndRefused() {
        val target = FolderNewFile(folder, "Gone/Family.kdbx")
        assertEquals(NewFilePlace.NO_FOLDER, target.check())
        assertFailsWith<NoSuchFileException> { target.create(bytes) }
        assertTrue(folder.names().isEmpty())
    }

    @Test
    fun aWriteThatFailsLeavesNothingBehind() {
        folder.beforeCall = { if (it.operation == Operation.WRITE) throw IOException("the storage is full") }

        assertFailsWith<IOException> { FolderNewFile(folder, "Family.kdbx").create(bytes) }

        assertTrue(folder.names().isEmpty())
    }

    @Test
    fun removeCreatedRemovesOnlyTheFileItMade() {
        val target = FolderNewFile(folder, "work.keyx")
        target.create(bytes, readOnly = true)
        folder.put("work.keyx", "a sync's".encodeToByteArray())

        target.removeCreated()
        assertEquals(listOf("work.keyx"), folder.names(), "not the one it made any more")

        val mine = FolderNewFile(folder, "home.keyx")
        mine.create(bytes)
        mine.removeCreated()
        assertEquals(listOf("work.keyx"), folder.names())
    }

    @Test
    fun aNewKeyFileIsCreatedInAGrantedFolderReadsBackThroughItAndIsNeverMadeOverAnother() {
        val creator = DatabaseCreator(now = { Instant.parse("2026-11-02T12:00:00Z") })
        val keyFiles = KeyFiles(folder = { if (it == folder.id) folder else null }, file = { null }, byPath = { error("not by path") })
        val keyAt = FileLocation.InFolder(folder.id, StoragePlace(StoragePlace.PRIMARY, "Sync"), "family.keyx")

        val key = creator.createKeyFile(FolderNewFile(folder, "family.keyx"), keyFiles.at(keyAt))
        key.wipe()

        assertEquals(listOf("family.keyx"), folder.names())
        assertFailsWith<CreateFailure.Exists> { creator.createKeyFile(FolderNewFile(folder, "family.keyx"), keyFiles.at(keyAt)) }
    }

    // Key files through a grant (AC9, AC10)

    private val sync = StoragePlace(StoragePlace.PRIMARY, "Sync")

    private class File(override val id: String, var bytes: ByteArray?, var granted: Boolean = true) : GrantedFile {
        override fun read(): ByteArray? = if (granted) bytes?.copyOf() else throw AccessDeniedException(id)
    }

    @Test
    fun aKeyFileInAGrantedFolderIsReadByItsPathThereAndSaysWhenItIsNotThereOrTheGrantIsGone() {
        folder.put("Keys/family.keyx", "key".encodeToByteArray())
        val keyFiles = KeyFiles(folder = { if (it == folder.id) folder else null }, file = { null }, byPath = { error("not by path") })
        val source = keyFiles.at(FileLocation.InFolder(folder.id, sync, "Keys/family.keyx"))

        assertEquals("family.keyx", source.fileName)
        assertContentEquals("key".encodeToByteArray(), assertIs<KeyFileRead.Found>(source.read()).bytes)
        folder.remove("Keys/family.keyx")
        assertEquals(KeyFileRead.Missing, source.read())
        folder.granted = false
        assertEquals(KeyFileRead.AccessDenied, source.read())
        assertEquals(KeyFileRead.AccessDenied, keyFiles.at(FileLocation.InFolder("a grant gone", sync, "family.keyx")).read())
    }

    @Test
    fun aKeyFileGrantedAloneIsReadThroughItsOwnGrantWhichASyncsReplaceEnds() {
        val file = File("grant-key", "key".encodeToByteArray())
        val keyFiles = KeyFiles(folder = { null }, file = { if (it == file.id) file else null }, byPath = { error("not by path") })
        val source = keyFiles.at(FileLocation.Granted(file.id, StoragePlace(StoragePlace.PRIMARY, "Download/stick.keyx")))

        assertEquals("stick.keyx", source.fileName)
        assertContentEquals("key".encodeToByteArray(), assertIs<KeyFileRead.Found>(source.read()).bytes)
        file.granted = false
        assertEquals(KeyFileRead.AccessDenied, source.read(), "the grant ended: Choose key file… (AC10)")
        file.granted = true
        file.bytes = null
        assertEquals(KeyFileRead.Missing, source.read())
    }

    @Test
    fun aKeyFileByItsPathIsTheAppsOwn() {
        val own = object : KeyFileSource {
            override val fileName: String = "old.keyx"

            override fun read(): KeyFileRead = KeyFileRead.Found(byteArrayOf(1))
        }
        val keyFiles = KeyFiles(folder = { null }, file = { null }, byPath = { path -> own.takeIf { path == "/storage/emulated/0/old.keyx" }!! })
        assertEquals(own, keyFiles.at(FileLocation.ByPath("/storage/emulated/0/old.keyx")))
    }
}
