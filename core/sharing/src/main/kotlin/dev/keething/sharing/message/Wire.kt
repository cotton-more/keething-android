package dev.keething.sharing.message

import dev.keething.domain.secret.Secret
import java.time.Instant

/** A code, an answer's content or the stored requests that cannot be read: changed, cut short or not ours. */
public class MalformedShareException(message: String) : Exception(message)

/**
 * Writes the sharing's binary formats: bytes, big-endian 64-bit numbers, and fields with their length first
 * (unsigned LEB128). A buffer that grows wipes the one it leaves, since an answer's content holds passwords.
 */
internal class WireWriter(capacity: Int = 256) {
    private var buffer = ByteArray(capacity)

    /** The bytes written so far. */
    var size: Int = 0
        private set

    fun byte(value: Int) {
        room(1)
        buffer[size++] = value.toByte()
    }

    fun bytes(value: ByteArray) {
        room(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun long(value: Long) {
        for (i in 7 downTo 0) byte((value ushr (8 * i)).toInt())
    }

    fun count(value: Int) {
        require(value >= 0)
        var rest = value
        while (rest >= 0x80) {
            byte((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        byte(rest)
    }

    fun field(value: ByteArray) {
        count(value.size)
        bytes(value)
    }

    fun text(value: String) = field(value.encodeToByteArray())

    fun secret(value: Secret) = value.useBytes(::field)

    /** A copy; [wipe] the writer afterwards when it held secrets. */
    fun toByteArray(): ByteArray = buffer.copyOf(size)

    /** The bytes as a secret, the writer wiped. */
    fun toSecret(): Secret = Secret.adopt(toByteArray()).also { wipe() }

    fun wipe() {
        buffer.fill(0)
        size = 0
    }

    private fun room(more: Int) {
        if (size + more <= buffer.size) return
        val bigger = buffer.copyOf(maxOf(buffer.size * 2, size + more))
        buffer.fill(0)
        buffer = bigger
    }
}

/** Reads what [WireWriter] wrote, refusing anything out of bounds, overlong or left over. */
internal class WireReader(private val bytes: ByteArray, private var at: Int = 0) {
    val remaining: Int get() = bytes.size - at

    fun byte(): Int {
        need(1)
        return bytes[at++].toInt() and 0xFF
    }

    fun bytes(count: Int): ByteArray {
        need(count)
        return bytes.copyOfRange(at, at + count).also { at += count }
    }

    fun long(): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or byte().toLong() }
        return value
    }

    /** Seconds since 1970 within the years 1 to 9999, the range KeePass's dates have; else malformed. */
    fun instant(): Instant {
        val seconds = long()
        if (seconds !in FIRST_SECOND..LAST_SECOND) throw MalformedShareException("a date out of range")
        return Instant.ofEpochSecond(seconds)
    }

    /** A count up to [max]; the shortest encoding only, so one message has one form. */
    fun count(max: Int): Int {
        var value = 0L
        var shift = 0
        while (true) {
            val b = byte()
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                if (b == 0 && shift > 0) throw MalformedShareException("an overlong number")
                break
            }
            shift += 7
            if (shift > 28) throw MalformedShareException("a number too large")
        }
        if (value > max) throw MalformedShareException("a number too large")
        return value.toInt()
    }

    fun field(maxBytes: Int): ByteArray = bytes(count(maxBytes))

    fun text(maxBytes: Int): String = try {
        field(maxBytes).decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        throw MalformedShareException("text that is not UTF-8")
    }

    fun secret(maxBytes: Int): Secret {
        val length = count(maxBytes)
        need(length)
        return Secret.copyOf(bytes, at, at + length).also { at += length }
    }

    fun end() {
        if (at != bytes.size) throw MalformedShareException("bytes left over")
    }

    private fun need(count: Int) {
        if (count < 0 || count > bytes.size - at) throw MalformedShareException("cut short")
    }

    private companion object {
        /** 0001-01-01T00:00:00Z and 9999-12-31T23:59:59Z. */
        const val FIRST_SECOND = -62_135_596_800L
        const val LAST_SECOND = 253_402_300_799L
    }
}
