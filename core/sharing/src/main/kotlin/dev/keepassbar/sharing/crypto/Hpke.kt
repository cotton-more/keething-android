package dev.keepassbar.sharing.crypto

import java.security.SecureRandom
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HPKE (RFC 9180) in base mode, one message per key: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256 and AES-GCM. The
 * standard way to encrypt for a public key, so another implementation (CryptoKit's `HPKE` on the Mac) opens
 * what this one seals. AES-256-GCM is what the sharing uses ([SHARING]); AES-128-GCM is here for RFC 9180's
 * test vectors of the same KEM and KDF.
 */
internal class Hpke private constructor(private val aeadId: Int, private val keySize: Int) {
    /** What travels: the ephemeral public key (`enc`) and the AEAD's output with its tag. */
    class Sealed(val enc: ByteArray, val ciphertext: ByteArray)

    fun seal(recipientPublicKey: ByteArray, info: ByteArray, aad: ByteArray, plaintext: ByteArray, random: SecureRandom): Sealed {
        val ephemeral = X25519.newPrivateKey(random)
        try {
            return seal(recipientPublicKey, info, aad, plaintext, ephemeral)
        } finally {
            ephemeral.fill(0)
        }
    }

    /** With a given ephemeral key: the test vectors' `skEm`. */
    fun seal(recipientPublicKey: ByteArray, info: ByteArray, aad: ByteArray, plaintext: ByteArray, ephemeral: ByteArray): Sealed {
        val enc = X25519.publicKey(ephemeral)
        val dh = X25519.sharedSecret(ephemeral, recipientPublicKey)
        val key = keySchedule(extractAndExpand(dh, enc + recipientPublicKey), info)
        try {
            return Sealed(enc, key.aead(Cipher.ENCRYPT_MODE, aad, plaintext))
        } finally {
            dh.fill(0)
            key.wipe()
        }
    }

    /** The plaintext; a [SealFailure] when the message was changed or is not for [recipientPrivateKey]. */
    fun open(enc: ByteArray, recipientPrivateKey: ByteArray, info: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (enc.size != X25519.KEY_SIZE) throw SealFailure("enc")
        val dh = X25519.sharedSecret(recipientPrivateKey, enc)
        val recipientPublicKey = X25519.publicKey(recipientPrivateKey)
        val key = keySchedule(extractAndExpand(dh, enc + recipientPublicKey), info)
        try {
            return key.aead(Cipher.DECRYPT_MODE, aad, ciphertext)
        } catch (_: GeneralSecurityException) {
            throw SealFailure("the message was changed or is not for this key")
        } finally {
            dh.fill(0)
            key.wipe()
        }
    }

    /** DeriveKeyPair (RFC 9180 §7.1.3): the private key the test vectors derive from their `ikm`. */
    fun derivePrivateKey(ikm: ByteArray): ByteArray {
        val prk = labeledExtract(KEM_SUITE, ByteArray(0), "dkp_prk", ikm)
        try {
            return labeledExpand(KEM_SUITE, prk, "sk", ByteArray(0), X25519.KEY_SIZE)
        } finally {
            prk.fill(0)
        }
    }

    private class Key(val key: ByteArray, val nonce: ByteArray) {
        /** Sequence number 0, the only one: the nonce is the base nonce. */
        fun aead(mode: Int, aad: ByteArray, input: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad)
                doFinal(input)
            }

        fun wipe() {
            key.fill(0)
            nonce.fill(0)
        }
    }

    private fun extractAndExpand(dh: ByteArray, kemContext: ByteArray): ByteArray {
        val prk = labeledExtract(KEM_SUITE, ByteArray(0), "eae_prk", dh)
        try {
            return labeledExpand(KEM_SUITE, prk, "shared_secret", kemContext, Hkdf.HASH_SIZE)
        } finally {
            prk.fill(0)
        }
    }

    private fun keySchedule(sharedSecret: ByteArray, info: ByteArray): Key {
        val suite = "HPKE".encodeToByteArray() + i2osp(KEM_ID, 2) + i2osp(KDF_ID, 2) + i2osp(aeadId, 2)
        val pskIdHash = labeledExtract(suite, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(suite, ByteArray(0), "info_hash", info)
        val context = byteArrayOf(MODE_BASE.toByte()) + pskIdHash + infoHash
        val secret = labeledExtract(suite, sharedSecret, "secret", ByteArray(0))
        try {
            return Key(
                labeledExpand(suite, secret, "key", context, keySize),
                labeledExpand(suite, secret, "base_nonce", context, NONCE_SIZE),
            )
        } finally {
            sharedSecret.fill(0)
            secret.fill(0)
        }
    }

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray): ByteArray {
        val labeled = VERSION_LABEL + suite + label.encodeToByteArray() + ikm
        try {
            return Hkdf.extract(salt, labeled)
        } finally {
            labeled.fill(0)
        }
    }

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray =
        Hkdf.expand(prk, i2osp(length, 2) + VERSION_LABEL + suite + label.encodeToByteArray() + info, length)

    companion object {
        private const val MODE_BASE = 0
        private const val KEM_ID = 0x0020
        private const val KDF_ID = 0x0001
        private const val NONCE_SIZE = 12
        private const val TAG_BITS = 128
        private val VERSION_LABEL = "HPKE-v1".encodeToByteArray()
        private val KEM_SUITE = "KEM".encodeToByteArray() + i2osp(KEM_ID, 2)

        /** AEAD 0x0002, AES-256-GCM: the sharing's suite. */
        val SHARING = Hpke(aeadId = 0x0002, keySize = 32)

        /** AEAD 0x0001, AES-128-GCM: RFC 9180 A.1. */
        val AES_128_GCM = Hpke(aeadId = 0x0001, keySize = 16)

        private fun i2osp(value: Int, length: Int): ByteArray = ByteArray(length) { (value ushr (8 * (length - 1 - it))).toByte() }
    }
}
