package dev.keething.kdbx.reader

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.crypto.KeyStream

/**
 * The header at the start of the decrypted payload: the key of the stream that hides protected values, and
 * the attachments (Mac `InnerHeader`).
 */
internal class InnerHeader(
    private val streamId: Int,
    private val streamKey: ByteArray,
    /** Wipeable: an attachment may be a private key. */
    val binaries: List<Secret>,
    /** Each attachment's flag byte (0x01: protected in memory), written back as it was. */
    val binaryFlags: List<Byte>,
    /** Where the XML starts. */
    val end: Int,
) {
    /** The stream its id names (ChaCha20, KDBX 4's only stream in practice), keyed with its key. */
    fun stream(): KeyStream = InnerStream.of(streamId, streamKey)

    fun wipeKey() {
        streamKey.fill(0)
    }

    companion object {
        fun parse(payload: ByteArray, length: Int): InnerHeader {
            val r = ByteReader(payload, 0, length)
            var streamId: Int? = null
            var key: ByteArray? = null
            val binaries = mutableListOf<Secret>()
            val flags = mutableListOf<Byte>()
            try {
                while (true) {
                    val id = r.uint8()
                    val size = r.size()
                    val start = r.position
                    if (id == null || size == null || !r.skip(size)) throw KdbxException.Corrupt("inner header")
                    when (id) {
                        0 -> {
                            if (streamId == null || key == null) throw KdbxException.Corrupt("inner header")
                            return InnerHeader(streamId, key, binaries, flags, r.position)
                        }
                        1 -> {
                            if (size != 4) throw KdbxException.Corrupt("inner header")
                            streamId = ByteReader(payload, start).int32()
                        }
                        2 -> key = payload.copyOfRange(start, start + size)
                        // The first byte holds flags (protected in memory); the rest is the attachment.
                        3 -> {
                            if (size == 0) throw KdbxException.Corrupt("inner header binary")
                            flags += payload[start]
                            binaries += Secret.copyOf(payload, start + 1, start + size)
                        }
                    }
                }
            } catch (failure: KdbxException) {
                binaries.forEach(Secret::wipe)
                key?.fill(0)
                throw failure
            }
        }
    }
}
