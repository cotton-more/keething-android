package dev.keething.vault.opening

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.CustomIcon
import dev.keething.domain.vault.EntryFieldTypes
import dev.keething.domain.vault.FieldValue
import dev.keething.kdbx.xml.KdbxAttachmentRef
import dev.keething.kdbx.xml.KdbxDocument
import dev.keething.kdbx.xml.KdbxEntry
import dev.keething.kdbx.xml.KdbxField
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
            "otp", "TOTP Seed", "TOTP Settings", "TimeOtp-Secret-Base32", "TimeOtp-Length",
            "TimeOtp-Period", "TimeOtp-Algorithm",
        ).map { protected(it, "x") }

        val e = map(raw(listOf(protected("Z", "1"), plain("Title", "t")) + reserved + plain("A", "")))

        assertEquals(listOf(CustomField.protected("Z", "1"), CustomField.plain("A", "")), e.customFields)
        assertTrue(reserved.all { (it.value as FieldValue.Protected).secret.isWiped }, "the code's fields are wiped once read")
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
    fun withoutARecycleBinNothingIsSetApart() {
        val document = KdbxDocument(KdbxMeta(), emptyList(), listOf(KdbxEntry("a", emptyList(), ancestorGroupUuids = listOf(""))))

        assertEquals(1, VaultAssembler.assemble(document, emptyList()).entries.size)
    }

    @Test
    fun fieldTypesComeFromTheEntrysCustomDataTheNewNameFirstAnythingButJsonIsNone() {
        fun types(data: Map<String, String>) = map(KdbxEntry("id", emptyList(), customData = data)).fieldTypes
        val key = EntryFieldTypes.CUSTOM_DATA_KEY
        val former = EntryFieldTypes.FORMER_CUSTOM_DATA_KEY

        assertEquals(mapOf("Ablauf" to "until"), types(mapOf("Other" to "x", key to """{"Ablauf":"until"}""")))
        assertEquals(mapOf("A" to "until"), types(mapOf(former to """{"A":"until"}""")), "the former name while there is no new item")
        assertEquals(mapOf("A" to "date"), types(mapOf(former to """{"A":"until"}""", key to """{"A":"date"}""")), "the new item wins")
        assertEquals(emptyMap(), types(mapOf(key to "not json", former to """{"A":"until"}""")))
        assertEquals(emptyMap(), types(emptyMap()))
    }
}
