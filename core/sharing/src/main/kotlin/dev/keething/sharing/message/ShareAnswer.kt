package dev.keething.sharing.message

/**
 * An answer to a [ShareRequest] (SPEC §10): entries sealed with HPKE for the request's public key. Only the id of
 * the request it answers is in the clear, so the requesting phone finds its key.
 *
 * Its bytes: version 1, kind 2, the request's id (16 bytes), HPKE's `enc` (32 bytes), then the ciphertext with
 * its tag to the end. The first three are the AEAD's associated data, so none of them can be changed either.
 */
public class ShareAnswer private constructor(
    internal val idBytes: ByteArray,
    internal val enc: ByteArray,
    internal val ciphertext: ByteArray,
) {
    /** The [ShareRequest.id] of the request it answers. */
    public val requestId: String = idBytes.joinToString("") { "%02x".format(it) }

    internal val header: ByteArray get() = header(idBytes)

    public fun encoded(): ByteArray = header + enc + ciphertext

    override fun toString(): String = "ShareAnswer(for $requestId)" // not user text

    public companion object {
        /** The AEAD's tag; a ciphertext is at least that long. */
        private const val TAG_SIZE = 16

        internal fun of(idBytes: ByteArray, enc: ByteArray, ciphertext: ByteArray): ShareAnswer =
            ShareAnswer(idBytes.copyOf(), enc.copyOf(), ciphertext.copyOf())

        internal fun header(idBytes: ByteArray): ByteArray =
            byteArrayOf(Format.VERSION.toByte(), Format.KIND_ANSWER.toByte()) + idBytes

        public fun decode(bytes: ByteArray): ShareAnswer {
            val reader = WireReader(bytes)
            Format.header(reader, Format.KIND_ANSWER)
            val id = reader.bytes(ShareRequest.ID_SIZE)
            val enc = reader.bytes(32)
            if (reader.remaining < TAG_SIZE) throw MalformedShareException("cut short") // English detail
            return ShareAnswer(id, enc, reader.bytes(reader.remaining))
        }
    }
}
