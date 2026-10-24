package dev.keepassbar.sharing.requests

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.sharing.message.MalformedShareException
import dev.keepassbar.sharing.message.ShareEnvelope
import dev.keepassbar.sharing.message.ShareRequest
import dev.keepassbar.sharing.message.WireReader
import dev.keepassbar.sharing.message.WireWriter
import dev.keepassbar.sharing.pictures.Pictures
import java.security.SecureRandom
import java.time.Instant
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A request this phone made, waiting for its answer: its private key opens it. */
public class MyRequest internal constructor(
    public val request: ShareRequest,
    internal val privateKey: Secret,
    /** Indices into [Pictures.ALL]. */
    public val pictures: List<Int>,
)

/** A request someone sent this phone, to answer once its pictures were checked. */
public class TheirRequest internal constructor(
    public val request: ShareRequest,
    public val received: Instant,
    /** The user said the pictures match those the requester sees (SPEC §10). */
    public val checked: Boolean,
    public val pictures: List<Int>,
)

/**
 * An envelope this phone sealed (SPEC §10), kept so that its code can be sent again and its pictures read out later,
 * until it expires or they are forgotten. Its pictures are its key: they stay in the sealed book, and in memory while
 * unlocked, as the entries themselves do.
 */
public class SentEnvelope internal constructor(
    public val envelope: ShareEnvelope,
    /** Indices into [Pictures.ALL], [Pictures.ENVELOPE_COUNT] of them. */
    public val pictures: List<Int>,
    /** Whom it is for, as the sender wrote it, or empty; never sent. */
    public val recipient: String,
    /** The sender's name in it, for its pictures' caption. */
    public val from: String,
    /** The titles of the entries in it, for the list. */
    public val titles: List<String>,
    /** The app the share sheet sent its code through, and its pictures (a component's flat name), or empty. */
    public val codeVia: String = "",
    public val picturesVia: String = "",
)

/**
 * The requests of this phone, both ways, newest first; the ids of those answered here, until they expire, so the same
 * request read again cannot be answered twice. Then the envelopes sent from here, newest first, and the ids of those
 * saved here, until they expire, so one is not saved twice.
 */
public class RequestBook internal constructor(
    public val mine: List<MyRequest>,
    public val theirs: List<TheirRequest>,
    internal val answered: Map<String, Instant> = emptyMap(),
    public val sent: List<SentEnvelope> = emptyList(),
    internal val saved: Map<String, Instant> = emptyMap(),
) {
    internal val isEmpty: Boolean get() = mine.isEmpty() && theirs.isEmpty() && answered.isEmpty() && sent.isEmpty() && saved.isEmpty()

    internal fun wipe() {
        mine.forEach { it.privateKey.wipe() }
    }

    internal companion object {
        val EMPTY = RequestBook(emptyList(), emptyList(), emptyMap())

        /** Version 1 had no envelopes; it is still read. */
        private const val VERSION = 2
        private val AAD = "Keething requests".encodeToByteArray()

        /**
         * The file: version 1, a random 12-byte nonce, then the book sealed with AES-256-GCM under [key]. The book:
         * the number of my requests, each its encoded request, private key and pictures (a byte each); then theirs,
         * each its encoded request, when it arrived (seconds), checked (a byte) and pictures; then the answered ones,
         * each its id and when it expires. Since version 2, then the envelopes sent, each its encoded envelope, its
         * pictures (a byte each), whom it is for, from whom, the titles, and the apps its code and pictures went through; then the saved ones, each its id and when it expires.
         */
        fun seal(book: RequestBook, key: Secret, random: SecureRandom): ByteArray {
            val w = WireWriter(2048)
            try {
                w.count(book.mine.size)
                for (mine in book.mine) {
                    w.field(mine.request.encoded())
                    w.secret(mine.privateKey)
                    mine.pictures.forEach(w::byte)
                }
                w.count(book.theirs.size)
                for (their in book.theirs) {
                    w.field(their.request.encoded())
                    w.long(their.received.epochSecond)
                    w.byte(if (their.checked) 1 else 0)
                    their.pictures.forEach(w::byte)
                }
                w.count(book.answered.size)
                book.answered.forEach { (id, expires) ->
                    w.text(id)
                    w.long(expires.epochSecond)
                }
                w.count(book.sent.size)
                for (sent in book.sent) {
                    w.field(sent.envelope.encoded())
                    sent.pictures.forEach(w::byte)
                    w.text(sent.recipient)
                    w.text(sent.from)
                    w.count(sent.titles.size)
                    sent.titles.forEach(w::text)
                    w.text(sent.codeVia)
                    w.text(sent.picturesVia)
                }
                w.count(book.saved.size)
                book.saved.forEach { (id, expires) ->
                    w.text(id)
                    w.long(expires.epochSecond)
                }
                val plain = w.toByteArray()
                try {
                    val nonce = ByteArray(12).also(random::nextBytes)
                    return byteArrayOf(VERSION.toByte()) + nonce + cipher(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plain)
                } finally {
                    plain.fill(0)
                }
            } finally {
                w.wipe()
            }
        }

        /** Null when [key] does not open [sealed]: another database's, or its key derivation changed. */
        fun open(sealed: ByteArray, key: Secret): RequestBook? {
            val version = sealed.firstOrNull()?.toInt()
            if (sealed.size < 1 + 12 + 16 || (version != 1 && version != VERSION)) return null
            val plain = try {
                cipher(Cipher.DECRYPT_MODE, key, sealed.copyOfRange(1, 13)).doFinal(sealed, 13, sealed.size - 13)
            } catch (_: GeneralSecurityException) {
                return null
            }
            val mine = mutableListOf<MyRequest>()
            try {
                val r = WireReader(plain)
                repeat(r.count(10_000)) {
                    val request = ShareRequest.decode(r.field(4096))
                    val privateKey = r.secret(32)
                    mine += MyRequest(request, privateKey, pictures(r))
                }
                val theirs = List(r.count(10_000)) {
                    TheirRequest(ShareRequest.decode(r.field(4096)), r.instant(), r.byte() == 1, pictures(r))
                }
                val answered = LinkedHashMap<String, Instant>()
                repeat(r.count(10_000)) { answered[r.text(64)] = r.instant() }
                val sent = mutableListOf<SentEnvelope>()
                val saved = LinkedHashMap<String, Instant>()
                if (version == VERSION) {
                    repeat(r.count(10_000)) {
                        val envelope = ShareEnvelope.decode(r.field(1 shl 20))
                        val pictures = List(Pictures.ENVELOPE_COUNT) { r.byte().also { if (it >= Pictures.ALL.size) throw MalformedShareException("a picture") } }
                        val recipient = r.text(1024)
                        val from = r.text(1024)
                        val titles = List(r.count(10_000)) { r.text(1 shl 16) }
                        sent += SentEnvelope(envelope, pictures, recipient, from, titles, r.text(1024), r.text(1024))
                    }
                    repeat(r.count(10_000)) { saved[r.text(64)] = r.instant() }
                }
                r.end()
                return RequestBook(mine, theirs, answered, sent, saved)
            } catch (_: Exception) {
                mine.forEach { it.privateKey.wipe() }
                return null
            } finally {
                plain.fill(0)
            }
        }

        private fun pictures(r: WireReader): List<Int> = List(Pictures.COUNT) {
            r.byte().also { if (it >= Pictures.ALL.size) throw MalformedShareException("a picture") }
        }

        private fun cipher(mode: Int, key: Secret, nonce: ByteArray): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                key.useBytes { init(mode, SecretKeySpec(it, "AES"), GCMParameterSpec(128, nonce)) }
                updateAAD(AAD)
            }
    }
}
