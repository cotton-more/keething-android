package dev.keething.sharing.crypto

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * The CFRG's HPKE test vectors (draft-irtf-cfrg-hpke `test-vectors.json`, the source of RFC 9180 Appendix A),
 * base mode, DHKEM(X25519, HKDF-SHA256) with HKDF-SHA256: AES-128-GCM is RFC 9180 A.1, AES-256-GCM the
 * sharing's own suite. The first encryption of each (sequence number 0), the only one a single-shot message has.
 */
class HpkeTest {
    private class Vector(
        val hpke: Hpke, val ikmE: String, val pkEm: String, val ikmR: String, val pkRm: String, val skRm: String,
        val pt: String, val aad: String, val ct: String,
    )

    private val info = hex("4f6465206f6e2061204772656369616e2055726e")

    private val aes128 = Vector(
        Hpke.AES_128_GCM,
        ikmE = "7268600d403fce431561aef583ee1613527cff655c1343f29812e66706df3234",
        pkEm = "37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431",
        ikmR = "6db9df30aa07dd42ee5e8181afdb977e538f5e1fec8a06223f33f7013e525037",
        pkRm = "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d",
        skRm = "4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8",
        pt = "4265617574792069732074727574682c20747275746820626561757479",
        aad = "436f756e742d30",
        ct = "f938558b5d72f1a23810b4be2ab4f84331acc02fc97babc53a52ae8218a355a96d8770ac83d07bea87e13c512a",
    )

    private val aes256 = Vector(
        Hpke.SHARING,
        ikmE = "2cd7c601cefb3d42a62b04b7a9041494c06c7843818e0ce28a8f704ae7ab20f9",
        pkEm = "6c93e09869df3402d7bf231bf540fadd35cd56be14f97178f0954db94b7fc256",
        ikmR = "dac33b0e9db1b59dbbea58d59a14e7b5896e9bdf98fad6891e99d1686492b9ee",
        pkRm = "430f4b9859665145a6b1ba274024487bd66f03a2dd577d7753c68d7d7d00c00c",
        skRm = "497b4502664cfea5d5af0b39934dac72242a74f8480451e1aee7d6a53320333d",
        pt = "4265617574792069732074727574682c20747275746820626561757479",
        aad = "436f756e742d30",
        ct = "e5d84cd531cfb583096e7cfa9641bd3079cf3a91cda813c52deb5f512be9931980a41de125a925cdad859d5b7a",
    )

    @Test
    fun derivesTheVectorsKeyPairs() {
        for (v in listOf(aes128, aes256)) {
            val skR = v.hpke.derivePrivateKey(hex(v.ikmR))
            assertContentEquals(hex(v.skRm), skR)
            assertContentEquals(hex(v.pkRm), X25519.publicKey(skR))
            assertContentEquals(hex(v.pkEm), X25519.publicKey(v.hpke.derivePrivateKey(hex(v.ikmE))))
        }
    }

    @Test
    fun sealsAsTheVectorsSay() {
        for (v in listOf(aes128, aes256)) {
            val sealed = v.hpke.seal(hex(v.pkRm), info, hex(v.aad), hex(v.pt), v.hpke.derivePrivateKey(hex(v.ikmE)))
            assertContentEquals(hex(v.pkEm), sealed.enc)
            assertContentEquals(hex(v.ct), sealed.ciphertext)
        }
    }

    @Test
    fun opensTheVectorsCiphertext() {
        for (v in listOf(aes128, aes256)) {
            assertContentEquals(hex(v.pt), v.hpke.open(hex(v.pkEm), hex(v.skRm), info, hex(v.aad), hex(v.ct)))
        }
    }

    @Test
    fun aRandomSealOpensWithTheRightKeyOnly() {
        val random = SecureRandom()
        val recipient = X25519.newPrivateKey(random)
        val other = X25519.newPrivateKey(random)
        val message = "hunter2".encodeToByteArray()
        val sealed = Hpke.SHARING.seal(X25519.publicKey(recipient), info, byteArrayOf(1), message, random)

        assertContentEquals(message, Hpke.SHARING.open(sealed.enc, recipient, info, byteArrayOf(1), sealed.ciphertext))
        assertFailsWith<SealFailure> { Hpke.SHARING.open(sealed.enc, other, info, byteArrayOf(1), sealed.ciphertext) }
        assertFailsWith<SealFailure> { Hpke.SHARING.open(sealed.enc, recipient, info + 0, byteArrayOf(1), sealed.ciphertext) }
        assertFailsWith<SealFailure> { Hpke.SHARING.open(sealed.enc, recipient, info, byteArrayOf(2), sealed.ciphertext) }
        val changed = sealed.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFailsWith<SealFailure> { Hpke.SHARING.open(sealed.enc, recipient, info, byteArrayOf(1), changed) }
    }

    @Test
    fun refusesAPublicKeyOfSmallOrder() {
        val recipient = X25519.newPrivateKey(SecureRandom())
        // The point of order 1 (u = 0) and one of order 8 (RFC 7748's list of small-order points).
        for (point in listOf("00".repeat(32), "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800")) {
            assertFailsWith<SealFailure> { X25519.sharedSecret(recipient, hex(point)) }
        }
    }
}
