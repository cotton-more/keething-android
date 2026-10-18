package dev.keepassbar.kdbx.crypto

/** Little-endian numbers in byte arrays, as KDBX and the hashes inside it store them. */
internal object Bytes {
    fun intLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    fun longLe(bytes: ByteArray, offset: Int): Long =
        (intLe(bytes, offset).toLong() and 0xFFFF_FFFFL) or (intLe(bytes, offset + 4).toLong() shl 32)

    fun putIntLe(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }

    fun putLongLe(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }

    fun le32(value: Int): ByteArray = ByteArray(4).also { putIntLe(it, 0, value) }

    fun le64(value: Long): ByteArray = ByteArray(8).also { putLongLe(it, 0, value) }
}
