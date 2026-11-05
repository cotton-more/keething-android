package dev.keething.kdbx.reader

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.FieldValue
import dev.keething.kdbx.Fixtures
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.xml.KdbxEntry
import dev.keething.kdbx.xml.KdbxTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A KDBX 3.1 database written as KDBX 4.0 (keething-spec kdbx.kdbx3 AC8): KeePassDX 4.5.8's new database and the Mac's
 * `kdbx3.kdbx`, read back with the password alone.
 */
class Kdbx3UpgradeTest {
    private val keepassdx get() = Fixtures.bytes("interop/keepassdx-4.5.8.kdbx")

    private fun key() = DatabaseKey.of(Secret.of(Fixtures.PASSWORD))

    /** What the reader makes of a file, to compare: every value in the clear, the attachments' bytes, times to the second. */
    private fun view(database: KdbxDatabase): List<Any?> {
        fun Instant?.seconds() = this?.truncatedTo(ChronoUnit.SECONDS)
        fun KdbxEntry.view() = copy(
            fields = emptyList(),
            attachments = emptyList(),
            expiryTime = expiryTime.seconds(),
            lastModified = lastModified.seconds(),
            created = created.seconds(),
        ) to listOf(
            fields.map { field ->
                field.key to when (val value = field.value) {
                    is FieldValue.Plain -> value.text
                    is FieldValue.Protected -> "protected " + value.secret.reveal()
                }
            },
            attachments.map { it.name to it.ref?.let { ref -> database.binaries[ref].reveal() } },
        )
        val document = database.document
        return listOf(
            document.meta.copy(customIcons = emptyMap()),
            document.meta.customIcons.mapValues { it.value.image.toList() },
            document.groups,
            document.entries.map { it.view() },
            document.history.map { it.view() },
        )
    }

    private fun upgraded(file: ByteArray): ByteArray {
        val editable = KdbxReader.openForUpgrade(file, key())
        try {
            return editable.write().file
        } finally {
            editable.wipe()
        }
    }

    // spec: kdbx.kdbx3/AC8
    @Test
    fun keepassdxsDatabaseIsWrittenAsKdbx40WithItsCipherAndItsKeyDerivationAsRead() {
        val file = keepassdx
        val written = upgraded(file)

        assertFalse(KdbxReader.isKdbx3(written))
        val before = KdbxReader.open(file, key())
        val after = KdbxReader.open(written, key())
        assertEquals(KdbxFormat("4.0", KdbxFormat.Cipher.AES256, KdbxFormat.Kdf.AesKdf(6_849_315), compressed = true), after.format)
        // The same transform seed and rounds: the key derived for the 3.1 file opens the new one.
        assertContentEquals(Header3.parse(file).kdf.identity, Header.parse(written).kdf.identity)
        assertEquals(view(before), view(after))
        assertTrue(after.binaries.isNotEmpty())
    }

    // spec: kdbx.kdbx3/AC8
    @Test
    fun theMacsKdbx3FileComesOutTheSame() {
        val file = Fixtures.bytes("kdbx3.kdbx")
        val after = KdbxReader.open(upgraded(file), key())

        assertEquals("4.0", after.format.version)
        assertEquals(view(KdbxReader.open(file, key())), view(after))
    }

    @Test
    fun theXmlIsKdbx4s() {
        val editable = KdbxReader.openForUpgrade(keepassdx, key())
        val xml = try {
            editable.xmlWithoutSecrets().decodeToString()
        } finally {
            editable.wipe()
        }

        assertFalse("<Binaries" in xml, "the attachments are in the inner header")
        assertFalse("<HeaderHash" in xml)
        val times = Regex("<(\\w+(?:Time|Changed))>([^<]*)</").findAll(xml).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(times.isNotEmpty())
        for ((name, text) in times) assertFalse(KdbxTime.isIso(text), "$name $text")
    }

    @Test
    fun onlyAKdbx3FileIsUpgradedAndAWrongKeyOpensNothing() {
        assertFailsWith<KdbxException.UnsupportedVersion> { KdbxReader.openForUpgrade(Fixtures.bytes("kdbx4-aes-aeskdf.kdbx"), key()) }
        assertFailsWith<KdbxException.WrongPassword> { KdbxReader.openForUpgrade(keepassdx, DatabaseKey.of(Secret.of("wrong"))) }
    }
}
