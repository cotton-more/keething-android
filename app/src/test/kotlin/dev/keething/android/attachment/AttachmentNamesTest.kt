package dev.keething.android.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentNamesTest {
    @Test
    fun keepsAnOrdinaryName() {
        assertEquals("Passport scan 2019.pdf", AttachmentNames.fileName("Passport scan 2019.pdf"))
        assertEquals("Счёт.pdf", AttachmentNames.fileName("Счёт.pdf"))
    }

    @Test
    fun noFoldersAndNoControlCharacters() {
        assertEquals("a-b-c.txt", AttachmentNames.fileName("a/b:c.txt"))
        assertEquals("ab.txt", AttachmentNames.fileName("a\u0000b\n.txt"))
        assertEquals("key.txt", AttachmentNames.fileName("  key.txt  "))
    }

    @Test
    fun neverEmptyNorAFolderName() {
        for (name in listOf("", "   ", ".", "..", "\u0000")) assertEquals("attachment", AttachmentNames.fileName(name), name)
    }

    @Test
    fun aLongNameIsShortenedAndKeepsItsExtension() {
        val name = "ж".repeat(200) + ".pdf"

        val shortened = AttachmentNames.fileName(name)

        assertTrue(shortened.encodeToByteArray().size <= 255)
        assertTrue(shortened.endsWith("ж.pdf"))
    }

    @Test
    fun aLongNameIsNotCutInsideACharacter() {
        val shortened = AttachmentNames.fileName("😀".repeat(100) + ".png")

        assertTrue(shortened.encodeToByteArray().size <= 255)
        assertEquals(shortened, shortened.encodeToByteArray().decodeToString(), "no broken surrogate pair")
        assertTrue(shortened.removeSuffix(".png").all { it.isSurrogate() } && shortened.removeSuffix(".png").length % 2 == 0)
    }

    @Test
    fun theExtensionIsLowerCaseAndOptional() {
        assertEquals("pdf", AttachmentNames.extension("Scan.PDF"))
        assertEquals("gz", AttachmentNames.extension("backup.tar.gz"))
        assertEquals("", AttachmentNames.extension("id_ed25519"))
        assertEquals("", AttachmentNames.extension(".hidden"))
        assertEquals("", AttachmentNames.extension("trailing."))
    }
}
