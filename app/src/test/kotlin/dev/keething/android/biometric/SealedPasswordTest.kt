package dev.keething.android.biometric

import dev.keething.domain.secret.Secret
import java.security.KeyPair
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The stored password's format, with a software RSA key in place of the Keystore's (SPEC §5). */
class SealedPasswordTest {
    private val keys = rsaKeys()

    @Test
    fun aSealedPasswordOpensWithThePrivateKey() {
        val sealed = SealedPassword.seal(Secret.of("correct horse battery staple"), keys.public)

        assertEquals("correct horse battery staple", SealedPassword.open(sealed, decrypting(keys)).reveal())
    }

    @Test
    fun longAndNonAsciiPasswordsFit() {
        // Longer than what RSA alone could take (190 bytes): the RSA key only wraps a random AES key.
        val password = "Пароль-ключ-äöü-🔑-".repeat(40)

        val sealed = SealedPassword.seal(Secret.of(password), keys.public)

        assertEquals(password, SealedPassword.open(sealed, decrypting(keys)).reveal())
    }

    @Test
    fun thePasswordIsNotInTheSealedBytesAndEverySealIsNew() {
        val password = "correct horse battery staple"

        val first = SealedPassword.seal(Secret.of(password), keys.public)
        val second = SealedPassword.seal(Secret.of(password), keys.public)

        assertFalse(String(first, Charsets.ISO_8859_1).contains(password))
        assertFalse(first.contentEquals(second), "a new AES key and IV each time")
    }

    @Test
    fun anotherKeyDoesNotOpenIt() {
        val sealed = SealedPassword.seal(Secret.of("pw"), keys.public)

        assertFailsWith<UnreadableSealException> { SealedPassword.open(sealed, decrypting(rsaKeys())) }
    }

    @Test
    fun anyChangedByteIsNoticed() {
        val sealed = SealedPassword.seal(Secret.of("pw"), keys.public)

        for (index in sealed.indices) {
            val changed = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFailsWith<UnreadableSealException>("byte $index") { SealedPassword.open(changed, decrypting(keys)) }
        }
    }

    @Test
    fun cutOrForeignBytesAreUnreadable() {
        val sealed = SealedPassword.seal(Secret.of("pw"), keys.public)

        for (length in listOf(0, 3, 5, 7, 200, sealed.size - 1)) {
            assertFailsWith<UnreadableSealException>("$length bytes") { SealedPassword.open(sealed.copyOf(length), decrypting(keys)) }
        }
        assertFailsWith<UnreadableSealException> { SealedPassword.open(sealed + byteArrayOf(0), decrypting(keys)) }
    }

    private fun rsaKeys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun decrypting(keys: KeyPair): Cipher =
        Cipher.getInstance(SealedPassword.RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, keys.private, SealedPassword.OAEP) }
}
