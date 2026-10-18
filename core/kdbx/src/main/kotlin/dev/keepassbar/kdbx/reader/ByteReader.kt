package dev.keepassbar.kdbx.reader

import dev.keepassbar.kdbx.crypto.Bytes

/** Little-endian reading with bounds checks: null past the end. */
internal class ByteReader(private val data: ByteArray, start: Int = 0, private val end: Int = data.size) {
    var position: Int = start
        private set

    /** Skips [count] bytes; false when there are not that many. */
    fun skip(count: Int): Boolean {
        if (count < 0 || count > end - position) return false
        position += count
        return true
    }

    fun bytes(count: Int): ByteArray? {
        val from = position
        if (!skip(count)) return null
        return data.copyOfRange(from, from + count)
    }

    fun uint8(): Int? = if (position < end) data[position++].toInt() and 0xFF else null

    fun uint16(): Int? {
        if (end - position < 2) return null
        return ((data[position].toInt() and 0xFF) or ((data[position + 1].toInt() and 0xFF) shl 8))
            .also { position += 2 }
    }

    /** An unsigned 32-bit number; as an Int it is negative above 2^31 - 1. */
    fun int32(): Int? {
        if (end - position < 4) return null
        return Bytes.intLe(data, position).also { position += 4 }
    }

    /** A size field: null when it is beyond what an array can hold. */
    fun size(): Int? = int32()?.takeIf { it >= 0 }
}
