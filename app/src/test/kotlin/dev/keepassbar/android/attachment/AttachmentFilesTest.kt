package dev.keepassbar.android.attachment

import dev.keepassbar.domain.secret.Secret
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

    @AfterTest
    fun deleteCache() {
        cache.deleteRecursively()
    }

    @Test
    fun writesTheBytesUnderTheAttachmentsNameInAFolderOfItsOwn() {
        val file = assertNotNull(files.write(files.generation, "id_ed25519.txt", Secret.of("key")))

        assertEquals("id_ed25519.txt", file.name)
        assertEquals(root, file.parentFile.parentFile)
        assertContentEquals("key".encodeToByteArray(), file.readBytes())
    }

    @Test
    fun twoAttachmentsOfOneNameDoNotMeet() {
        val first = assertNotNull(files.write(files.generation, "scan.pdf", Secret.of("one")))
        val second = assertNotNull(files.write(files.generation, "scan.pdf", Secret.of("two")))

        assertNotEquals(first.parentFile, second.parentFile)
        assertContentEquals("one".encodeToByteArray(), first.readBytes())
    }

    @Test
    fun aNameCannotLeaveItsFolder() {
        val file = assertNotNull(files.write(files.generation, "../../escape", Secret.of("x")))

        assertEquals(root, file.parentFile.parentFile)
        assertEquals("..-..-escape", file.name)
    }

    @Test
    fun clearRemovesEveryFileWritten() {
        files.write(files.generation, "a.txt", Secret.of("a"))
        files.write(files.generation, "b.txt", Secret.of("b"))

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
        val generation = files.generation

        files.clear()

        assertNull(files.write(generation, "late.txt", Secret.of("x")))
        assertFalse(root.exists())
    }

    @Test
    fun writingWorksAgainAfterALock() {
        files.clear()

        assertTrue(assertNotNull(files.write(files.generation, "next.txt", Secret.of("x"))).exists())
    }

    @Test
    fun aWriteThatFailsSaysSo() {
        root.parentFile.mkdirs()
        root.writeText("a file where the folder should be")

        assertFails { files.write(files.generation, "a.txt", Secret.of("x")) }
    }
}
