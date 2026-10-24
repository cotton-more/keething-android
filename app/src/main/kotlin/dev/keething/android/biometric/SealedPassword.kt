package dev.keething.android.biometric

import dev.keething.domain.secret.Secret
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** The bytes are not a sealed password, were changed, or this key does not open them. */
internal class UnreadableSealException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The stored master password (SPEC §5): a random AES-256 key encrypts it with GCM, and the Keystore's RSA
 * public key wraps that AES key with OAEP (RSA alone would take at most 190 bytes). Sealing needs only the
 * public key, so storing never asks for a fingerprint; opening needs the private key, which the Keystore
 * uses only right after one.
 *
 * `KPBS` · version 1 · wrapped key's length (2 bytes, big-endian) · wrapped key · 12-byte IV · ciphertext with
 * its 16-byte tag. The magic and the version are the GCM's additional data.
 */
internal object SealedPassword {
    const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

    /**
     * Given on both sides: providers assume different MGF1 digests for this transformation, and the Keystore
     * takes SHA-1 for MGF1 on every Android version.
     */
    val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    private val HEADER = "KPBS".encodeToByteArray() + 1
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun seal(password: Secret, publicKey: PublicKey): ByteArray {
        val aesKey = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            val rsa = Cipher.getInstance(RSA_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, publicKey, OAEP) }
            val wrapped = rsa.doFinal(aesKey)
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val encrypted = password.useBytes { gcm(Cipher.ENCRYPT_MODE, aesKey, iv).doFinal(it) }
            return ByteBuffer.allocate(HEADER.size + 2 + wrapped.size + iv.size + encrypted.size)
                .put(HEADER).putShort(wrapped.size.toShort()).put(wrapped).put(iv).put(encrypted).array()
        } finally {
            aesKey.fill(0)
        }
    }

    /**
     * [rsa]: a cipher for the private key, set to decrypt with [OAEP] (on the phone, the fingerprint has just
     * unlocked it for one operation). The caller wipes the password.
     */
    fun open(sealed: ByteArray, rsa: Cipher): Secret {
        if (sealed.size < HEADER.size + 2 || !sealed.copyOf(HEADER.size).contentEquals(HEADER)) {
            throw UnreadableSealException("not a sealed password")
        }
        val buffer = ByteBuffer.wrap(sealed, HEADER.size, sealed.size - HEADER.size)
        val wrappedSize = buffer.short.toInt() and 0xFFFF
        if (buffer.remaining() < wrappedSize + IV_BYTES + TAG_BITS / 8) throw UnreadableSealException("cut short")
        val wrapped = ByteArray(wrappedSize).also { buffer.get(it) }
        val iv = ByteArray(IV_BYTES).also { buffer.get(it) }
        val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
        var aesKey: ByteArray? = null
        try {
            aesKey = rsa.doFinal(wrapped)
            if (aesKey.size != KEY_BYTES) throw UnreadableSealException("not a sealed password")
            return Secret.adopt(gcm(Cipher.DECRYPT_MODE, aesKey, iv).doFinal(encrypted))
        } catch (failure: GeneralSecurityException) {
            throw UnreadableSealException("does not open (${failure.javaClass.simpleName})", failure)
        } finally {
            aesKey?.fill(0)
        }
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        updateAAD(HEADER)
    }
}
