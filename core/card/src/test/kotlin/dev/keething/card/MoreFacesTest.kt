package dev.keething.card

import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The faces of bank accounts, identity documents and SSH logins (SPEC §6.2), and the offer to type them (SPEC §6.3). */
class MoreFacesTest {
    private fun build(entry: Entry, types: String) = EntryCardBuilder.build(typed(entry, types), now, de)

    // Bank numbers

    @Test
    fun anIbanIsCheckedByItsCountryLengthAndMod97() {
        assertEquals("DE89370400440532013000", Iban.of("DE89 3704 0044 0532 0130 00"))
        assertEquals("DE89370400440532013000", Iban.of(" de89370400440532013000 "))
        assertNull(Iban.of("DE89 3704 0044 0532 0131 00"), "a digit changed")
        assertNull(Iban.of("DE89 3704 0044 0532 0130 0"), "too short for Germany")
        assertNull(Iban.of("XX89 3704 0044 0532 0130 00"), "no such country")
        assertNull(Iban.of("DE89-3704-0044"))
        assertEquals("GB82WEST12345698765432", Iban.of("GB82 WEST 1234 5698 7654 32"))
        assertEquals("NL91 ABNA 0417 1643 00", Iban.grouped("NL91ABNA0417164300"))
    }

    @Test
    fun aRussianAccountChecksAgainstItsBik() {
        assertTrue(RussianAccount.isBik("044525974"))
        assertFalse(RussianAccount.isBik("123456789"), "not a Russian БИК")
        assertTrue(RussianAccount.accountChecks("40817810500001234567", "044525974"))
        assertFalse(RussianAccount.accountChecks("40817810500001234568", "044525974"))
        assertTrue(RussianAccount.correspondentChecks("30101810900000000974", "044525974"))
        assertFalse(RussianAccount.correspondentChecks("30101810900000000975", "044525974"))
        assertEquals("40817 810 5 0000 1234567", RussianAccount.grouped("40817810500001234567"))
        assertEquals("40817810500001234567", RussianAccount.digits("40817 810 5 0000 1234567"))
    }

    @Test
    fun countriesAreReadFromCodesAndNames() {
        for (text in listOf("DE", "deu", "D", "Germany", "Deutschland", "Германия", " germany ")) assertEquals("DE", Countries.parse(text), text)
        assertEquals("RU", Countries.parse("Россия"))
        assertNull(Countries.parse("Atlantis"))
        assertEquals("🇩🇪", Countries.flag("DE"))
        assertEquals("Germany", Countries.name("DE"))
        assertEquals("\uD83C\uDDE6\uD83C\uDDF6", Countries.UNKNOWN_FLAG)
        assertEquals("DE", Bic.country("COBADEFFXXX"))
        assertNull(Bic.country("Commerzbank"))
    }

    // Bank account

    private val bankTypes = "Holder = bank.holder\nIBAN = bank.iban\nBIC = bank.bic\nBank = bank.name\nTelephone PIN = text"
    private val bank = Entry(
        "b", title = "Girokonto",
        customFields = listOf(
            field("Holder", "Alex Morgan"), field("IBAN", "DE89370400440532013000"), field("BIC", "COBADEFFXXX"),
            field("Bank", "Commerzbank"), field("Telephone PIN", "1234", protected = true),
        ),
    )

    @Test
    fun theAccountsFaceShowsHolderIbanBicAndBank() {
        val built = build(bank, bankTypes)
        val account = built.bankAccount!!
        assertEquals("DE", account.country)
        assertEquals("IBAN", account.numberLabel)
        assertEquals("DE89 3704 0044 0532 0130 00", account.numberShown)
        assertEquals(listOf("BIC" to "COBADEFFXXX", "BANK" to "Commerzbank"), account.items.map { it.label to it.value })
        assertNull(account.problem)
        assertEquals(setOf("custom-0", "custom-1", "custom-2", "custom-3"), account.faceRowIds)
        val row = built.rows.first { it.id == "custom-1" }
        assertEquals("DE89370400440532013000", row.clipboardText())
        assertEquals("Alex Morgan\nIBAN DE89 3704 0044 0532 0130 00\nBIC COBADEFFXXX\nCommerzbank", account.shareText())
        assertEquals(EntryFace.Bank("DE", "DE89 •• 3000", "Commerzbank"), EntryFace.of(typed(bank, bankTypes)))
        assertEquals("DE89 •• 3000 · Commerzbank", EntryFace.of(typed(bank, bankTypes))!!.subtitle)
    }

    @Test
    fun aMistypedIbanSaysSo() {
        val fields = bank.customFields.toMutableList().apply { this[1] = field("IBAN", "DE89 3704 0044 0532 0131 00") }
        assertEquals("The IBAN fails its check digits (a typo?)", build(bank.copy(customFields = fields), bankTypes).bankAccount!!.problem)
    }

    private val russianTypes = "Получатель = bank.holder\nСчёт = bank.account\nБИК = bank.code\nКорр. счёт = bank.corr\nБанк = bank.name"
    private val russian = Entry(
        "r", title = "Тестбанк",
        customFields = listOf(
            field("Получатель", "Морган Алекс"), field("Счёт", "40817810500001234567"), field("БИК", "044525974"),
            field("Корр. счёт", "30101810900000000974"), field("Банк", "АО «Тестбанк»"), field("ИНН", "771234567890"),
        ),
    )

    @Test
    fun russianDetailsAreAnAccountWithItsBik() {
        val built = build(russian, russianTypes)
        val account = built.bankAccount!!
        assertEquals("RU", account.country)
        assertEquals("ACCOUNT", account.numberLabel)
        assertEquals("40817 810 5 0000 1234567", account.numberShown)
        assertEquals(listOf("BIK", "CORR. ACCOUNT", "BANK"), account.items.map { it.label })
        assertEquals("30101 810 9 0000 0000974", account.items[1].value)
        assertNull(account.problem)
        assertEquals("30101810900000000974", built.rows.first { it.label == "Корр. счёт" }.clipboardText())
        assertEquals(listOf("ИНН"), built.sections.first { it.kind == CardSection.Kind.Custom }.rows.map { it.label }, "the recipient's numbers stay rows")
        assertEquals("Морган Алекс\nСчёт 40817810500001234567\nБИК 044525974\nК/с 30101810900000000974\nАО «Тестбанк»", account.shareText())
        assertEquals("40817 •• 4567 · АО «Тестбанк»", EntryFace.of(typed(russian, russianTypes))!!.subtitle)

        val wrong = russian.customFields.toMutableList().apply { this[1] = field("Счёт", "40817810500001234568") }
        assertEquals("The account does not match the BIK (a typo?)", build(russian.copy(customFields = wrong), russianTypes).bankAccount!!.problem)
    }

    @Test
    fun anIbanWithoutATypeIsGroupedWithItsFlagAndCopiedWithoutSpaces() {
        val built = EntryCardBuilder.build(Entry("u", customFields = listOf(field("Konto", "de89 3704 0044 0532 0130 00"), field("Note", "DE12"))), now, de)
        val row = built.rows.first { it.label == "Konto" }
        assertEquals("🇩🇪  DE89 3704 0044 0532 0130 00", row.revealedText())
        assertEquals("DE89370400440532013000", row.clipboardText().uppercase())
        assertEquals("Note", built.rows.first { !it.copiesCompact }.label)
        assertEquals(listOf(FieldType.BANK_IBAN, FieldType.TEXT), EntryCardBuilder.guessedTypes(built.rows.map { field(it.label, it.storedText()) }))
    }

    // Identity document

    private val docTypes = "Type = doc.type\nNumber = doc.number\nName = doc.name\nCountry = doc.country\nBorn = doc.birth\nIssued = doc.issued\nExpires = doc.expiry"
    private val passport = Entry(
        "p", title = "Passport",
        customFields = listOf(
            field("Type", "Passport"), field("Number", "C01X00T47"), field("Name", "Alex Morgan"), field("Country", "D"),
            field("Born", "1990-08-12"), field("Issued", "2019-03-04"), field("Expires", "2027-03-03"), field("Authority", "Stadt Köln"),
        ),
    )

    @Test
    fun theDocumentsFaceShowsItsPartsAndItsExpiryIsADeadline() {
        val built = build(passport, docTypes)
        val document = built.document!!
        assertEquals("PASSPORT", document.heading())
        assertEquals("Germany", document.country, "a code is named")
        assertEquals("DE", document.countryCode)
        assertEquals(day(1990, 8, 12), document.birth!!.date)
        assertEquals(ExpiryHint.Status.SOON, document.expiryHint!!.status)
        assertEquals((0..6).map { "custom-$it" }.toSet(), document.faceRowIds)
        assertEquals(listOf("Authority"), built.sections.first { it.kind == CardSection.Kind.Custom }.rows.map { it.label })
        assertNotNull(built.deadline, "the document's expiry is a deadline")
        assertEquals("Passport · Germany · until 03/2027", EntryFace.of(typed(passport, docTypes))!!.subtitle)
    }

    // SSH

    private val sshTypes = "Host = ssh.host\nPort = ssh.port"
    private val server = Entry("s", title = "Database server", username = "alex", customFields = listOf(field("Host", "db.example.com"), field("Port", "2222")))

    @Test
    fun theSshFaceHasTheCommandAndAConfigBlock() {
        val login = build(server, sshTypes).ssh!!
        assertEquals("ssh -p 2222 alex@db.example.com", login.command())
        assertEquals("Host database-server\n  HostName db.example.com\n  User alex\n  Port 2222", login.config())
        assertEquals("ssh://alex@db.example.com:2222", login.uri)
        assertEquals(RowIds.USERNAME, login.userRowId, "the user is the entry's username")
        assertEquals(setOf("custom-0", "custom-1"), login.faceRowIds)
        assertEquals("alex@db.example.com:2222", EntryFace.of(typed(server, sshTypes))!!.subtitle)
    }

    @Test
    fun aKeyInAFileIsTheCommandsIdentityAnAttachmentsComesThroughTheAgent() {
        val login = build(server, sshTypes).ssh!!
        val file = SshKeyLocation.InFile("~/.ssh/id ed25519")
        assertEquals("ssh -i ~/'.ssh/id ed25519' -p 2222 alex@db.example.com", login.command(file))
        assertEquals(
            "Host database-server\n  HostName db.example.com\n  User alex\n  Port 2222\n  IdentityFile \"~/.ssh/id ed25519\"\n  IdentitiesOnly yes",
            login.config(file),
        )
        assertEquals("ssh -p 2222 alex@db.example.com", login.command(SshKeyLocation.InAttachment("id_ed25519")))
        assertTrue(login.config(SshKeyLocation.InAttachment("id_ed25519")).endsWith("\n  # key id_ed25519 via the SSH agent"))
    }

    @Test
    fun aHostMayWriteItsUserAndPort() {
        assertEquals(SshTarget("db.example.com", "root", 22), SshTarget.parse("ssh://root@db.example.com:22/"))
        assertEquals(SshTarget("2001:db8::1", null, 2200), SshTarget.parse("[2001:db8::1]:2200"))
        assertEquals(SshTarget("2001:db8::1", null, null), SshTarget.parse("2001:db8::1"))
        assertEquals(SshTarget("192.168.178.20", "pi", null), SshTarget.parse("pi@192.168.178.20"))
        assertNull(SshTarget.parse("db example com"))
        assertNull(SshTarget.parse("db.example.com:99999"))

        val written = Entry("w", username = "other", customFields = listOf(field("Host", "pi@192.168.178.20:22")))
        val login = build(written, "Host = ssh.host").ssh!!
        assertEquals("ssh pi@192.168.178.20", login.command(), "the host's user before the entry's, port 22 left out")
        assertNull(login.userRowId)
    }

    // SSH keys

    @Test
    fun keeAgentSettingsSayWhereTheKeyIs() {
        val settings = """<?xml version="1.0" encoding="UTF-16"?>
            |<EntrySettings><AllowUseOfSshKey>true</AllowUseOfSshKey><Location><SelectedType>file</SelectedType>
            |<AttachmentName>id_old</AttachmentName><FileName>~/.ssh/id_ed25519_db</FileName></Location></EntrySettings>""".trimMargin()
        val attachments = listOf(Attachment("KeeAgent.settings", 1), Attachment("id_old", 1))
        assertEquals(SshKeyLocation.InFile("~/.ssh/id_ed25519_db"), SshKeyLocation.of(settings.toByteArray(Charsets.UTF_16LE), attachments))
        val inAttachment = settings.replace(">file<", ">attachment<")
        assertEquals(SshKeyLocation.InAttachment("id_old"), SshKeyLocation.of(inAttachment.toByteArray(Charsets.UTF_8), attachments))
        assertEquals(
            SshKeyLocation.InAttachment("id_ed25519"),
            SshKeyLocation.of(null, listOf(Attachment("KeeAgent.settings", 1), Attachment("id_ed25519", 1), Attachment("id_ed25519.pub", 1))),
            "unreadable settings: the only key-like attachment",
        )
        assertNull(SshKeyLocation.of(null, listOf(Attachment("a", 1), Attachment("b", 1))))
    }

    @Test
    fun thePublicKeyIsReadWithoutThePassphrase() {
        val plain = SshPublicKey.ofPrivateKey(KEY.encodeToByteArray())!!
        assertEquals("ED25519", plain.displayType)
        assertEquals("SHA256:6IK1fKL0N2ghqRZbLPbeRBBpe1b09igI53UhdvRz3yY", plain.fingerprint)
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOWVGtt4PNJgXEX0ZbWQVkWXPlxRNb3iQfdsZIhbH5OP", plain.line)
        val protected = SshPublicKey.ofPrivateKey(PROTECTED_KEY.encodeToByteArray())!!
        assertEquals("SHA256:XKASjHadgaZNyf2Uk3YtKG5uLZ6gw7SRihou3Wt24qo", protected.fingerprint)
        assertNull(SshPublicKey.ofPrivateKey("-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----".encodeToByteArray()))
    }

    // The offer

    @Test
    fun anIbanBikOrHostNamedFieldsAreOfferedTheirTypes() {
        val iban = TypeSuggestion.of(Entry("i", customFields = listOf(field("Account", "DE89370400440532013000"), field("SWIFT", "COBADEFFXXX"), field("Bank name", "Commerzbank"))))!!
        assertEquals(TypeSuggestion.Kind.BANK_ACCOUNT, iban.kind)
        assertEquals(mapOf("Account" to FieldType.BANK_IBAN, "SWIFT" to FieldType.BANK_BIC, "Bank name" to FieldType.BANK_NAME), iban.types)

        val ru = TypeSuggestion.of(russian)!!
        assertEquals(FieldType.BANK_ACCOUNT, ru.types["Счёт"])
        assertEquals(FieldType.BANK_CODE, ru.types["БИК"])
        assertEquals(FieldType.BANK_CORR, ru.types["Корр. счёт"])
        assertEquals(FieldType.BANK_HOLDER, ru.types["Получатель"])

        val doc = TypeSuggestion.of(Entry("d", customFields = listOf(field("Passport number", "C01X00T47"), field("Expiry date", "2029-03-03"))))!!
        assertEquals(TypeSuggestion.Kind.DOCUMENT, doc.kind)
        assertNull(TypeSuggestion.of(Entry("n", customFields = listOf(field("Number", "123"), field("Expiry date", "2029-03")))), "a bare number and an expiry could be a card's")

        val ssh = TypeSuggestion.of(Entry("s", customFields = listOf(field("Hostname", "db.example.com"), field("Port", "2222"))))!!
        assertEquals(mapOf("Hostname" to FieldType.SSH_HOST, "Port" to FieldType.SSH_PORT), ssh.types)
        assertNull(TypeSuggestion.of(Entry("h", customFields = listOf(field("Server", "mail.example.com")))), "a host alone could be a website's")
    }

    private companion object {
        /** Throwaway keys made for these tests (`ssh-keygen -t ed25519`), the second with a passphrase. */
        const val KEY = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACDllRrbeDzSYFxF9GW1kFZFlz5cUTW94kH3bGSIWx+TjwAAAIiliUIepYlC
HgAAAAtzc2gtZWQyNTUxOQAAACDllRrbeDzSYFxF9GW1kFZFlz5cUTW94kH3bGSIWx+Tjw
AAAEDwAY1HuzCfapgDFyLL30XsbQOUq2FnnkXU7ptFBdYteuWVGtt4PNJgXEX0ZbWQVkWX
PlxRNb3iQfdsZIhbH5OPAAAABHRlc3QB
-----END OPENSSH PRIVATE KEY-----
"""
        const val PROTECTED_KEY = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABAdM7XlRn
7zHFYQGF12ceFDAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIHPChCSEpBsfVeNy
OZ22gsssjrmvHQfO7LLhhD3ZR1g/AAAAkHpAlTTgIUOq1ypbxY2Ao07aa/9JfUWo6QuV/+
S5uqqBf8RtMvYh8rkIGJsLGHe5ikORIpWypll016637QH73suFd2KFEGmJ7A9M/32aOSd9
YUxFIRQ+GuLZVZ0oqe7znqMZN0MTb0xdu2nh/yI8isJEcW4lM4k2dIT1Nsr7Pn5o7IvnYS
nijKRuoT39NEdmVA==
-----END OPENSSH PRIVATE KEY-----
"""
    }
}
