package dev.keething.android.storage

import dev.keething.domain.storage.NewFilePlace
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A new file put in place without ever replacing one (keething-spec kdbx.new-file AC8, AC9, kdbx.new-key-file AC3). */
class PathNewFileTest {
    private val folder: File = Files.createTempDirectory("new-file-test").toFile()
    private val target = File(folder, "New.kdbx")
    private val bytes = "the new database".encodeToByteArray()

    @AfterTest
    fun deleteFolder() {
        folder.setWritable(true)
        folder.deleteRecursively()
    }

    @Test
    fun theFileIsMadeOwnerOnlyAndNothingIsLeftBeside() {
        val file = PathNewFile(target, ownerOnly = true)

        assertEquals(NewFilePlace.FREE, file.check())
        file.create(bytes)

        assertContentEquals(bytes, target.readBytes())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(target.toPath()))
        assertEquals(listOf("New.kdbx"), folder.list()!!.toList())
        assertEquals(NewFilePlace.TAKEN, file.check())
    }

    @Test
    fun aFileAlreadyThereIsNeverReplaced() {
        target.writeText("mine")
        val file = PathNewFile(target, ownerOnly = false)

        assertEquals(NewFilePlace.TAKEN, file.check())
        assertEquals("New.kdbx", assertFailsWith<FileAlreadyExistsException> { file.create(bytes) }.file)

        assertEquals("mine", target.readText())
    }

    @Test
    fun aFileThatAppearsAfterTheCheckIsNotReplacedEither() {
        val file = PathNewFile(target, ownerOnly = false)
        assertEquals(NewFilePlace.FREE, file.check())
        target.writeText("synced in meanwhile")

        assertFailsWith<FileAlreadyExistsException> { file.create(bytes) }

        assertEquals("synced in meanwhile", target.readText())
        assertEquals(listOf("New.kdbx"), folder.list()!!.toList())
    }

    @Test
    fun aDanglingLinkAtThePathCountsAsAFileThere() {
        Files.createSymbolicLink(target.toPath(), File(folder, "gone").toPath())
        val file = PathNewFile(target, ownerOnly = false)

        assertEquals(NewFilePlace.TAKEN, file.check())
        assertFailsWith<FileAlreadyExistsException> { file.create(bytes) }

        assertFalse(File(folder, "gone").exists(), "nothing written through the link")
    }

    @Test
    fun aFolderThatIsNotThere() {
        val file = PathNewFile(File(folder, "missing/New.kdbx"), ownerOnly = false)
        assertEquals(NewFilePlace.NO_FOLDER, file.check())
        assertFailsWith<NoSuchFileException> { file.create(bytes) }
    }

    @Test
    fun aKeyFileIsReadOnlyForItsOwner() {
        val key = File(folder, "work.keyx")

        PathNewFile(key, ownerOnly = true).create(bytes, readOnly = true)

        assertEquals(setOf(PosixFilePermission.OWNER_READ), Files.getPosixFilePermissions(key.toPath()))
    }

    @Test
    fun sharedStorageGetsNoPermissionsSet() {
        val before = Files.getPosixFilePermissions(Files.createFile(File(folder, "plain").toPath()))

        PathNewFile(target, ownerOnly = false).create(bytes, readOnly = true)

        assertEquals(before, Files.getPosixFilePermissions(target.toPath()))
        assertTrue(target.canRead())
    }

    @Test
    fun removeCreatedRemovesOnlyTheFileItMade() {
        val file = PathNewFile(target, ownerOnly = false)
        file.removeCreated()
        file.create(bytes)
        file.removeCreated()
        assertFalse(target.exists())

        file.create(bytes)
        target.delete()
        target.writeText("another one now")
        file.removeCreated()
        assertEquals("another one now", target.readText(), "replaced meanwhile: not removed")
    }

    @Test
    fun aFailureAfterTheFileWasCreatedRemovesItAgain() {
        for (failure in listOf(IOException("disk full"), UnsupportedOperationException("no POSIX permissions here"))) {
            val file = PathNewFile(target, ownerOnly = true, setPermissions = { _, _ -> throw failure })

            val thrown = assertFailsWith<IOException> { file.create(bytes) }

            assertTrue(thrown === failure || thrown.cause === failure, "$thrown")
            assertEquals(emptyList(), folder.list()!!.toList(), "nothing left behind")
            file.removeCreated()
        }
    }

    @Test
    fun aFolderThatCannotBeWrittenIsAnErrorAndNothingIsLeftBehind() {
        folder.setWritable(false)
        assertFailsWith<IOException> { PathNewFile(target, ownerOnly = false).create(bytes) }
        folder.setWritable(true)
        assertEquals(emptyList(), folder.list()!!.toList())
    }
}
