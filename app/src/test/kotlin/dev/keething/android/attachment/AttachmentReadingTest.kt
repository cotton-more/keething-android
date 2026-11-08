package dev.keething.android.attachment

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/** A picked file read up to the limit (keething-spec edit.attachments AC2). */
class AttachmentReadingTest {
    private val limit = 3 shl 20
    private val bytes = Random(7).nextBytes(limit)

    /** Hands out at most [step] bytes a read, as a provider's pipe does. */
    private class Trickle(private val bytes: ByteArray, private val step: Int = 4096) : InputStream() {
        private var at = 0

        override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xff else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at == bytes.size) return -1
            val n = minOf(len, step, bytes.size - at)
            bytes.copyInto(b, off, at, at + n)
            at += n
            return n
        }
    }

    @Test
    fun aFileUpToTheLimitIsReadWholeWhateverItsProviderSays() {
        for (expected in listOf(null, limit.toLong(), 1000L, 0L)) {
            assertContentEquals(bytes, AttachmentReading.readAtMost(Trickle(bytes), limit, expected), "said $expected")
        }
        assertContentEquals(ByteArray(0), AttachmentReading.readAtMost(ByteArrayInputStream(ByteArray(0)), limit, null))
    }

    @Test
    fun aFileOverTheLimitIsRefusedEvenWhenItsProviderSaysLess() {
        val over = bytes + byteArrayOf(1)

        assertNull(AttachmentReading.readAtMost(Trickle(over), limit, null))
        assertNull(AttachmentReading.readAtMost(Trickle(over), limit, 10L))
        assertNull(AttachmentReading.readAtMost(ByteArrayInputStream(ByteArray(0)), limit, limit + 1L), "refused before a byte is read")
    }
}
