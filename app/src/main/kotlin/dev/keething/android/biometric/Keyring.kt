package dev.keething.android.biometric

import dev.keething.domain.secret.Secret
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * The keyring (SPEC §5; plan AD5, chosen after the spike on the Pixel 6): a software RSA-2048 key pair whose public key
 * seals each database's password ([SealedPassword]), and whose private key is itself sealed for the Keystore's key,
 * which only a fingerprint unlocks, for one operation. One prompt opens the private key, and the private key any
 * database's password; storing needs the public key alone, so it never asks for a fingerprint. It names the Keystore
 * key it was sealed for, so one made for a key since replaced is known before any prompt, and a refusal of the right
 * key is no reason to give everything up.
 *
 * `KPBR` · version 1 · SHA-256 of the Keystore's public key (X.509) · the public key's length (2 bytes, big-endian) · the
 * public key (X.509) · the private key (PKCS#8) sealed as a [SealedPassword].
 */
internal class Keyring private constructor(
    private val keystoreKey: ByteArray,
    val publicKey: PublicKey,
    private val sealedPrivateKey: ByteArray,
) {
    fun encoded(): ByteArray {
        val public = publicKey.encoded
        return ByteBuffer.allocate(HEADER.size + HASH_BYTES + 2 + public.size + sealedPrivateKey.size)
            .put(HEADER).put(keystoreKey).putShort(public.size.toShort()).put(public).put(sealedPrivateKey).array()
    }

    /** It was sealed for [keystoreKey], the Keystore's public key. */
    fun isFor(keystoreKey: PublicKey): Boolean = MessageDigest.isEqual(this.keystoreKey, hash(keystoreKey))

    /**
     * The private key, opened with [rsa], the Keystore's cipher the fingerprint has just unlocked for one operation. It
     * is a Java key object: dropped after the unlock, not wiped. Throws [UnwrapRefusedException] when the Keystore
     * refused, and [UnreadableSealException] when the rest does not open.
     */
    fun privateKey(rsa: Cipher): PrivateKey {
        val encoded = SealedPassword.open(sealedPrivateKey, rsa)
        return try {
            encoded.useBytes { KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(it)) }
        } catch (failure: GeneralSecurityException) {
            throw UnreadableSealException("not a private key", failure)
        } finally {
            encoded.wipe()
        }
    }

    companion object {
        private val HEADER = "KPBR".encodeToByteArray() + 1
        private const val HASH_BYTES = 32
        private const val ALGORITHM = "RSA"

        /** A new key pair, its private key sealed for [keystoreKey], the public half of the Keystore's key. */
        fun make(keystoreKey: PublicKey): Keyring {
            val pair = KeyPairGenerator.getInstance(ALGORITHM).apply { initialize(2048) }.generateKeyPair()
            val encoded = Secret.adopt(pair.private.encoded)
            return try {
                Keyring(hash(keystoreKey), pair.public, SealedPassword.seal(encoded, keystoreKey))
            } finally {
                encoded.wipe()
            }
        }

        /** Throws [UnreadableSealException] when [bytes] are not a keyring. */
        fun read(bytes: ByteArray): Keyring {
            if (bytes.size < HEADER.size + HASH_BYTES + 2 || !bytes.copyOf(HEADER.size).contentEquals(HEADER)) {
                throw UnreadableSealException("not a keyring")
            }
            val buffer = ByteBuffer.wrap(bytes, HEADER.size, bytes.size - HEADER.size)
            val keystoreKey = ByteArray(HASH_BYTES).also { buffer.get(it) }
            val publicSize = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() <= publicSize) throw UnreadableSealException("cut short")
            val public = ByteArray(publicSize).also { buffer.get(it) }
            val publicKey = try {
                KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(public))
            } catch (failure: GeneralSecurityException) {
                throw UnreadableSealException("not a public key", failure)
            }
            return Keyring(keystoreKey, publicKey, ByteArray(buffer.remaining()).also { buffer.get(it) })
        }

        /** A cipher opening the passwords sealed for the keyring, with its [privateKey]. */
        fun opening(privateKey: PrivateKey): Cipher =
            Cipher.getInstance(SealedPassword.RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, privateKey, SealedPassword.OAEP) }

        private fun hash(key: PublicKey): ByteArray = MessageDigest.getInstance("SHA-256").digest(key.encoded)
    }
}
