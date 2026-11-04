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
    /** [checkpoint] is called between steps: what it throws stops the derivation (keething-spec kdbx.hardening AC6). */
    abstract fun transform(composite: ByteArray, checkpoint: () -> Unit = {}): ByteArray

    /** [rounds] as the file stores them, unsigned: above `Long.MAX_VALUE` the number is negative. */
    class Aes(identity: ByteArray, private val rounds: Long, private val seed: ByteArray) : KdfParameters(identity) {
        override fun transform(composite: ByteArray, checkpoint: () -> Unit): ByteArray {
            if (seed.size != 32) throw KdbxException.Corrupt("AES-KDF seed")
            val block = composite.copyOf()
            try {
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(seed, "AES"))
                var left = rounds.toULong()
                while (left > 0uL) {
                    checkpoint()
                    val now = minOf(left, CHECKPOINT_ROUNDS)
                    for (round in 0uL until now) cipher.update(block, 0, 32, block, 0)
                    left -= now
                }
                return MessageDigest.getInstance("SHA-256").digest(block)
            } finally {
                block.fill(0)
            }
        }

        private companion object {
            /** About a millisecond's rounds between two checkpoints. */
            val CHECKPOINT_ROUNDS = 1uL shl 12
        }
    }

    /**
     * Refused as unsupported when the memory it asks for is more than the app can have (keething-spec kdbx.hardening
     * AC6): the phone gives an app a few hundred MiB, KeePass allows up to 2 GiB; the allocation is never left to
     * run the app out of memory.
     */
    class Argon2(identity: ByteArray, private val parameters: Argon2Hash.Parameters, private val salt: ByteArray) :
        KdfParameters(identity) {
        override fun transform(composite: ByteArray, checkpoint: () -> Unit): ByteArray {
            val memory = parameters.memoryKiB.toLong() * 1024
            val most = AppMemory.most()
            if (memory > most - HEADROOM) throw tooMuch(memory, most)
            return try {
                Argon2Hash.hash(composite, salt, parameters, checkpoint = checkpoint)
            } catch (_: IllegalArgumentException) {
                throw KdbxException.Corrupt("Argon2 parameters")
            } catch (_: OutOfMemoryError) {
                throw tooMuch(memory, most)
            }
        }

        private fun tooMuch(memory: Long, most: Long) = KdbxException.NotEnoughMemory(
            "Argon2 memory of ${KdfLimits.size(memory.toULong())}, more than the ${KdfLimits.size(most.toULong())} this phone gives Keething",
        )

        private companion object {
            /** What the rest of the app needs beside Argon2's memory while a database opens. */
            const val HEADROOM = 48L shl 20
        }
    }
}

/** The most memory the app may have (the phone's heap limit for it); a test sets less. */
internal object AppMemory {
    @Volatile
    var most: () -> Long = { Runtime.getRuntime().maxMemory() }
}
