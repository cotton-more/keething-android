package dev.keething.android.biometric

import dev.keething.domain.secret.Secret
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The keyring's format (SPEC §5; plan AD5), with a software RSA key in place of the Keystore's. */
class KeyringTest {
    private val keystore = rsaKeys()

    @Test
    fun oneOpeningOfThePrivateKeyOpensEveryDatabasesPassword() {
        val keyring = Keyring.make(keystore.public)
        val personal = SealedPassword.seal(Secret.of("personal-pw"), keyring.publicKey)
        val work = SealedPassword.seal(Secret.of("work-pw"), keyring.publicKey)

        val privateKey = Keyring.read(keyring.encoded()).privateKey(decrypting(keystore))

        assertEquals("personal-pw", SealedPassword.open(personal, Keyring.opening(privateKey)).reveal())
        assertEquals("work-pw", SealedPassword.open(work, Keyring.opening(privateKey)).reveal())
    }

    @Test
    fun itsPublicKeyIsReadBackWithoutTheKeystoreSoStoringNeedsNoFingerprint() {
        val keyring = Keyring.make(keystore.public)

        assertEquals(keyring.publicKey, Keyring.read(keyring.encoded()).publicKey)
    }

    @Test
    fun thePrivateKeyIsNotInTheFileInClearAndOnlyTheKeystoresKeyOpensIt() {
        val encoded = Keyring.make(keystore.public).encoded()

        // The private exponent: the modulus is in the public key, in clear by design.
        val exponent = (Keyring.read(encoded).privateKey(decrypting(keystore)) as RSAPrivateCrtKey).privateExponent.toByteArray()
        assertFalse(String(encoded, Charsets.ISO_8859_1).contains(String(exponent.copyOfRange(8, 72), Charsets.ISO_8859_1)))
        assertFailsWith<UnreadableSealException> { Keyring.read(encoded).privateKey(decrypting(rsaKeys())) }
    }

    @Test
    fun aPasswordSealedForAnotherKeyringDoesNotOpen() {
        val first = Keyring.make(keystore.public)
        val second = Keyring.make(keystore.public)
        val sealed = SealedPassword.seal(Secret.of("pw"), first.publicKey)

        val privateKey = second.privateKey(decrypting(keystore))

        assertFailsWith<UnreadableSealException> { SealedPassword.open(sealed, Keyring.opening(privateKey)) }
    }

    @Test
    fun cutChangedOrForeignBytesAreNoKeyring() {
        val encoded = Keyring.make(keystore.public).encoded()

        for (length in listOf(0, 4, 6, 7, 100, 300)) {
            assertFailsWith<UnreadableSealException>("$length bytes") { Keyring.read(encoded.copyOf(length)) }
        }
        assertFailsWith<UnreadableSealException> { Keyring.read(SealedPassword.seal(Secret.of("pw"), keystore.public)) }
        val sealedPartChanged = encoded.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<UnreadableSealException> { Keyring.read(sealedPartChanged).privateKey(decrypting(keystore)) }
    }

    private fun rsaKeys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun decrypting(keys: KeyPair): Cipher =
        Cipher.getInstance(SealedPassword.RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, keys.private, SealedPassword.OAEP) }
}
