package dev.keething.sharing.message

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Entries sent without a request (SPEC §10): sealed with a key made from ten pictures, which the sender gives another
 * way than the code. In the clear: when it was made, until when it is valid, and the salt the key is made with.
 *
 * Its bytes: version 1, kind 4, created and expires (seconds since 1970, 64-bit big-endian), the salt (16 bytes),
 * then the ciphertext with its tag to the end. All before the ciphertext is the AEAD's associated data.
 */
public class ShareEnvelope private constructor(
    public val created: Instant,
    public val expires: Instant,
    internal val salt: ByteArray,
    internal val ciphertext: ByteArray,
    private val bytes: ByteArray,
) {
    /** The first 16 bytes of the SHA-256 of its bytes, in hex: its name in the book and the screens. */
    public val id: String = MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(16).joinToString("") { "%02x".format(it) }

    internal val header: ByteArray get() = bytes.copyOf(HEADER_SIZE)

    public fun isExpired(at: Instant): Boolean = !at.isBefore(expires)

    public fun encoded(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is ShareEnvelope && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ShareEnvelope($id)"

    public companion object {
        internal const val SALT_SIZE = 16
        private const val HEADER_SIZE = 2 + 8 + 8 + SALT_SIZE

        /** The AEAD's tag; a ciphertext is at least that long. */
        private const val TAG_SIZE = 16

        /** The associated data of an envelope made at [created] (whole seconds) for [validFor]. */
        internal fun header(created: Instant, validFor: Duration, salt: ByteArray): ByteArray {
            require(salt.size == SALT_SIZE)
            val from = created.epochSecond
            val writer = WireWriter(HEADER_SIZE)
            writer.byte(Format.VERSION)
            writer.byte(Format.KIND_ENVELOPE)
            writer.long(from)
            writer.long(from + validFor.seconds)
            writer.bytes(salt)
            return writer.toByteArray()
        }

        public fun decode(bytes: ByteArray): ShareEnvelope {
            val reader = WireReader(bytes)
            Format.header(reader, Format.KIND_ENVELOPE)
            val created = reader.instant()
            val expires = reader.instant()
            val salt = reader.bytes(SALT_SIZE)
            if (reader.remaining < TAG_SIZE) throw MalformedShareException("cut short")
            if (!expires.isAfter(created) || Duration.between(created, expires) > ShareRequest.MAX_VALIDITY) {
                throw MalformedShareException("a validity out of range")
            }
            return ShareEnvelope(created, expires, salt, reader.bytes(reader.remaining), bytes.copyOf())
        }
    }
}
