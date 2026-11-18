package dev.keething.sharing.message

import dev.keething.sharing.crypto.X25519
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * A request for entries (SPEC §10): who asks, what for, until when, and the public key the answer is encrypted
 * for. It travels in the clear, so anyone who carries it can read it; its pictures ([dev.keething.sharing.pictures.Pictures])
 * show whether it arrived unchanged.
 *
 * Its bytes: version 1, kind 1, created and expires (seconds since 1970, 64-bit big-endian), the X25519 public
 * key (32 bytes), then the name and the reason (UTF-8, each after its length).
 */
public class ShareRequest private constructor(
    public val name: String,
    public val reason: String,
    public val created: Instant,
    public val expires: Instant,
    internal val publicKey: ByteArray,
    private val bytes: ByteArray,
) {
    /** SHA-256 of the bytes: an answer is sealed for exactly this request, and the pictures come from it. */
    internal val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** The first 16 bytes of [digest]: what an answer names its request by. */
    internal val idBytes: ByteArray get() = digest.copyOf(ID_SIZE)

    /** [idBytes] in hex: the request's name in the book and the screens. */
    public val id: String = idBytes.joinToString("") { "%02x".format(it) }

    public fun isExpired(at: Instant): Boolean = !at.isBefore(expires)

    public fun encoded(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is ShareRequest && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ShareRequest($id)" // not user text

    public companion object {
        internal const val ID_SIZE = 16
        public const val MAX_NAME_LENGTH: Int = 64
        public const val MAX_REASON_LENGTH: Int = 200

        /** The longest a request may be valid: the longest choice in Settings (SPEC §10). */
        public val MAX_VALIDITY: Duration = Duration.ofDays(7)

        internal fun create(name: String, reason: String, created: Instant, validFor: Duration, publicKey: ByteArray): ShareRequest {
            val from = Instant.ofEpochSecond(created.epochSecond)
            val writer = WireWriter()
            writer.byte(Format.VERSION)
            writer.byte(Format.KIND_REQUEST)
            writer.long(from.epochSecond)
            writer.long(from.plus(validFor).epochSecond)
            writer.bytes(publicKey)
            writer.text(name.trim())
            writer.text(reason.trim())
            return decode(writer.toByteArray())
        }

        public fun decode(bytes: ByteArray): ShareRequest {
            val reader = WireReader(bytes)
            Format.header(reader, Format.KIND_REQUEST)
            val created = reader.instant()
            val expires = reader.instant()
            val publicKey = reader.bytes(32)
            val name = reader.text(MAX_NAME_LENGTH * 4)
            val reason = reader.text(MAX_REASON_LENGTH * 4)
            reader.end()
            if (!expires.isAfter(created) || Duration.between(created, expires) > MAX_VALIDITY) {
                throw MalformedShareException("a validity out of range") // English detail
            }
            if (!isLine(name, MAX_NAME_LENGTH) || !isLine(reason, MAX_REASON_LENGTH)) {
                throw MalformedShareException("a name or reason out of range") // English detail
            }
            if (!X25519.isUsable(publicKey)) throw MalformedShareException("a public key of small order") // English detail
            return ShareRequest(name, reason, created, expires, publicKey, bytes.copyOf())
        }

        /**
         * One line of text, trimmed, not empty, at most [max] characters (code points), without control or format
         * characters: a right-to-left override or a zero-width space could make a name look like another.
         */
        public fun isLine(text: String, max: Int): Boolean =
            text.isNotEmpty() && text == text.trim() && text.codePointCount(0, text.length) <= max &&
                text.none { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' || Character.getType(it) == Character.FORMAT.toInt() }
    }
}

/** The first two bytes every sharing message starts with. */
internal object Format {
    const val VERSION = 1
    const val KIND_REQUEST = 1
    const val KIND_ANSWER = 2
    const val KIND_PART = 3
    const val KIND_ENVELOPE = 4

    fun header(reader: WireReader, kind: Int) {
        if (reader.byte() != VERSION) throw MalformedShareException("a version this app does not know") // English detail
        if (reader.byte() != kind) throw MalformedShareException("another kind of code") // English detail
    }
}
