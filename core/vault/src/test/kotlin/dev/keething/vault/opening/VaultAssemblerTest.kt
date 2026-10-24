package dev.keething.vault.opening

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.kdbx.xml.KdbxXml
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac `KDBXXMLParserTests` expectations on `fixtures/sample.xml`. */
class VaultAssemblerTest {
    private fun sample(): Vault {
        val xml = File(System.getProperty("keething.fixtures"), "sample.xml").readBytes()
        return VaultAssembler.assemble(KdbxXml.readExport(xml), binarySizes = emptyList())
    }

    private fun Vault.byTitle(title: String): Entry = entries.single { it.title == title }

    @Test
    fun entriesExcludeTheRecycleBinAndHistory() {
        val v = sample()

        assertEquals(9, v.entries.size)
        assertFalse(v.entries.any { it.title == "Deleted Thing" }, "recycle bin entries are excluded")
        assertFalse(v.entries.any { it.title == "GitHub (old title)" }, "history items are not entries")
        assertEquals(9, v.entries.map { it.id }.toSet().size, "ids are unique")
        assertEquals(listOf("Deleted Thing"), v.recycleBin.map { it.title })
    }

    @Test
    fun standardFields() {
        val gh = sample().byTitle("GitHub")

        assertEquals("GITHUB-UUID-0000000000==", gh.id)
        assertEquals("octocat", gh.username)
        assertEquals("gh-secret-pw", gh.password.reveal())
        assertEquals("https://github.com/login", gh.url)
        assertEquals("Work account", gh.notes)
        assertEquals(listOf("dev", "work"), gh.tags)
        assertEquals("G-WORK", gh.groupId)
    }

    @Test
    fun groupPathExcludesTheRootGroup() {
        val v = sample()

        assertEquals(listOf("Work"), v.byTitle("GitHub").groupPath)
        assertEquals(listOf("Work", "Git"), v.byTitle("Legacy 2FA").groupPath)
        assertEquals(emptyList(), v.byTitle("Root Entry").groupPath)
        assertEquals(listOf("Verträge"), v.byTitle("Süwag").groupPath)
    }

    @Test
    fun customFieldsKeepOrderAndProtection() {
        val v = sample()

        assertEquals(
            listOf(
                CustomField.protected("Recovery code", "abcd-efgh-ijkl"),
                CustomField.plain("Org", "Acme GmbH"),
                CustomField.plain("Empty field", ""),
            ),
            v.byTitle("GitHub").customFields,
        )
        assertEquals(
            listOf(CustomField.plain("Versicherungsnummer", "VN-123456")),
            v.byTitle("ILAY - Continentale").customFields,
        )
    }

    @Test
    fun oneTimeCodeFieldsAreNotCustomFields() {
        val v = sample()

        assertTrue(v.byTitle("Legacy 2FA").customFields.isEmpty())
        assertFalse(v.byTitle("GitHub").customFields.any { it.name == "otp" })
    }

    @Test
    fun oneTimeCodesFromALinkAndFromTheLegacyFields() {
        val v = sample()

        assertEquals(TotpConfig(Secret.of("12345678901234567890")), v.byTitle("GitHub").totp)
        assertEquals(TotpConfig(Secret.of("12345678901234567890"), digits = 8), v.byTitle("Legacy 2FA").totp)
        assertNull(v.byTitle("ILAY - Continentale").totp)
    }

    @Test
    fun unicodeIsKept() {
        val e = sample().byTitle("Пятёрочка")

        assertEquals("Карта лояльности", e.notes)
        assertEquals(listOf("магазин"), e.tags)
    }

    @Test
    fun attachmentsWithoutAFileAttachmentHaveNoSize() {
        val v = sample()

        // KDBX 4 keeps attachments in the file's inner header; an export's Meta/Binaries are not read.
        assertEquals(listOf(Attachment("passport.pdf", null)), v.byTitle("Passport scan").attachments)
        assertTrue(v.byTitle("GitHub").attachments.isEmpty())
    }

    @Test
    fun expiry() {
        val v = sample()

        assertTrue(v.byTitle("Old Forum").expires)
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), v.byTitle("Old Forum").expiryTime)
        assertFalse(v.byTitle("GitHub").expires)
    }

    @Test
    fun anUntitledEntryIsKept() {
        val e = sample().entries.single { it.id == "UNTITLED-UUID-00000000==" }

        assertEquals("", e.title)
        assertEquals("www.example.org", e.displayTitle)
    }

    @Test
    fun theFirstEntryTitledKeethingOutsideTheBinIsTheSettingsEntry() {
        fun entry(uuid: String, title: String, notes: String = "") =
            "<Entry><UUID>$uuid</UUID><String><Key>Title</Key><Value>$title</Value></String>" +
                "<String><Key>Notes</Key><Value>$notes</Value></String></Entry>"
        val xml = """
            <KeePassFile><Meta><RecycleBinUUID>BIN</RecycleBinUUID></Meta><Root><Group><UUID>R</UUID><Name>Passwords</Name>
            <Group><UUID>BIN</UUID><Name>Recycle Bin</Name>${entry("binned", "Keething", "old")}</Group>
            ${entry("a", "Mail")}
            <Group><UUID>G</UUID><Name>Tools</Name>${entry("kb", " Keething ", "x = date")}</Group>
            ${entry("kb2", "Keething", "second")}
            </Group></Root></KeePassFile>
        """.trimIndent()

        val v = VaultAssembler.assemble(KdbxXml.readExport(xml.encodeToByteArray()), emptyList())

        assertEquals("kb", v.settings?.id)
        assertEquals("x = date", v.settings?.notes)
        assertEquals(listOf("a", "kb2"), v.entries.map { it.id }, "only the first one is set apart")
        assertEquals(listOf("R", "G"), v.groups.map { it.id }, "groups outside the bin")
        assertNull(sample().settings)
    }
}
