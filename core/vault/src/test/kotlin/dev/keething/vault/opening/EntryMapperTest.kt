package dev.keething.vault.opening

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.CustomIcon
import dev.keething.domain.vault.EntryFieldTypes
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.VaultGroup
import dev.keething.kdbx.xml.KdbxAttachmentRef
import dev.keething.kdbx.xml.KdbxDocument
import dev.keething.kdbx.xml.KdbxEntry
import dev.keething.kdbx.xml.KdbxField
import dev.keething.kdbx.xml.KdbxGroup
import dev.keething.kdbx.xml.KdbxMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac `ParsingComponentTests` expectations for `EntryMapper` and `VaultAssembler`. */
class EntryMapperTest {
    private fun plain(key: String, text: String) = KdbxField(key, FieldValue.Plain(text))

    private fun protected(key: String, text: String) = KdbxField(key, FieldValue.Protected(Secret.of(text)))

    private fun raw(
        fields: List<KdbxField> = emptyList(),
        tags: String = "",
        attachments: List<KdbxAttachmentRef> = emptyList(),
        ancestors: List<String> = listOf("R", "G"),
        icon: String? = null,
    ) = KdbxEntry("id", fields, tags, attachments = attachments, groupPath = listOf("G"), ancestorGroupUuids = ancestors, customIconUuid = icon)

    private fun map(entry: KdbxEntry, sizes: List<Int> = emptyList(), icons: Set<String> = emptySet()) =
        EntryMapper(sizes, icons).map(entry)

    @Test
    fun standardFieldsMissingOnesEmptyLastDuplicateWins() {
        val firstTitle = protected("Title", "A")
        val e = map(
            raw(
                listOf(
                    firstTitle, plain("Title", "B"), plain("UserName", "u"), protected("Password", "p"),
                    plain("URL", "https://x.y"), plain("Notes", "n"),
                ),
            ),
        )

        assertEquals(listOf("B", "u", "p", "https://x.y", "n"), listOf(e.title, e.username, e.password.reveal(), e.url, e.notes))
        assertEquals("id", e.id)
        assertEquals(listOf("G"), e.groupPath)
        assertEquals("G", e.groupId)
        assertTrue((firstTitle.value as FieldValue.Protected).secret.isWiped, "a secret not kept is wiped")
        val bare = map(raw())
        assertEquals(listOf("", "", "", "", ""), listOf(bare.title, bare.username, bare.password.reveal(), bare.url, bare.notes))
    }

    @Test
    fun aPlainPasswordIsStillASecret() {
        assertEquals("p", map(raw(listOf(plain("Password", "p")))).password.reveal())
    }

    @Test
    fun customFieldsKeepOrderAndProtectionReservedKeysExcluded() {
        val reserved = listOf(
            "otp", "TOTP Seed", "TOTP Settings", "TimeOtp-Secret", "TimeOtp-Secret-Hex", "TimeOtp-Secret-Base32",
            "TimeOtp-Secret-Base64", "TimeOtp-Length", "TimeOtp-Period", "TimeOtp-Algorithm",
        ).map { protected(it, "x") }

        val e = map(raw(listOf(protected("Z", "1"), plain("Title", "t")) + reserved + plain("A", "")))

        assertEquals(listOf(CustomField.protected("Z", "1"), CustomField.plain("A", "")), e.customFields)
        assertTrue(reserved.all { (it.value as FieldValue.Protected).secret.isWiped }, "the code's fields are wiped once read")
    }

    @Test
    fun keePasssOwnFieldsAreTheCodeWhenThereIsNoOtpAndNoTotpSeed() {
        val key = "12345678901234567890123456789012"
        val keePass = listOf(
            protected("TimeOtp-Secret-Hex", key.encodeToByteArray().joinToString("") { "%02x".format(it) }),
            plain("TimeOtp-Length", "8"),
            plain("TimeOtp-Algorithm", "HMAC-SHA-256"),
            plain("HmacOtp-Counter", "3"),
        )

        val e = map(raw(keePass))

        assertEquals(TotpConfig(Secret.of(key), digits = 8, algorithm = TotpConfig.Algorithm.SHA256), e.totp)
        assertEquals(listOf(CustomField.plain("HmacOtp-Counter", "3")), e.customFields, "counter-based codes stay fields")
        val otp = plain("otp", "otpauth://totp/x?secret=GEZDGNBVGY3TQOJQ&digits=7")
        assertEquals(7, map(raw(keePass + otp)).totp?.digits, "the otp link first")
    }

    @Test
    fun tagsSplitOnSemicolonsAndCommasTrimmed() {
        assertEquals(listOf("a", "b", "c"), map(raw(tags = " a; b ,c;;, ")).tags)
        assertEquals(emptyList(), map(raw(tags = "")).tags)
    }

    @Test
    fun attachmentsResolveThroughTheFilesAttachments() {
        val e = map(
            raw(attachments = listOf(KdbxAttachmentRef("a", 0), KdbxAttachmentRef("b", 1), KdbxAttachmentRef("c", 9), KdbxAttachmentRef("d", null))),
            sizes = listOf(14, 0),
        )

        assertEquals(listOf(Attachment("a", 14), Attachment("b", 0), Attachment("c", null), Attachment("d", null)), e.attachments)
    }

    @Test
    fun theOneTimeCodeComesFromTheResolver() {
        val seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

        val viaUri = map(raw(listOf(protected("otp", "otpauth://totp/x?secret=$seed"))))
        val broken = map(raw(listOf(protected("otp", "garbage"), protected("TOTP Seed", seed))))

        assertEquals(Secret.of("12345678901234567890"), viaUri.totp?.secret)
        assertNull(broken.totp)
    }

    @Test
    fun aCustomIconOnlyWhenTheFileHasIt() {
        assertEquals("ICON", map(raw(icon = "ICON"), icons = setOf("ICON")).customIconId)
        assertNull(map(raw(icon = "GONE"), icons = setOf("ICON")).customIconId)
    }

    @Test
    fun entriesBelowTheRecycleBinAtAnyDepthAreSetApartInOrder() {
        fun entry(id: String, vararg ancestors: String) = KdbxEntry(id, emptyList(), ancestorGroupUuids = ancestors.toList())
        val document = KdbxDocument(
            KdbxMeta(recycleBinUuid = "BIN", customIcons = mapOf("I" to CustomIcon(byteArrayOf(1)))),
            groups = emptyList(),
            entries = listOf(entry("a", "R"), entry("b", "R", "BIN"), entry("c", "R", "BIN", "sub"), entry("d", "R", "X")),
        )

        val vault = VaultAssembler.assemble(document, emptyList())

        assertEquals(listOf("a", "d"), vault.entries.map { it.id })
        assertEquals(listOf("b", "c"), vault.recycleBin.map { it.id })
        assertEquals(setOf("I"), vault.customIcons.keys)
    }

    @Test
    fun theRecycleBinsGroupIsKeptWithItsPathWhereverItIs() {
        val groups = listOf(
            KdbxGroup("R", "Root", emptyList(), listOf("R")),
            KdbxGroup("A", "Archive", listOf("Archive"), listOf("R", "A")),
            KdbxGroup("BIN", "Recycle Bin", listOf("Archive", "Recycle Bin"), listOf("R", "A", "BIN")),
        )
        val vault = VaultAssembler.assemble(KdbxDocument(KdbxMeta(recycleBinUuid = "BIN"), groups, emptyList()), emptyList())

        assertEquals(VaultGroup("BIN", "Recycle Bin", listOf("Archive", "Recycle Bin")), vault.recycleBinGroup)
        assertNull(VaultAssembler.assemble(KdbxDocument(KdbxMeta(), groups, emptyList()), emptyList()).recycleBinGroup)
    }

    @Test
    fun withoutARecycleBinNothingIsSetApart() {
        val document = KdbxDocument(KdbxMeta(), emptyList(), listOf(KdbxEntry("a", emptyList(), ancestorGroupUuids = listOf(""))))

        assertEquals(1, VaultAssembler.assemble(document, emptyList()).entries.size)
    }

    @Test
    fun fieldTypesComeFromTheEntrysCustomDataAnythingButJsonIsNone() {
        fun types(data: Map<String, String>) = map(KdbxEntry("id", emptyList(), customData = data)).fieldTypes
        val key = EntryFieldTypes.CUSTOM_DATA_KEY

        assertEquals(mapOf("Ablauf" to "until"), types(mapOf("Other" to "x", key to """{"Ablauf":"until"}""")))
        assertEquals(emptyMap(), types(mapOf(key to "not json")))
        assertEquals(emptyMap(), types(emptyMap()))
    }
}
