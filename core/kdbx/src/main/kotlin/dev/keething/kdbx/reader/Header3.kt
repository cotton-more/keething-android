package dev.keething.kdbx.reader

import dev.keething.kdbx.crypto.Bytes
import dev.keething.kdbx.key.KdfParameters
import java.security.MessageDigest

/**
 * The outer header of a KDBX 3.1 file (keething-spec kdbx.kdbx3 AC1; KeePass's `KdbxFile.Read` for version 3): its
 * fields have 16-bit sizes, its key derivation is AES-KDF from the transform seed and rounds, and the stream start
 * bytes, the protected stream's key and id are in it. No checksum or HMAC follows it: only `Meta/HeaderHash`
 * inside the encrypted XML covers it ([sha256]).
 */
internal class Header3(
    val format: KdbxFormat,
    /** The cipher's id as the header has it: a KDBX 4 header names the cipher the same way. */
    val cipherId: ByteArray,
    /** AES-KDF as KDBX 4 would store it: the same parameters field, so the key derived is the same. */
    val kdf: KdfParameters,
    val masterSeed: ByteArray,
    val iv: ByteArray,
    /** The first 32 bytes of the decrypted payload; any other bytes there mean a wrong key. */
    val streamStartBytes: ByteArray,
    val streamId: Int,
    /** Wiped once the stream is made. */
    val streamKey: ByteArray,
    /** SHA-256 of the header bytes, from the start to [end], for `Meta/HeaderHash`. */
    val sha256: ByteArray,
    /** Where the encrypted payload starts. */
    val end: Int,
) {
    companion object {
        fun parse(file: ByteArray): Header3 {
            val (major, minor) = Header.version(file)
            if (major != 3) throw KdbxException.UnsupportedVersion(major, minor)
            val r = ByteReader(file, 12)

            var cipherId: ByteArray? = null
            var compression = 0
            var seed: ByteArray? = null
            var transformSeed: ByteArray? = null
            var rounds: Long? = null
            var iv: ByteArray? = null
            var streamKey: ByteArray? = null
            var startBytes: ByteArray? = null
            var streamId: Int? = null
            while (true) {
                val id = r.uint8()
                val value = r.uint16()?.let(r::bytes)
                if (id == null || value == null) throw KdbxException.Corrupt("header truncated")
                when (id) {
                    0 -> break
                    2 -> cipherId = value
                    3 -> compression = if (value.size == 4) Bytes.intLe(value, 0) else -1
                    4 -> seed = value
                    5 -> transformSeed = value
                    6 -> rounds = if (value.size == 8) Bytes.longLe(value, 0) else throw KdbxException.Corrupt("transform rounds")
                    7 -> iv = value
                    8 -> streamKey = value
                    9 -> startBytes = value
                    10 -> streamId = if (value.size == 4) Bytes.intLe(value, 0) else throw KdbxException.Corrupt("inner stream id")
                    // A comment (1) and unknown fields are covered by the header hash, not used.
                }
            }
            val end = r.position
            val cipher = cipherId?.let { Header.CIPHERS[Header.hex(it)] }
                ?: throw KdbxException.UnsupportedCipher(cipherId?.let(Header::hex) ?: "missing")
            if (compression !in 0..1) throw KdbxException.Corrupt("compression flag")
            if (seed == null || seed.size != 32 || transformSeed == null || transformSeed.size != 32 || rounds == null ||
                iv == null || streamKey == null || startBytes == null || startBytes.size != 32 || streamId == null
            ) {
                throw KdbxException.Corrupt("header field missing")
            }

            val parameters = VariantDictionary.Writer()
                .bytes("\$UUID", Header.id(Header.KEEPASS_AES_KDF))
                .uint64("R", rounds)
                .bytes("S", transformSeed)
                .finish()
            val (kdf, summary) = Header.kdf(parameters)
            return Header3(
                format = KdbxFormat("$major.$minor", cipher, summary, compressed = compression == 1),
                cipherId = cipherId,
                kdf = kdf,
                masterSeed = seed,
                iv = iv,
                streamStartBytes = startBytes,
                streamId = streamId,
                streamKey = streamKey,
                sha256 = MessageDigest.getInstance("SHA-256").apply { update(file, 0, end) }.digest(),
                end = end,
            )
        }
    }
}
