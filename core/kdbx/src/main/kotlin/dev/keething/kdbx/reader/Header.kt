package dev.keething.kdbx.reader

import dev.keething.kdbx.crypto.Argon2
import dev.keething.kdbx.crypto.Bytes
import dev.keething.kdbx.key.KdfLimits
import dev.keething.kdbx.key.KdfParameters
import dev.keething.kdbx.reader.KdbxFormat.Cipher
import dev.keething.kdbx.reader.KdbxFormat.Kdf
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** The outer header of a KDBX 4 file: format, cipher, key derivation, seeds (Mac `Header`). KDBX 3.1's is [Header3]. */
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
        private const val KEEPASS1_SIGNATURE_2 = 0xB54BFB65.toInt()
        private const val PRE_RELEASE_SIGNATURE_2 = 0xB54BFB66.toInt()

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

        /**
         * The version in the file's first 12 bytes, major and minor; nothing else is read. KeePass 1.x's file and KDBX 2's
         * pre-release one are refused here as the old formats they are.
         */
        fun version(file: ByteArray): Pair<Int, Int> {
            val r = ByteReader(file)
            val signature1 = r.int32()
            val signature2 = r.int32()
            val minor = r.uint16()
            val major = r.uint16()
            if (signature1 != SIGNATURE_1 || minor == null || major == null) throw KdbxException.NotKdbx()
            when (signature2) {
                SIGNATURE_2 -> return major to minor
                KEEPASS1_SIGNATURE_2 -> throw KdbxException.KeePass1()
                // KeePass 2's pre-release files: an old version, named as their bytes give it (keething-spec kdbx.read AC7).
                PRE_RELEASE_SIGNATURE_2 -> throw KdbxException.UnsupportedVersion(major, minor)
                else -> throw KdbxException.NotKdbx()
            }
        }

        /**
         * The header's bytes read from [input] and no further, for [parse]: the version, the fields to the end mark, the
         * checksum and the HMAC (KDBX 4). A file of another version is refused as [parse] refuses it; a short one is given
         * as far as it goes, for [parse] to say so.
         */
        fun read(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream(1024)
            val start = input.readNBytes(12)
            out.write(start)
            val (major, minor) = version(start)
            if (major != 4) throw KdbxException.UnsupportedVersion(major, minor)
            while (true) {
                val field = input.readNBytes(5)
                out.write(field)
                if (field.size < 5) return out.toByteArray()
                val size = Bytes.intLe(field, 1)
                // A size beyond an array is parse's to refuse; what is there is read only as it comes, never allocated ahead.
                if (size < 0) return out.toByteArray()
                val value = input.readNBytes(size)
                out.write(value)
                if (value.size < size) return out.toByteArray()
                if (field[0].toInt() == 0) break
            }
            out.write(input.readNBytes(64))
            return out.toByteArray()
        }

        /** A KDBX 4 header; a KDBX 3 file is [Header3]'s, any other version is refused. */
        fun parse(file: ByteArray): Header {
            val (major, minor) = version(file)
            if (major != 4) throw KdbxException.UnsupportedVersion(major, minor)
            val r = ByteReader(file, 12)

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

        /**
         * The KDF parameters field read: what transforms a key, and what the file says about it. Costs outside KeePass
         * 2.x's limits are refused here, before anything is derived, the parameter named ([KdfLimits]; keething-spec
         * kdbx.hardening AC6): the key derivation runs before the header's HMAC can be checked, so a file swapped in
         * by someone without the key could ask for anything.
         */
        fun kdf(data: ByteArray): Pair<KdfParameters, Kdf> {
            val dictionary = VariantDictionary.parse(data)
            val uuid = dictionary.bytes("\$UUID")?.let(::hex) ?: throw KdbxException.Corrupt("KDF id")
            return when (uuid) {
                in AES_KDF -> {
                    val rounds = dictionary.uint64Bits("R")
                    val seed = dictionary.bytes("S")
                    if (rounds == null || seed == null) throw KdbxException.Corrupt("AES-KDF parameters")
                    val summary = Kdf.AesKdf(rounds)
                    KdfLimits.problem(summary)?.let { throw KdbxException.UnsupportedKdf(it) }
                    KdfParameters.Aes(data, rounds, seed) to summary
                }
                ARGON2D, ARGON2ID -> {
                    val salt = dictionary.bytes("S")
                    val parallelism = dictionary.uint32("P")
                    val memory = dictionary.uint64Bits("M")
                    val iterations = dictionary.uint64Bits("I")
                    val version = dictionary.uint32("V")
                    if (salt == null || parallelism == null || memory == null || iterations == null || version == null) {
                        throw KdbxException.Corrupt("Argon2 parameters")
                    }
                    if (dictionary.contains("K") || dictionary.contains("A")) {
                        throw KdbxException.UnsupportedKdf("Argon2 with secret")
                    }
                    if (version != 0x13L) throw KdbxException.UnsupportedKdf("Argon2 version 0x%X (KeePass apps write 0x13)".format(version))
                    KdfLimits.lanes(parallelism)?.let { throw KdbxException.UnsupportedKdf(it) }
                    val isId = uuid == ARGON2ID
                    val summary = Kdf.Argon2(
                        if (isId) Kdf.Argon2.Variant.ID else Kdf.Argon2.Variant.D,
                        iterations, memory, parallelism.toInt(),
                    )
                    KdfLimits.problem(summary)?.let { throw KdbxException.UnsupportedKdf(it) }
                    val parameters = Argon2.Parameters(
                        variant = if (isId) Argon2.Variant.ID else Argon2.Variant.D,
                        iterations = iterations,
                        memoryKiB = (memory / 1024).toInt(),
                        parallelism = parallelism.toInt(),
                        version = version.toInt(),
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
