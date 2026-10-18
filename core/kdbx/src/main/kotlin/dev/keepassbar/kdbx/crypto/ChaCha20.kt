package dev.keepassbar.kdbx.crypto

/**
 * ChaCha20 (RFC 8439: 96-bit nonce, 32-bit block counter starting at 0): the outer cipher KDBX 4 may use and
 * the stream that hides protected values inside its XML. Encrypting and decrypting are the same XOR.
 */
internal class ChaCha20(key: ByteArray, nonce: ByteArray) {
    private val state = IntArray(16)
    private val x = IntArray(16)
    private val block = ByteArray(64)
    private var used = 64

    init {
        require(key.size == 32 && nonce.size == 12)
        state[0] = 0x61707865; state[1] = 0x3320646e; state[2] = 0x79622d32; state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = Bytes.intLe(key, 4 * i)
        for (i in 0 until 3) state[13 + i] = Bytes.intLe(nonce, 4 * i)
    }

    /** XORs the keystream into [bytes], continuing where the last call stopped. */
    fun apply(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
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

    fun wipe() {
        state.fill(0)
        x.fill(0)
        block.fill(0)
    }

    private fun nextBlock() {
        state.copyInto(x)
        repeat(10) {
            quarter(0, 4, 8, 12); quarter(1, 5, 9, 13); quarter(2, 6, 10, 14); quarter(3, 7, 11, 15)
            quarter(0, 5, 10, 15); quarter(1, 6, 11, 12); quarter(2, 7, 8, 13); quarter(3, 4, 9, 14)
        }
        for (i in 0 until 16) Bytes.putIntLe(block, 4 * i, x[i] + state[i])
        state[12]++
        used = 0
    }

    private fun quarter(a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }
}
