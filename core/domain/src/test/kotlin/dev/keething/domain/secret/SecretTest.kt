package dev.keething.domain.secret

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretTest {
    @Test
    fun holdsTheUtf8BytesOfText() {
        val secret = Secret.of("pässwört")

        assertEquals("pässwört", secret.reveal())
        assertEquals("pässwört".encodeToByteArray().size, secret.size)
        assertTrue(Secret.of("").isEmpty())
    }

    @Test
    fun aWipeZeroesTheOneBufferForEveryHolder() {
        val secret = Secret.of("correct horse")
        val alias = secret

        secret.wipe()

        assertTrue(alias.isWiped)
        alias.useBytes { bytes -> assertTrue(bytes.all { it == 0.toByte() }) }
        assertEquals("correct horse".length, alias.size, "the length stays; only the bytes go")
    }

    @Test
    fun aCopyHasABufferOfItsOwn() {
        val secret = Secret.of("pw")
        val copy = secret.copy()

        secret.wipe()

        assertEquals("pw", copy.reveal())
        assertFalse(copy.isWiped)
    }

    @Test
    fun adoptTakesTheBufferAndCopyOfCopiesARange() {
        val buffer = byteArrayOf(1, 2, 3, 4)
        val adopted = Secret.adopt(buffer)
        val range = Secret.copyOf(buffer, 1, 3)

        adopted.wipe()

        assertContentEquals(byteArrayOf(0, 0, 0, 0), buffer)
        range.useBytes { assertContentEquals(byteArrayOf(2, 3), it) }
    }

    @Test
    fun comparedByTheirBytes() {
        assertEquals(Secret.of("a"), Secret.of("a"))
        assertNotEquals(Secret.of("a"), Secret.of("b"))
        assertNotEquals(Secret.of("a"), Secret.of("ab"))
    }

    @Test
    fun neverShowsTheBytesAsText() {
        val secret = Secret.of("hunter2")

        assertFalse(secret.toString().contains("hunter2"))
        secret.wipe()
        assertEquals("Secret(wiped)", secret.toString())
    }
}
