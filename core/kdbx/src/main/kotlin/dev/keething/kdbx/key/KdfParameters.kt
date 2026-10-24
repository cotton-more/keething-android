package dev.keething.kdbx.key

import dev.keething.kdbx.crypto.Argon2 as Argon2Hash
import dev.keething.kdbx.reader.KdbxException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * A file's key derivation with its parameters. [identity] is the header's raw parameter field (algorithm,
 * salt and costs): two files with the same identity turn a key into the same derived key.
 */
internal sealed class KdfParameters(val identity: ByteArray) {
    abstract fun transform(composite: ByteArray): ByteArray

    class Aes(identity: ByteArray, private val rounds: Long, private val seed: ByteArray) : KdfParameters(identity) {
        override fun transform(composite: ByteArray): ByteArray {
            if (seed.size != 32) throw KdbxException.Corrupt("AES-KDF seed")
            val block = composite.copyOf()
            try {
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(seed, "AES"))
                for (round in 0 until rounds) cipher.update(block, 0, 32, block, 0)
                return MessageDigest.getInstance("SHA-256").digest(block)
            } finally {
                block.fill(0)
            }
        }
    }

    class Argon2(identity: ByteArray, private val parameters: Argon2Hash.Parameters, private val salt: ByteArray) :
        KdfParameters(identity) {
        override fun transform(composite: ByteArray): ByteArray =
            try {
                Argon2Hash.hash(composite, salt, parameters)
            } catch (_: IllegalArgumentException) {
                throw KdbxException.Corrupt("Argon2 parameters")
            }
    }
}
