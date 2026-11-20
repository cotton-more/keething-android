package dev.keething.sharing.requests

import dev.keething.domain.secret.Secret
import dev.keething.sharing.crypto.Hpke
import dev.keething.sharing.crypto.SealFailure
import dev.keething.sharing.crypto.X25519
import dev.keething.sharing.message.MalformedShareException
import dev.keething.sharing.message.ShareAnswer
import dev.keething.sharing.message.ShareRequest
import dev.keething.sharing.payload.AnswerPayload
import java.security.SecureRandom

/**
 * Sealing an answer for a request and opening it (SPEC §10). HPKE's `info` is a label and the request's whole
 * digest: an answer opens only for the very request it was made for, name, reason and validity included.
 */
internal object Answers {
    private val INFO = "Keething answer v1".encodeToByteArray() // not user text

    fun seal(request: ShareRequest, payload: AnswerPayload, random: SecureRandom): ShareAnswer {
        val ephemeral = X25519.newPrivateKey(random)
        try {
            return seal(request, payload, ephemeral)
        } finally {
            ephemeral.fill(0)
        }
    }

    /** With a given ephemeral key: the vector another implementation checks against (`InteropVectorTest`). */
    fun seal(request: ShareRequest, payload: AnswerPayload, ephemeral: ByteArray): ShareAnswer = sealEncoded(request, payload.encode(), ephemeral)

    /** Seals [plain], the encoded content, and wipes it whatever happens. */
    internal fun sealEncoded(request: ShareRequest, plain: Secret, ephemeral: ByteArray): ShareAnswer {
        try {
            val id = request.idBytes
            val sealed = plain.useBytes { Hpke.SHARING.seal(request.publicKey, INFO + request.digest, ShareAnswer.header(id), it, ephemeral) }
            return ShareAnswer.of(id, sealed.enc, sealed.ciphertext)
        } finally {
            plain.wipe()
        }
    }

    /** The payload, the caller's to wipe; a [MalformedShareException] when it was changed or is not for [mine]. */
    fun open(answer: ShareAnswer, mine: MyRequest): AnswerPayload {
        val plain = try {
            mine.privateKey.useBytes { Hpke.SHARING.open(answer.enc, it, INFO + mine.request.digest, answer.header, answer.ciphertext) }
        } catch (_: SealFailure) {
            throw MalformedShareException("changed on the way, or not for this request") // English detail
        } catch (failure: RuntimeException) {
            throw MalformedShareException(failure.javaClass.simpleName)
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
}
