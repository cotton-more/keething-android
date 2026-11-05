package dev.keething.kdbx.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Reference values from the Mac tests (`KDBXTests.swift`): Python's hashlib (BLAKE2b) and OpenSSL 3+
 * (`openssl kdf ARGON2D/ARGON2ID`, `openssl enc -chacha20`); Salsa20 from the eSTREAM test vectors.
 */
class CryptoTest {
    @Test
    fun blake2bMatchesHashlibAnyLengthAcrossBlockBoundaries() {
        val long = ByteArray(768) { it.toByte() }

        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            Blake2b.hash(ByteArray(0)).hex(),
        )
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            Blake2b.hash("abc".encodeToByteArray()).hex(),
        )
        assertEquals(
            "323e97a7a859ee63c9013debb0ca995811e73117a2f574723416e596ebc184e37a59b66d2f597df4a7c1b0d1d41a1a7f28774f46a6864d56c57b9d6c5f7302fb",
            Blake2b.hash(long).hex(),
        )
        assertEquals(
            "bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319",
            Blake2b.hash("abc".encodeToByteArray(), length = 32).hex(),
        )
        assertEquals("7d9fb494034c8408a0aae4f6ef64160924", Blake2b.hash(ByteArray(200) { it.toByte() }, length = 17).hex())
    }

    @Test
    fun blake2bFedInPiecesGivesTheSameDigest() {
        val long = ByteArray(768) { it.toByte() }
        val pieces = Blake2b()
        pieces.update(long, 0, 100)
        pieces.update(long, 100, 28)
        pieces.update(long, 128, 640)

        assertContentEquals(Blake2b.hash(long), pieces.finish())
    }

    @Test
    fun argon2dAndArgon2idMatchOpenSsl() {
        val salt = "somesaltsomesalt".encodeToByteArray()
        val cases = listOf(
            Case(Argon2.Variant.D, 1, 8, 1, "8f6b35da93486025c6a1c6361da620487040700d56c30b87eb870865781d3274"),
            Case(Argon2.Variant.D, 2, 32, 1, "9b60e17f88d110784a65c348586c14f55af9d33897ca3210c24545b669a6eb42"),
            Case(Argon2.Variant.D, 1, 32, 2, "f32d2c124360ffef573f916b33885fb0f17b4721e03fbd959ef5e14e86a96e4f"),
            Case(Argon2.Variant.D, 1, 64, 4, "503b34e3218d3d936abe106b946720232dd3783877b17ca793c4c71a647562bb"),
            Case(Argon2.Variant.D, 1, 32, 1, "ffb666505060953d67c04a0351261634eb042e7cf13d4009957d616333405c1a"),
            Case(Argon2.Variant.D, 3, 1024, 2, "70bbe1f806bdedfeb0986154bbd155569c2bdef982d2c08d5263cc0e924b0188"),
            Case(Argon2.Variant.D, 2, 64, 4, "21477a8712db011b1bda81a1d18d4ccf6f1100729d89d9215c28d1fcd3862dd4"),
            Case(Argon2.Variant.ID, 1, 8, 1, "05b6ca8f8038b683887ad4916533858b49cb52040126c134f4c6e9d8711b8c09"),
            Case(Argon2.Variant.ID, 1, 32, 1, "fa1e90316994a42525e8e3db47cc59ed150c98d47db299b2bcb689d8217af811"),
            Case(Argon2.Variant.ID, 3, 1024, 2, "a331c9e908e5cc6a7fbf63e915ca3ae2d797e03965116715a95b4efec189a160"),
            Case(Argon2.Variant.ID, 2, 64, 4, "4111a92266f8015c4224e634fdc7352dad7e17abe362d2386331333226f8189e"),
        )
        for (case in cases) {
            val parameters = Argon2.Parameters(case.variant, case.iterations.toLong(), case.memoryKiB, case.parallelism)

            val tag = Argon2.hash("password".encodeToByteArray(), salt, parameters)

            assertEquals(case.expected, tag.hex(), case.toString())
        }
    }

    @Test
    fun argon2RefusesParametersOutsideTheSpecification() {
        val salt = ByteArray(16)
        assertFailsWith<IllegalArgumentException> {
            Argon2.hash(ByteArray(1), salt, Argon2.Parameters(Argon2.Variant.D, 1, 8, 2))
        }
        assertFailsWith<IllegalArgumentException> {
            Argon2.hash(ByteArray(1), salt, Argon2.Parameters(Argon2.Variant.D, 1, 8, 1, version = 0x10))
        }
    }

    @Test
    fun chaCha20KeystreamMatchesOpenSslAlsoWhenAppliedInPieces() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x4a, 0, 0, 0, 0)
        val whole = ByteArray(80)
        ChaCha20(key, nonce).apply(whole)

        assertEquals(
            "af051e40bba0354981329a806a140eafd258a22a6dcb4bb9f6569cb3efe2deaf837bd87ca20b5ba12081a306af0eb35c41a239d20dfc74c81771560d9c9c1e4b224f51f3401bd9e12fde276fb8631ded",
            whole.hex(),
        )
        val pieces = ByteArray(80)
        val stream = ChaCha20(key, nonce)
        stream.apply(pieces, 0, 7)
        stream.apply(pieces, 7, 73)
        assertContentEquals(whole, pieces)
    }

    @Test
    fun salsa20KeystreamMatchesEstreamSet1Vector0AlsoWhenAppliedInPieces() {
        val key = ByteArray(32).also { it[0] = 0x80.toByte() }
        val whole = ByteArray(64)
        Salsa20(key, ByteArray(8)).apply(whole)

        assertEquals(
            "e3be8fdd8beca2e3ea8ef9475b29a6e7003951e1097a5c38d23b7a5fad9f6844b22c97559e2723c7cbbd3fe4fc8d9a0744652a83e72a9c461876af4d7ef1a117",
            whole.hex(),
        )
        val pieces = ByteArray(64)
        val stream = Salsa20(key, ByteArray(8))
        stream.apply(pieces, 0, 13)
        stream.apply(pieces, 13, 51)
        assertContentEquals(whole, pieces)
    }

    private data class Case(
        val variant: Argon2.Variant,
        val iterations: Int,
        val memoryKiB: Int,
        val parallelism: Int,
        val expected: String,
    )
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
