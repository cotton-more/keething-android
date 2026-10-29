package dev.keething.kdbx.reader

import dev.keething.kdbx.crypto.Argon2
import dev.keething.kdbx.key.KdfParameters
import dev.keething.kdbx.reader.KdbxFormat.Cipher
import dev.keething.kdbx.reader.KdbxFormat.Kdf

/** The outer header of a KDBX 4 file: format, cipher, key derivation, seeds (Mac `Header`). */
internal class Header(
    val format: KdbxFormat,
    val kdf: KdfParameters,
    /** What a writer needs to write the file again in the same format (seeds aside). */
    val template: KdbxTemplate,
    val masterSeed: ByteArray,
    val iv: ByteArray,
    /** The header bytes the checksum and the HMAC cover: from the start to [checksumStart]. */
    val checksumStart: Int,
    val sha256: ByteArray,
    val hmac: ByteArray,
    /** Where the HMAC block stream starts. */
    val end: Int,
) {
    companion object {
        private const val SIGNATURE_1 = 0x9AA2D903.toInt()
        private const val SIGNATURE_2 = 0xB54BFB67.toInt()

        val CIPHERS = mapOf(
            "31c1f2e6bf714350be5805216afc5aff" to Cipher.AES256,
            "d6038a2b8b6f4cb5a524339a31dbb59a" to Cipher.CHACHA20,
            "ad68f29f576f4bb9a36ad47af965346c" to Cipher.TWOFISH,
        )

        /** KeePass's own AES-KDF id: the one a new file gets (KeePass 2.x knows no other). */
        const val KEEPASS_AES_KDF = "c9d9f39a628a4460bf740d08c18a4fea"

        // KeePass's AES-KDF and KeePassXC's KDBX 4 variant of it: the same transformation.
        private val AES_KDF = setOf(KEEPASS_AES_KDF, "7c02bb8279a74ac0927d114a00648238")
        const val ARGON2D = "ef636ddf8c29444b91f7a9a403e30a0c"
        const val ARGON2ID = "9e298b1956db4773b23dfc3ec6f0a1e6"

        fun parse(file: ByteArray): Header {
            val r = ByteReader(file)
            val signature1 = r.int32()
            val signature2 = r.int32()
            val minor = r.uint16()
            val major = r.uint16()
            if (signature1 != SIGNATURE_1 || signature2 != SIGNATURE_2 || minor == null || major == null) {
                throw KdbxException.NotKdbx()
            }
            if (major != 4) throw KdbxException.UnsupportedVersion(major, minor)

            var cipherId: ByteArray? = null
            var compression = 0
            var seed: ByteArray? = null
            var iv: ByteArray? = null
            var kdfData: ByteArray? = null
            var publicCustomData: ByteArray? = null
            while (true) {
                val id = r.uint8()
                val value = r.size()?.let(r::bytes)
                if (id == null || value == null) throw KdbxException.Corrupt("header truncated")
                when (id) {
                    0 -> break
                    2 -> cipherId = value
                    3 -> compression = if (value.size == 4) ByteReader(value).int32()!! else -1
                    4 -> seed = value
                    7 -> iv = value
                    11 -> kdfData = value
                    12 -> publicCustomData = value
                    // Unknown fields are covered by the HMAC, not used.
                }
            }
            val checksumStart = r.position
            val sha = r.bytes(32)
            val mac = r.bytes(32)
            if (sha == null || mac == null) throw KdbxException.Corrupt("header truncated")
            val cipher = cipherId?.let { CIPHERS[hex(it)] }
                ?: throw KdbxException.UnsupportedCipher(cipherId?.let(::hex) ?: "missing")
            if (compression !in 0..1) throw KdbxException.Corrupt("compression flag")
            if (seed == null || seed.size != 32 || iv == null || kdfData == null) {
                throw KdbxException.Corrupt("header field missing")
            }

            val (kdf, summary) = kdf(kdfData)
            return Header(
                format = KdbxFormat("$major.$minor", cipher, summary, compressed = compression == 1),
                kdf = kdf,
                template = KdbxTemplate(major, minor, cipherId, cipher, compression == 1, kdf, publicCustomData),
                masterSeed = seed,
                iv = iv,
                checksumStart = checksumStart,
                sha256 = sha,
                hmac = mac,
                end = checksumStart + 64,
            )
        }

        /** The KDF parameters field read: what transforms a key, and what the file says about it. */
        fun kdf(data: ByteArray): Pair<KdfParameters, Kdf> {
            val dictionary = VariantDictionary.parse(data)
            val uuid = dictionary.bytes("\$UUID")?.let(::hex) ?: throw KdbxException.Corrupt("KDF id")
            return when (uuid) {
                in AES_KDF -> {
                    val rounds = dictionary.uint64("R")
                    val seed = dictionary.bytes("S")
                    if (rounds == null || seed == null) throw KdbxException.Corrupt("AES-KDF parameters")
                    KdfParameters.Aes(data, rounds, seed) to Kdf.AesKdf(rounds)
                }
                ARGON2D, ARGON2ID -> {
                    val salt = dictionary.bytes("S")
                    val parallelism = dictionary.uint32("P")
                    val memory = dictionary.uint64("M")
                    val iterations = dictionary.uint64("I")
                    val version = dictionary.uint32("V")
                    if (salt == null || parallelism == null || memory == null || iterations == null || version == null ||
                        memory / 1024 > Int.MAX_VALUE || iterations > Int.MAX_VALUE || parallelism > Int.MAX_VALUE
                    ) {
                        throw KdbxException.Corrupt("Argon2 parameters")
                    }
                    if (dictionary.contains("K") || dictionary.contains("A")) {
                        throw KdbxException.UnsupportedKdf("Argon2 with secret")
                    }
                    val isId = uuid == ARGON2ID
                    val parameters = Argon2.Parameters(
                        variant = if (isId) Argon2.Variant.ID else Argon2.Variant.D,
                        iterations = iterations.toInt(),
                        memoryKiB = (memory / 1024).toInt(),
                        parallelism = parallelism.toInt(),
                        version = version.toInt(),
                    )
                    val summary = Kdf.Argon2(
                        if (isId) Kdf.Argon2.Variant.ID else Kdf.Argon2.Variant.D,
                        iterations, memory, parallelism.toInt(),
                    )
                    KdfParameters.Argon2(data, parameters, salt) to summary
                }
                else -> throw KdbxException.UnsupportedKdf(uuid)
            }
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** An id above as bytes. */
        fun id(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
