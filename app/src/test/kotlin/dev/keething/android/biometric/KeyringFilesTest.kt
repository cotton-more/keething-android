package dev.keething.android.biometric

import dev.keething.domain.biometric.StoredPasswordOpening
import dev.keething.domain.secret.Secret
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import javax.crypto.Cipher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The stored passwords' files (SPEC §5; plan AD5's review): which failure costs nothing, one database's password, or
 * every one; a software key in place of the Keystore's.
 */
class KeyringFilesTest {
    /**
     * The Keystore's key: [invalidated] as by a fingerprint change; [failing]: the Keystore fails to give it; [refusing]:
     * the operation after the prompt fails (here as a wrong private key does: the same catch as a Keystore error there).
     */
    private class SoftwareKey : FingerprintKey {
        var pair: KeyPair? = null
        var invalidated = false
        var failing = false
        var refusing = false
        var created = 0

        override fun publicKey(): PublicKey? = pair?.public

        override fun decryptingCipher(): Cipher? {
            if (failing) throw IOException("the Keystore did not give the key")
            val pair = pair?.takeUnless { invalidated } ?: return null
            // A refusal: the operation fails, as a cipher for another key does.
            val key = if (refusing) rsaKeys().private else pair.private
            return Cipher.getInstance(SealedPassword.RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, SealedPassword.OAEP) }
        }

        override fun create(): PublicKey {
            created++
            invalidated = false
            return rsaKeys().also { pair = it }.public
        }

        override fun delete() {
            pair = null
        }
    }

    private val folder = Files.createTempDirectory("keyring").toFile()
    private val key = SoftwareKey()
    private val files = KeyringFiles(folder, key)

    @AfterTest
    fun removeTheFolder() {
        folder.deleteRecursively()
    }

    private fun opened(database: String): StoredPasswordOpening = when (val prepared = files.prepare(database)) {
        is KeyringFiles.Prepared.Answer -> prepared.opening
        is KeyringFiles.Prepared.Ask -> files.open(prepared)
    }

    private fun StoredPasswordOpening.text(): String = assertIs<StoredPasswordOpening.Opened>(this).password.reveal()

    @Test
    fun eachDatabasesPasswordOpensAsItsOwnWithOneKeyAndOneKeyring() {
        files.save("personal", Secret.of("personal-pw"))
        files.save("work", Secret.of("work-pw"))

        assertEquals("personal-pw", opened("personal").text())
        assertEquals("work-pw", opened("work").text())
        assertEquals(1, key.created, "one Keystore key for every database")
        assertTrue(files.hasPassword("work"))
        assertFalse(files.hasPassword("family"))
        assertEquals(StoredPasswordOpening.NothingStored, opened("family"))
    }

    @Test
    fun savingAgainReplacesTheDatabasesOwnOnly() {
        files.save("personal", Secret.of("old"))
        files.save("work", Secret.of("work-pw"))

        files.save("personal", Secret.of("new"))

        assertEquals("new", opened("personal").text())
        assertEquals("work-pw", opened("work").text())
    }

    @Test
    fun theKeystoreRefusingAfterThePromptIsReportedAndCostsNothing() {
        files.save("personal", Secret.of("pw"))
        key.refusing = true

        assertFailsWith<IOException> { opened("personal") }

        key.refusing = false
        assertEquals("pw", opened("personal").text(), "kept: it may have been for a moment")
    }

    @Test
    fun theKeystoreFailingToGiveTheKeyIsReportedAndNeitherCostsNorReplacesAnything() {
        files.save("personal", Secret.of("pw"))
        key.failing = true

        assertFailsWith<IOException> { opened("personal") }
        assertFailsWith<IOException> { files.save("work", Secret.of("work-pw")) }
        assertEquals(1, key.created, "no new key for a passing failure")

        key.failing = false
        assertEquals("pw", opened("personal").text())
    }

    @Test
    fun aFingerprintChangeIsFoundBeforeThePromptAndCostsEveryDatabase() {
        files.save("personal", Secret.of("pw"))
        key.invalidated = true

        assertEquals(StoredPasswordOpening.KeyInvalidated, opened("personal"))
    }

    @Test
    fun aKeyringMadeForAKeySinceReplacedIsFoundBeforeThePrompt() {
        // The key was made again and the keyring of before stayed (its writing failed, say).
        files.save("personal", Secret.of("pw"))
        key.create()

        assertEquals(StoredPasswordOpening.KeyInvalidated, opened("personal"))
    }

    @Test
    fun aSaveAfterAFingerprintChangeMakesANewKeyAndKeyringAndTheOthersSayItWhenTried() {
        files.save("personal", Secret.of("pw"))
        files.save("work", Secret.of("work-pw"))
        key.invalidated = true

        files.save("personal", Secret.of("pw"))

        assertEquals(2, key.created)
        assertEquals("pw", opened("personal").text())
        assertTrue(files.hasPassword("work"), "not deleted unseen")
        assertEquals(StoredPasswordOpening.CopyInvalidated, opened("work"), "said on its own lock screen when tried")
    }

    @Test
    fun aDamagedKeyringCostsEveryDatabaseAndTheNextSaveMakesANewOne() {
        files.save("personal", Secret.of("pw"))
        File(folder, "keyring").writeBytes("not a keyring".encodeToByteArray())

        assertEquals(StoredPasswordOpening.KeyInvalidated, opened("personal"))

        files.save("work", Secret.of("work-pw"))
        assertEquals("work-pw", opened("work").text())
        assertEquals(1, key.created, "the key was fine: only the keyring is new")
        assertEquals(StoredPasswordOpening.CopyInvalidated, opened("personal"), "sealed for the keyring of before")
    }

    @Test
    fun aKeyringWhosePrivateKeyNoLongerOpensCostsEveryDatabase() {
        files.save("personal", Secret.of("pw"))
        val keyring = File(folder, "keyring")
        keyring.writeBytes(keyring.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })

        assertEquals(StoredPasswordOpening.KeyInvalidated, opened("personal"))
    }

    @Test
    fun oneDatabasesDamagedFileCostsItsOwnOnly() {
        files.save("personal", Secret.of("pw"))
        files.save("work", Secret.of("work-pw"))
        val work = File(folder, "databases/work")
        work.writeBytes(work.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })

        assertEquals(StoredPasswordOpening.CopyInvalidated, opened("work"))
        assertEquals("pw", opened("personal").text())
    }

    @Test
    fun aDatabasesFileWithoutAKeyringCanNeverOpen() {
        files.save("personal", Secret.of("pw"))
        File(folder, "keyring").delete()

        assertEquals(StoredPasswordOpening.CopyInvalidated, opened("personal"))
    }

    @Test
    fun deletingOneLeavesTheOthersAndDeletingAllLeavesNothing() {
        files.save("personal", Secret.of("pw"))
        files.save("work", Secret.of("work-pw"))

        assertTrue(files.delete("work"))
        assertFalse(files.delete("work"), "nothing to delete is no error")
        assertEquals("pw", opened("personal").text())

        files.deleteAll()
        assertFalse(files.hasPassword("personal"))
        assertFalse(File(folder, "keyring").exists())
        assertEquals(null, key.pair, "the key goes too")
        files.deleteAll()
    }

    private companion object {
        fun rsaKeys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
