package dev.keething.sharing.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869), the two steps HPKE labels separately. */
internal object Hkdf {
    const val HASH_SIZE = 32

    /** An empty salt is the hash's length of zeros (RFC 5869 §2.2); the platform refuses an empty HMAC key. */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmac(if (salt.isEmpty()) ByteArray(HASH_SIZE) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..255 * HASH_SIZE)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var done = 0
        var counter = 1
        while (done < length) {
            val next = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            block.fill(0)
            block = next
            val take = minOf(HASH_SIZE, length - done)
            block.copyInto(out, done, 0, take)
            done += take
            counter++
        }
        block.fill(0)
        return out
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }
}
