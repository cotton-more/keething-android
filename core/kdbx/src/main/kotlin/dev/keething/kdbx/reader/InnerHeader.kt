package dev.keething.kdbx.reader

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.crypto.ChaCha20
import java.security.MessageDigest

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
    /** ChaCha20 keyed with the SHA-512 of the stream key (KDBX 4's only stream in practice). */
    fun stream(): ChaCha20 {
        if (streamId != 3) throw KdbxException.UnsupportedInnerStream(streamId)
        val hash = MessageDigest.getInstance("SHA-512").digest(streamKey)
        try {
            return ChaCha20(hash.copyOfRange(0, 32), hash.copyOfRange(32, 44))
        } finally {
            hash.fill(0)
        }
    }

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
