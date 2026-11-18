package dev.keething.kdbx.reader

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.FieldValue
import dev.keething.kdbx.Fixtures
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.xml.KdbxEntry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * KDBX 3.1 (keething-spec kdbx.kdbx3): KeePassDX 4.5.8's new database (`fixtures/interop/keepassdx-4.5.8.kdbx`: AES,
 * AES-KDF, gzip, Salsa20, `Meta/HeaderHash`, a compressed attachment in `Meta/Binaries`), the Mac's `kdbx3.kdbx`, and
 * altered copies of the first.
 */
class Kdbx3ReaderTest {
    private val keepassdx get() = Fixtures.bytes("interop/keepassdx-4.5.8.kdbx")

    private fun key(password: String = Fixtures.PASSWORD) = DatabaseKey.of(Secret.of(password))

    private fun KdbxEntry.field(key: String) = fields.last { it.key == key }.value

    private fun FieldValue.text() = when (this) {
        is FieldValue.Plain -> text
        is FieldValue.Protected -> secret.reveal()
    }

    private fun KdbxEntry.title() = field("Title").text()

    // spec: kdbx.kdbx3/AC1
    @Test
    fun keepassdxsDatabaseOpensWithAesKdfFromTheTransformSeedAndRounds() {
        val database = KdbxReader.open(keepassdx, key())

        assertEquals(KdbxFormat("3.1", KdbxFormat.Cipher.AES256, KdbxFormat.Kdf.AesKdf(6_849_315), compressed = true), database.format)
        val main = database.document.entries.single { it.title() == "Main" }
        assertEquals("third-version", main.field("Password").text())
        assertEquals(listOf("Work"), database.document.entries.single { it.title() == "Work mail" }.groupPath)
        assertEquals(listOf("Work", "Projects"), database.document.entries.single { it.title() == "Project server" }.groupPath)
    }

    @Test
    fun theFormatIsReadWithoutAKey() {
        assertTrue(KdbxReader.isKdbx3(keepassdx))
        assertEquals(KdbxFormat.Kdf.AesKdf(6_849_315), KdbxReader.format(keepassdx).kdf)
        assertTrue(!KdbxReader.isKdbx3(Fixtures.bytes("kdbx4-aes-aeskdf.kdbx")))
    }

    // spec: kdbx.kdbx3/AC1
    @Test
    fun aWrongKeyFailsOnTheStreamStartBytesAndADamagedBlockOnItsHash() {
        assertFailsWith<KdbxException.WrongPassword> { KdbxReader.open(keepassdx, key("wrong")) }

        // A byte in the middle of the payload: its AES block decrypts to other bytes, inside the first data block.
        val damaged = keepassdx.also { it[it.size - 200] = (it[it.size - 200].toInt() xor 1).toByte() }
        val failure = assertFailsWith<KdbxException.Corrupt> { KdbxReader.open(damaged, key()) }
        assertEquals("block 0 hash", failure.detail)
    }

    // spec: kdbx.kdbx3/AC1
    @Test
    fun aFileCutShortIsDamagedAndNeverAWrongPassword() {
        val file = keepassdx
        // Inside the last AES block (its padding), a whole block, more than one, half the file, and all but the start.
        for (cut in listOf(1, 16, 37, file.size / 2, file.size - Header3.parse(file).end - 20)) {
            assertFailsWith<KdbxException.Corrupt>("cut by $cut") { KdbxReader.open(file.copyOf(file.size - cut), key()) }
        }
        // A wrong key still fails on the stream start bytes, cut or not.
        assertFailsWith<KdbxException.WrongPassword> { KdbxReader.open(file.copyOf(file.size - 37), key("wrong")) }
    }

    // spec: kdbx.kdbx3/AC2
    @Test
    fun protectedValuesAreDecryptedWithSalsa20InDocumentOrderHistoryIncluded() {
        val database = KdbxReader.open(keepassdx, key())
        val main = database.document.entries.single { it.title() == "Main" }

        assertIs<FieldValue.Protected>(main.field("PIN"))
        assertEquals("1234", main.field("PIN").text())
        assertTrue(main.field("otp").text().contains("secret=JBSWY3DPEHPK3PXP"))
        // After Main's history in the stream: right only if the history's values moved it on.
        assertEquals("rS?~;qP/5Os9a+", database.document.entries.single { it.title() == "Work mail" }.field("Password").text())

        val history = KdbxReader.open(keepassdx, key(), historyOf = main.uuid).document.history
        assertEquals(listOf("S3cret-main!", "first-version", "second-version", "third-version"), history.map { it.field("Password").text() })
    }

    // spec: kdbx.kdbx3/AC3
    @Test
    fun attachmentsComeFromMetaBinariesAndTimesFromIso8601() {
        val database = KdbxReader.open(keepassdx, key())
        val main = database.document.entries.single { it.title() == "Main" }

        val note = main.attachments.single()
        assertEquals("note.txt", note.name)
        assertEquals("Interop test attachment. Nothing secret here.\n", database.binaries[note.ref!!].reveal())
        assertEquals(Instant.parse("2026-11-05T16:51:49Z"), main.lastModified)
        assertEquals(Instant.parse("2026-11-05T16:45:35Z"), main.created)
        assertEquals(Instant.parse("2027-02-26T23:00:17Z"), main.expiryTime)
        assertTrue(main.expires)
    }

    // spec: kdbx.kdbx3/AC4
    @Test
    fun aHeaderChangedAfterTheFactFailsOnTheHeaderHash() {
        // A comment field (id 1) put first in the header: nothing it says is used, only the hash covers it.
        val file = keepassdx
        val changed = file.copyOfRange(0, 12) + byteArrayOf(1, 3, 0, 'h'.code.toByte(), 'i'.code.toByte(), '!'.code.toByte()) +
            file.copyOfRange(12, file.size)

        val failure = assertFailsWith<KdbxException.Corrupt> { KdbxReader.open(changed, key()) }
        assertEquals("header hash", failure.detail)
    }

    @Test
    fun theMacsKdbx3FixtureOpens() {
        val database = KdbxReader.open(Fixtures.bytes("kdbx3.kdbx"), key())

        assertEquals("3.1", database.format.version)
        val one = database.document.entries.single { it.title() == "Entry <one> ü" }
        assertEquals("p&ss<w>rd \"ü\" 🔑", one.field("Password").text())
        assertEquals("second-password", database.document.entries.single { it.title() == "Second renamed" }.field("Password").text())
    }

    @Test
    fun aKdbx3FileIsNeverOpenedForEditing() {
        val failure = assertFailsWith<KdbxException.UnsupportedVersion> { KdbxReader.openForEditing(keepassdx, key()) }

        assertEquals(3 to 1, failure.major to failure.minor)
    }
}
