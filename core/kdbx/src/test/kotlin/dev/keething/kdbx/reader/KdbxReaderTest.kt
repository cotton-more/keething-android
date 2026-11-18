package dev.keething.kdbx.reader

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.FieldValue
import dev.keething.kdbx.Fixtures
import dev.keething.kdbx.crypto.Bytes
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.xml.KdbxEntry
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac `KDBXTests` expectations, on the same fixtures. */
class KdbxReaderTest {
    private fun key(password: String = Fixtures.PASSWORD) = DatabaseKey.of(Secret.of(password))

    private fun open(name: String) = KdbxReader.open(Fixtures.bytes("$name.kdbx"), key())

    private fun KdbxEntry.field(key: String) = fields.last { it.key == key }.value

    private fun FieldValue.text() = when (this) {
        is FieldValue.Plain -> text
        is FieldValue.Protected -> secret.reveal()
    }

    private fun KdbxEntry.title() = field("Title").text()

    @Test
    fun aesArgon2dOpens() = checkFixture("kdbx4-aes-argon2d", KdbxFormat.Cipher.AES256)

    @Test
    fun chaCha20Argon2idOpens() = checkFixture("kdbx4-chacha20-argon2id", KdbxFormat.Cipher.CHACHA20)

    @Test
    fun aesAesKdfOpens() = checkFixture("kdbx4-aes-aeskdf", KdbxFormat.Cipher.AES256)

    private fun checkFixture(name: String, cipher: KdbxFormat.Cipher) {
        val database = open(name)

        assertEquals(cipher, database.format.cipher)
        val one = database.document.entries.single { it.title() == "Entry <one> ü" }
        val second = database.document.entries.single { it.title() == "Second renamed" }
        assertEquals("p&ss<w>rd \"ü\" 🔑", one.field("Password").text())
        assertIs<FieldValue.Protected>(one.field("Password"))
        assertEquals(listOf("Web & Mail"), one.groupPath)
        val custom = one.fields.filter { it.key in setOf("API key", "Empty secret", "Plain") }
        assertEquals(listOf("API key", "Empty secret", "Plain"), custom.map { it.key })
        assertEquals(listOf("s3cr3t", "", "visible"), custom.map { it.value.text() })
        assertEquals(listOf(true, true, false), custom.map { it.value is FieldValue.Protected })
        assertTrue(one.fields.any { it.key == "otp" }, "otp field read")
        // Comes after the history's protected value in the stream: right only if the order is kept.
        assertEquals("second-password", second.field("Password").text())
        assertEquals(setOf("blob.bin", "att.bin"), second.attachments.map { it.name }.toSet())
        assertEquals(3, database.binaries.size)
        val sizes = second.attachments.associate { it.name to database.binaries[it.ref!!].size }
        assertEquals(mapOf("blob.bin" to 2000, "att.bin" to 3000), sizes)
    }

    @Test
    fun theFormatIsReadWithoutAKey() {
        val format = KdbxReader.format(Fixtures.bytes("kdbx4-chacha20-argon2id.kdbx"))

        assertEquals(KdbxFormat.Cipher.CHACHA20, format.cipher)
        assertIs<KdbxFormat.Kdf.Argon2>(format.kdf)
        assertEquals(KdbxFormat.Kdf.Argon2.Variant.ID, (format.kdf as KdbxFormat.Kdf.Argon2).variant)
        assertIs<KdbxFormat.Kdf.AesKdf>(KdbxReader.format(Fixtures.bytes("kdbx4-aes-aeskdf.kdbx")).kdf)
    }

    @Test
    fun aWrongPasswordIsToldApartFromADamagedFile() {
        val file = Fixtures.bytes("kdbx4-aes-aeskdf.kdbx")
        assertFailsWith<KdbxException.WrongPassword> { KdbxReader.open(file, key("wrong")) }

        val damagedPayload = file.copyOf().apply { this[size - 100] = (this[size - 100].toInt() xor 1).toByte() }
        val payloadFailure = assertFailsWith<KdbxException.Corrupt> { KdbxReader.open(damagedPayload, key()) }
        assertEquals("block 0 HMAC", payloadFailure.detail)

        val damagedHeader = file.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }
        assertFailsWith<KdbxException> { KdbxReader.open(damagedHeader, key()) }

        assertFailsWith<KdbxException.NotKdbx> { KdbxReader.open("<KeePassFile/>".encodeToByteArray(), key()) }
        val truncated = assertFailsWith<KdbxException.Corrupt> { KdbxReader.open(file.copyOf(60), key()) }
        assertEquals("header truncated", truncated.detail)
    }

    // spec: databases.backups-window/AC5
    @Test
    fun aDamagedHeaderIsToldWithoutAKey() {
        fun damaged(bytes: ByteArray) = KdbxReader.isHeaderDamaged(ByteArrayInputStream(bytes))
        for (name in listOf("kdbx4-aes-argon2d", "kdbx4-chacha20-argon2id", "kdbx4-aes-aeskdf", "kdbx3")) {
            assertEquals(false, damaged(Fixtures.bytes("$name.kdbx")), name)
        }
        val file = Fixtures.bytes("kdbx4-aes-aeskdf.kdbx")
        assertTrue(damaged(file.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }), "a header byte changed")
        assertTrue(damaged(file.copyOf(60)), "cut short")
        assertTrue(damaged("<KeePassFile/>".encodeToByteArray()), "not a KeePass database")
        assertTrue(damaged(ByteArray(0)), "empty")
        val payload = file.copyOf().apply { this[size - 100] = (this[size - 100].toInt() xor 1).toByte() }
        assertEquals(false, damaged(payload), "the payload is not the header's: it needs the key")
    }

    @Test
    fun aFileOpenedForEditingAsItIsReadIsTheFileReadWholeAndWhatFollowsItIsLeft() {
        val file = Fixtures.bytes("kdbx4-chacha20-argon2id.kdbx")
        // A few bytes at a time, as a provider may give them.
        val input = object : FilterInputStream(ByteArrayInputStream(file + "after".encodeToByteArray())) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 7))
        }

        val streamed = KdbxReader.openForEditing(input, key())
        val whole = KdbxReader.openForEditing(file, key())

        assertEquals(whole.document.entries.map { it.uuid to it.title() }, streamed.document.entries.map { it.uuid to it.title() })
        assertEquals(whole.binaries.map { it.size }, streamed.binaries.map { it.size })
        assertEquals("after", input.readBytes().decodeToString())
        streamed.wipe()
        whole.wipe()
    }

    @Test
    fun aBlockLongerThanTheFileIsATruncatedFileNotAnAllocationOfItsSize() {
        val file = Fixtures.bytes("kdbx4-aes-aeskdf.kdbx")
        val sizeField = Header.read(ByteArrayInputStream(file)).size + 32
        val lying = file.copyOf().apply { Bytes.le32(Int.MAX_VALUE - 8).copyInto(this, sizeField) }

        val failure = assertFailsWith<KdbxException.Corrupt> { KdbxReader.openForEditing(ByteArrayInputStream(lying), key()) }

        assertEquals("block 0 truncated", failure.detail)
    }

    @Test
    fun aFileThatCannotBeReadToItsEndIsTheStoragesFailureNotADamagedFile() {
        val file = Fixtures.bytes("kdbx4-aes-aeskdf.kdbx")
        for (failingAt in listOf(20, 200, file.size / 2, file.size - 10)) {
            val input = object : InputStream() {
                private var at = 0

                override fun read(): Int {
                    if (at == failingAt) throw IOException("I/O error")
                    return if (at < file.size) file[at++].toInt() and 0xFF else -1
                }
            }

            val failure = assertFailsWith<IOException>("failing at $failingAt") { KdbxReader.openForEditing(input, key()) }

            assertEquals("I/O error", failure.message, "failing at $failingAt")
        }
    }

    @Test
    fun kdbx2IsRefusedAsUnsupported() {
        // The KDBX 3.1 fixture with its version set to 2.0 (KDBX 3.1 itself is read: Kdbx3ReaderTest).
        val file = Fixtures.bytes("kdbx3.kdbx").also { it[8] = 0; it[10] = 2 }

        val failure = assertFailsWith<KdbxException.UnsupportedVersion> { KdbxReader.open(file, key()) }

        assertEquals("KDBX 2.0", failure.message)
    }

    @Test
    fun keepass1AndKdbx2PreReleaseSignaturesAreOldFormatsNotSomeOtherFile() {
        // KeePass 1.x's second signature (0xB54BFB65) and KDBX 2's pre-release one (0xB54BFB66), little-endian at 4.
        val keepass1 = Fixtures.bytes("kdbx3.kdbx").also { it[4] = 0x65 }
        val failure = assertFailsWith<KdbxException.KeePass1> { KdbxReader.open(keepass1, key()) }
        assertEquals("KeePass 1.x (.kdb)", failure.message)
        assertFailsWith<KdbxException.KeePass1> { KdbxReader.isKdbx3(keepass1) }
        assertEquals(false, KdbxReader.isHeaderDamaged(ByteArrayInputStream(keepass1)))

        val preRelease = Fixtures.bytes("kdbx3.kdbx").also { it[4] = 0x66; it[8] = 0; it[10] = 1 }
        assertEquals("KDBX 1.0", assertFailsWith<KdbxException.UnsupportedVersion> { KdbxReader.open(preRelease, key()) }.message)
    }

    @Test
    fun aSecondReadWithTheSameKeyDerivesNothing() {
        val file = Fixtures.bytes("kdbx4-chacha20-argon2id.kdbx")
        val key = key()

        val first = KdbxReader.open(file, key)
        val second = KdbxReader.open(file, key)

        assertNotNull(first.keyDerivation)
        assertNull(second.keyDerivation, "the derived key is kept")
        assertEquals(first.document.entries, second.document.entries)
    }

    @Test
    fun aKeyIsDerivedAgainForAFileWithOtherParameters() {
        val key = key()
        KdbxReader.open(Fixtures.bytes("kdbx4-chacha20-argon2id.kdbx"), key)

        val other = KdbxReader.open(Fixtures.bytes("kdbx4-aes-argon2d.kdbx"), key)

        assertNotNull(other.keyDerivation)
    }

    @Test
    fun aWipedKeyOpensNothing() {
        val key = key()
        key.wipe()

        assertFailsWith<IllegalStateException> { KdbxReader.open(Fixtures.bytes("kdbx4-aes-aeskdf.kdbx"), key) }
    }
}
