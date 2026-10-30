package dev.keething.android.attachment

import dev.keething.domain.secret.Secret
import dev.keething.vault.databases.DatabaseId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentFilesTest {
    private val cache: File = Files.createTempDirectory("attachments-test").toFile()
    private val root = File(cache, "attachments")
    private val files = AttachmentFiles(root)
    private val personal = DatabaseId("P")
    private val work = DatabaseId("W")

    @AfterTest
    fun deleteCache() {
        cache.deleteRecursively()
    }

    @Test
    fun writesTheBytesUnderTheAttachmentsNameInAFolderOfItsOwn() {
        val file = assertNotNull(files.write(personal, files.generation(personal), "id_ed25519.txt", Secret.of("key")))

        assertEquals("id_ed25519.txt", file.name)
        assertEquals(File(root, "P"), file.parentFile.parentFile, "in its database's folder")
        assertContentEquals("key".encodeToByteArray(), file.readBytes())
    }

    @Test
    fun twoAttachmentsOfOneNameDoNotMeet() {
        val first = assertNotNull(files.write(personal, files.generation(personal), "scan.pdf", Secret.of("one")))
        val second = assertNotNull(files.write(personal, files.generation(personal), "scan.pdf", Secret.of("two")))

        assertNotEquals(first.parentFile, second.parentFile)
        assertContentEquals("one".encodeToByteArray(), first.readBytes())
    }

    @Test
    fun aNameCannotLeaveItsFolder() {
        val file = assertNotNull(files.write(personal, files.generation(personal), "../../escape", Secret.of("x")))

        assertEquals(File(root, "P"), file.parentFile.parentFile, "in its database's folder")
        assertEquals("..-..-escape", file.name)
    }

    @Test
    fun clearRemovesEveryFileWritten() {
        files.write(personal, files.generation(personal), "a.txt", Secret.of("a"))
        files.write(personal, files.generation(personal), "b.txt", Secret.of("b"))

        files.clear()

        assertFalse(root.exists())
    }

    @Test
    fun clearRemovesWhatAnEarlierProcessLeft() {
        File(root, "old").mkdirs()
        File(root, "old/left.pdf").writeText("x")

        AttachmentFiles(root).clear()

        assertFalse(root.exists())
    }

    @Test
    fun aWriteAskedForBeforeALockWritesNothing() {
        val generation = files.generation(personal)

        files.clear()

        assertNull(files.write(personal, generation, "late.txt", Secret.of("x")))
        assertFalse(root.exists())
    }

    @Test
    fun aDatabaseLockedAloneRemovesItsOwnFilesOnly() {
        // Each database's secrets go at its own lock (plan AD9: one can lock alone while another stays open).
        val personals = assertNotNull(files.write(personal, files.generation(personal), "a.txt", Secret.of("a")))
        val works = assertNotNull(files.write(work, files.generation(work), "b.txt", Secret.of("b")))

        files.clear(work)

        assertFalse(works.exists())
        assertTrue(personals.exists(), "Personal is still open")
    }

    @Test
    fun aWriteAskedForBeforeItsDatabaseLockedWritesNothingAnothersStillWrites() {
        val personals = files.generation(personal)
        val works = files.generation(work)

        files.clear(work)

        assertNull(files.write(work, works, "late.txt", Secret.of("x")))
        assertNotNull(files.write(personal, personals, "on.txt", Secret.of("x")))
    }

    @Test
    fun writingWorksAgainAfterALock() {
        files.clear()

        assertTrue(assertNotNull(files.write(personal, files.generation(personal), "next.txt", Secret.of("x"))).exists())
    }

    @Test
    fun aWriteThatFailsSaysSo() {
        root.parentFile.mkdirs()
        root.writeText("a file where the folder should be")

        assertFails { files.write(personal, files.generation(personal), "a.txt", Secret.of("x")) }
    }
}
