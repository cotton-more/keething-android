package dev.keething.kdbx.crypto

/**
 * Salsa20/20 (Bernstein: 256-bit key, 64-bit nonce, 64-bit block counter starting at 0): the stream that hides
 * protected values inside a KDBX 3.1 file's XML. Encrypting and decrypting are the same XOR.
 */
internal class Salsa20(key: ByteArray, nonce: ByteArray) : KeyStream {
    private val state = IntArray(16)
    private val x = IntArray(16)
    private val block = ByteArray(64)
    private var used = 64

    init {
        require(key.size == 32 && nonce.size == 8)
        state[0] = 0x61707865; state[5] = 0x3320646e; state[10] = 0x79622d32; state[15] = 0x6b206574
        for (i in 0 until 4) {
            state[1 + i] = Bytes.intLe(key, 4 * i)
            state[11 + i] = Bytes.intLe(key, 16 + 4 * i)
        }
        state[6] = Bytes.intLe(nonce, 0)
        state[7] = Bytes.intLe(nonce, 4)
    }

    override fun apply(bytes: ByteArray, offset: Int, count: Int) {
        var i = offset
        val end = offset + count
        while (i < end) {
            if (used == 64) nextBlock()
            val n = minOf(64 - used, end - i)
            for (j in 0 until n) bytes[i + j] = (bytes[i + j].toInt() xor block[used + j].toInt()).toByte()
            used += n
            i += n
        }
    }

    override fun wipe() {
        state.fill(0)
        x.fill(0)
        block.fill(0)
    }

    private fun nextBlock() {
        state.copyInto(x)
        repeat(10) {
            quarter(0, 4, 8, 12); quarter(5, 9, 13, 1); quarter(10, 14, 2, 6); quarter(15, 3, 7, 11)
            quarter(0, 1, 2, 3); quarter(5, 6, 7, 4); quarter(10, 11, 8, 9); quarter(15, 12, 13, 14)
        }
        for (i in 0 until 16) Bytes.putIntLe(block, 4 * i, x[i] + state[i])
        if (++state[8] == 0) state[9]++
        used = 0
    }

    private fun quarter(a: Int, b: Int, c: Int, d: Int) {
        x[b] = x[b] xor (x[a] + x[d]).rotateLeft(7)
        x[c] = x[c] xor (x[b] + x[a]).rotateLeft(9)
        x[d] = x[d] xor (x[c] + x[b]).rotateLeft(13)
        x[a] = x[a] xor (x[d] + x[c]).rotateLeft(18)
    }
}
