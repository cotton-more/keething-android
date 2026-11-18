package dev.keething.sharing.crypto

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * X25519 (RFC 7748) with raw 32-byte keys, done by the platform's provider (the JDK's SunEC in tests, Conscrypt
 * on Android 13+, both under the name `XDH`). The raw keys go in behind the fixed X.509 and PKCS #8 prefixes
 * of the X25519 algorithm, which both providers read; a private key is 32 random bytes, which the function
 * clamps itself, so no provider-specific encoding is ever read back.
 *
 * The provider's key objects keep their own copy of a private key until they are collected: they cannot be
 * wiped (accepted, as for a `String` that is shown, SPEC §4).
 */
internal object X25519 {
    const val KEY_SIZE = 32

    private val PUBLIC_PREFIX = hex("302a300506032b656e032100")
    private val PRIVATE_PREFIX = hex("302e020100300506032b656e04220420")
    private val BASE_POINT = ByteArray(KEY_SIZE).also { it[0] = 9 }

    fun newPrivateKey(random: SecureRandom): ByteArray = ByteArray(KEY_SIZE).also(random::nextBytes)

    fun publicKey(privateKey: ByteArray): ByteArray = sharedSecret(privateKey, BASE_POINT)

    /** Whether [publicKey] is a point of large order, one an answer can be sealed for (tried with a fixed scalar). */
    fun isUsable(publicKey: ByteArray): Boolean =
        publicKey.size == KEY_SIZE && runCatching { sharedSecret(PROBE, publicKey) }.isSuccess

    private val PROBE = ByteArray(KEY_SIZE) { 0x55 }

    /** X25519(private, public); a public key of small order (an all-zero result) is refused. */
    fun sharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_SIZE && publicKey.size == KEY_SIZE)
        val pkcs8 = PRIVATE_PREFIX + privateKey
        try {
            val factory = KeyFactory.getInstance("XDH")
            val agreement = KeyAgreement.getInstance("XDH")
            agreement.init(factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)))
            agreement.doPhase(factory.generatePublic(X509EncodedKeySpec(PUBLIC_PREFIX + publicKey)), true)
            val secret = agreement.generateSecret()
            if (secret.all { it == 0.toByte() }) throw SealFailure("a public key of small order") // English detail
            return secret
        } catch (failure: GeneralSecurityException) {
            throw SealFailure(failure.message ?: "X25519")
        } catch (failure: RuntimeException) {
            // The JDK refuses a small-order point with an IllegalStateException; whatever a provider throws, the key does not fit.
            throw SealFailure(failure.message ?: "X25519")
        } finally {
            pkcs8.fill(0)
        }
    }
}

/** Sealing or opening failed: a key that does not fit, or a message changed or not meant for this key. */
internal class SealFailure(message: String) : Exception(message)

internal fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
