package dev.keething.sharing.requests

import dev.keething.kdbx.crypto.Argon2
import dev.keething.sharing.crypto.Hkdf
import dev.keething.sharing.crypto.SealFailure
import dev.keething.sharing.message.MalformedShareException
import dev.keething.sharing.message.ShareEnvelope
import dev.keething.sharing.message.ShareRequest
import dev.keething.sharing.payload.AnswerPayload
import dev.keething.sharing.pictures.Pictures
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealing an envelope (SPEC §10): the same content as an answer, under a key made from its ten pictures. Argon2id of the
 * ten indices (a byte each) with the envelope's salt, with the pictures' own parameters (64 MiB, 3 passes, 2 lanes);
 * then HKDF-SHA256 (no salt, `info` "Keething envelope v1") gives the AES-256-GCM key and the 12-byte nonce. Each key
 * seals one envelope, as the salt is new each time. The header is the associated data.
 *
 * The pictures are the whole secret: whoever holds the code tries them offline, 2^59 on average, each 64 MiB of
 * Argon2id. Slow on purpose, about 0.3 s: call it off the main thread.
 */
internal object Envelopes {
    private val INFO = "Keething envelope v1".encodeToByteArray() // not user text

    fun seal(payload: AnswerPayload, pictures: List<Int>, created: Instant, validFor: Duration, random: SecureRandom): ShareEnvelope =
        seal(payload, pictures, created, validFor, ByteArray(ShareEnvelope.SALT_SIZE).also(random::nextBytes))

    /** With a given salt: the vector another implementation checks against (`InteropVectorTest`). */
    fun seal(payload: AnswerPayload, pictures: List<Int>, created: Instant, validFor: Duration, salt: ByteArray): ShareEnvelope {
        require(!validFor.isNegative && !validFor.isZero && validFor <= ShareRequest.MAX_VALIDITY)
        val header = ShareEnvelope.header(created, validFor, salt)
        val plain = payload.encode()
        try {
            val ciphertext = plain.useBytes { aead(Cipher.ENCRYPT_MODE, pictures, salt, header, it) }
            return ShareEnvelope.decode(header + ciphertext)
        } finally {
            plain.wipe()
        }
    }

    /**
     * The payload, the caller's to wipe. A [SealFailure] when [pictures] are not the envelope's (or it was changed: the
     * two cannot be told apart); a [MalformedShareException] when it opens but its content cannot be read.
     */
    fun open(envelope: ShareEnvelope, pictures: List<Int>): AnswerPayload {
        val plain = try {
            aead(Cipher.DECRYPT_MODE, pictures, envelope.salt, envelope.header, envelope.ciphertext)
        } catch (_: GeneralSecurityException) {
            throw SealFailure("not these pictures, or changed on the way") // English detail
        }
        try {
            val payload = AnswerPayload.decode(plain)
            if (!ShareRequest.isLine(payload.from, ShareRequest.MAX_NAME_LENGTH)) {
                payload.wipe()
                throw MalformedShareException("a sender's name")
            }
            return payload
        } finally {
            plain.fill(0)
        }
    }

    private fun aead(mode: Int, pictures: List<Int>, salt: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        require(pictures.size == Pictures.ENVELOPE_COUNT && pictures.all { it in Pictures.ALL.indices })
        val password = ByteArray(pictures.size) { pictures[it].toByte() }
        val stretched = Argon2.hash(password, salt, Pictures.STRETCH)
        val prk = Hkdf.extract(ByteArray(0), stretched)
        val okm = Hkdf.expand(prk, INFO, KEY_SIZE + NONCE_SIZE)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding") // not user text
            cipher.init(mode, SecretKeySpec(okm, 0, KEY_SIZE, "AES"), GCMParameterSpec(128, okm, KEY_SIZE, NONCE_SIZE))
            cipher.updateAAD(aad)
            return cipher.doFinal(input)
        } finally {
            password.fill(0)
            stretched.fill(0)
            prk.fill(0)
            okm.fill(0)
        }
    }

    private const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12
}
