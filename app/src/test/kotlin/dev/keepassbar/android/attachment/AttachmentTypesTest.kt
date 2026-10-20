package dev.keepassbar.android.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentTypesTest {
    private val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\n"

    @Test
    fun theExtensionDecides() {
        assertEquals("application/pdf", AttachmentTypes.forOpening("application/pdf", key.encodeToByteArray()))
    }

    @Test
    fun textWithoutAKnownExtensionOpensAsText() {
        assertEquals(AttachmentTypes.TEXT, AttachmentTypes.forOpening(null, key.encodeToByteArray()))
        assertEquals(AttachmentTypes.TEXT, AttachmentTypes.forOpening(null, "Коды:\r\n1\t2\n".encodeToByteArray()))
    }

    @Test
    fun anythingElseIsGeneric() {
        assertEquals(AttachmentTypes.GENERIC, AttachmentTypes.forOpening(null, byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
    }

    @Test
    fun whatIsText() {
        assertTrue(AttachmentTypes.isText(ByteArray(0)))
        assertFalse(AttachmentTypes.isText(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte())), "a NUL")
        assertFalse(AttachmentTypes.isText(byteArrayOf(0xD0.toByte())), "a cut UTF-8 sequence")
        assertFalse(AttachmentTypes.isText(byteArrayOf(0x1B, '['.code.toByte())), "an escape")
        assertFalse(AttachmentTypes.isText(ByteArray((1 shl 20) + 1) { 'a'.code.toByte() }), "too large to check")
    }
}
