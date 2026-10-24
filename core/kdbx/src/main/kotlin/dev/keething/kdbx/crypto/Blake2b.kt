package dev.keething.kdbx.crypto

/** BLAKE2b (RFC 7693), unkeyed, any digest length from 1 to 64 bytes: the hash inside Argon2. */
internal class Blake2b(private val length: Int = 64) {
    private val h = IV.copyOf()
    private val buffer = ByteArray(BLOCK)
    private var buffered = 0
    private var counter = 0L
    private val m = LongArray(16)
    private val v = LongArray(16)

    init {
        require(length in 1..64)
        h[0] = h[0] xor (0x0101_0000L xor length.toLong())
    }

    fun update(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        var position = offset
        val end = offset + count
        while (position < end) {
            // A full buffer is compressed only when more input follows: the last block is special.
            if (buffered == BLOCK) {
                counter += BLOCK
                compress(last = false)
                buffered = 0
            }
            val n = minOf(BLOCK - buffered, end - position)
            bytes.copyInto(buffer, buffered, position, position + n)
            buffered += n
            position += n
        }
    }

    fun update(value: Int) {
        update(byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte()))
    }

    fun finish(): ByteArray {
        counter += buffered
        buffer.fill(0, buffered)
        compress(last = true)
        val out = ByteArray(64)
        for (i in 0 until 8) Bytes.putLongLe(out, i * 8, h[i])
        buffer.fill(0)
        h.fill(0)
        return if (length == 64) out else out.copyOf(length).also { out.fill(0) }
    }

    private fun compress(last: Boolean) {
        for (i in 0 until 16) m[i] = Bytes.longLe(buffer, i * 8)
        h.copyInto(v)
        IV.copyInto(v, 8)
        v[12] = v[12] xor counter
        if (last) v[14] = v[14].inv()
        for (round in 0 until 12) {
            val s = SIGMA[round % 10]
            g(0, 4, 8, 12, m[s[0]], m[s[1]]); g(1, 5, 9, 13, m[s[2]], m[s[3]])
            g(2, 6, 10, 14, m[s[4]], m[s[5]]); g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]]); g(1, 6, 11, 12, m[s[10]], m[s[11]])
            g(2, 7, 8, 13, m[s[12]], m[s[13]]); g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x; v[d] = (v[d] xor v[a]).rotateRight(32)
        v[c] = v[c] + v[d]; v[b] = (v[b] xor v[c]).rotateRight(24)
        v[a] = v[a] + v[b] + y; v[d] = (v[d] xor v[a]).rotateRight(16)
        v[c] = v[c] + v[d]; v[b] = (v[b] xor v[c]).rotateRight(63)
    }

    companion object {
        private const val BLOCK = 128

        fun hash(data: ByteArray, length: Int = 64): ByteArray = Blake2b(length).apply { update(data) }.finish()

        private val IV = listOf(
            0x6a09e667f3bcc908uL, 0xbb67ae8584caa73buL, 0x3c6ef372fe94f82buL, 0xa54ff53a5f1d36f1uL,
            0x510e527fade682d1uL, 0x9b05688c2b3e6c1fuL, 0x1f83d9abfb41bd6buL, 0x5be0cd19137e2179uL,
        ).map { it.toLong() }.toLongArray()

        private val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        )
    }
}
