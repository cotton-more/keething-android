package dev.keething.card.editing

import dev.keething.domain.text.english
import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.TotpChange
import dev.keething.totp.TotpLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeEditsTest {
    private val own = TotpConfig(Secret.of("12345678901234567890"))
    private val link = "otpauth://totp/Microsoft:work?secret=JBSWY3DPEHPK3PXP&issuer=Microsoft"
    private val microsoft = Entry(
        id = "ms", title = "Microsoft", username = "alex@outlook.com", password = Secret.of("pw"), totp = own, hasCodeFields = true,
        codeFields = listOf(CustomField.protected("otp", "otpauth://totp/Microsoft:alex?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")),
        customFields = listOf(CustomField.plain("Recovery", "words"), CustomField.protected("Work account", link), CustomField.plain("Notes 2", "x")),
        fieldTypes = mapOf("Work account" to "text"),
    )

    private fun names(fields: List<CustomField>?) = fields?.map { it.name }

    private fun text(field: CustomField): String = when (val value = field.value) {
        is FieldValue.Plain -> value.text
        is FieldValue.Protected -> value.secret.reveal()
    }

    // spec: app.codes-view/AC4
    @Test
    fun editingTheOwnCodeSetsItAndNothingElse() {
        val changed = TotpConfig(Secret.of("abcdefghijabcdefghij"), digits = 8, period = 60, algorithm = TotpConfig.Algorithm.SHA256)
        val changes = CodeEdits.edit(microsoft, null, changed)

        val set = assertIs<TotpChange.Set>(changes.totp)
        assertEquals(changed, set.config)
        assertTrue(set.uri.startsWith("otpauth://totp/Microsoft:alex%40outlook.com?"), "written as the editor writes it")
        assertEquals(EntryChanges(), changes.copy(totp = null), "nothing but the code")
    }

    // spec: app.codes-view/AC4
    @Test
    fun editingAFurtherCodeReplacesItsLinkAndNameKeepingItsPlaceProtectionAndType() {
        val changed = TotpConfig(Secret.of("abcdefghijabcdefghij"), period = 60)
        val changes = CodeEdits.edit(microsoft, "Work account", changed, name = " Office ")

        assertEquals(listOf("Recovery", "Office", "Notes 2"), names(changes.customFields))
        val field = changes.customFields!![1]
        assertIs<FieldValue.Protected>(field.value)
        assertTrue(text(field).contains("period=60"))
        assertEquals(mapOf("Office" to "text"), changes.fieldTypes, "the type follows the field")
        assertNull(changes.totp, "the own code is untouched")
        assertEquals("words", text(changes.customFields!![0]))
        assertNull(CodeEdits.edit(microsoft, "Work account", changed).fieldTypes, "not renamed: types untouched")
    }

    @Test
    fun aFurtherCodesNameIsCheckedAsAnyFieldsName() {
        assertNull(CodeEdits.nameProblem(microsoft, "Work account", "Work account")?.english())
        assertNull(CodeEdits.nameProblem(microsoft, "Work account", "Office")?.english())
        assertEquals("Another field has this name.", CodeEdits.nameProblem(microsoft, "Work account", "Recovery")?.english())
        assertEquals("“otp” is a name KeePass uses itself.", CodeEdits.nameProblem(microsoft, "Work account", "otp")?.english())
        assertEquals("Give the code a name: KeePass cannot keep a value without one.", CodeEdits.nameProblem(microsoft, "Work account", " ")?.english())
    }

    // spec: app.codes-view/AC5
    @Test
    fun deletingACodeRemovesOnlyItsFieldsAndUndoPutsThemBack() {
        assertEquals(TotpChange.Remove, CodeEdits.delete(microsoft, null).totp)
        assertNull(CodeEdits.delete(microsoft, null).customFields, "no further code takes the own code's place")

        val further = CodeEdits.delete(microsoft, "Work account")
        assertEquals(listOf("Recovery", "Notes 2"), names(further.customFields))
        assertEquals(emptyMap(), further.fieldTypes)
        assertNull(further.totp)

        val ownBack = assertIs<TotpChange.Fields>(CodeEdits.restore(microsoft, microsoft.copy(totp = null, codeFields = emptyList()), null).totp)
        assertEquals(microsoft.codeFields, ownBack.fields)
        // Meanwhile another field was added: the code goes back where it stood, the new field stays.
        val current = microsoft.copy(customFields = listOf(CustomField.plain("Recovery", "words"), CustomField.plain("Notes 2", "x"), CustomField.plain("New", "n")), fieldTypes = emptyMap())
        val back = CodeEdits.restore(microsoft, current, "Work account")
        assertEquals(listOf("Recovery", "Work account", "Notes 2", "New"), names(back.customFields))
        assertEquals(link, text(back.customFields!![1]))
        assertEquals(mapOf("Work account" to "text"), back.fieldTypes)
    }

    @Test
    fun aChangeCarriesSecretsOfItsOwnSoWipingItLeavesTheEntryAlone() {
        CodeEdits.delete(microsoft, "Recovery").wipe()
        CodeEdits.restore(microsoft, microsoft, null).wipe()
        CodeEdits.edit(microsoft, "Recovery", own).wipe()
        assertEquals(link, text(microsoft.customFields[1]))
        assertTrue(text(microsoft.codeFields.single()).startsWith("otpauth://"))
        assertEquals("12345678901234567890", own.secret.reveal())
    }

    // spec: app.codes-view/AC6
    @Test
    fun aCodeIsAllTheEntryHoldsOnlyWithNothingButTheTitleBesideIt() {
        val bare = Entry(id = "gh", title = "GitHub", totp = own, hasCodeFields = true)
        assertTrue(CodeEdits.isAllItHolds(bare, null))
        assertFalse(CodeEdits.isAllItHolds(bare.copy(username = "alex"), null))
        assertFalse(CodeEdits.isAllItHolds(bare.copy(password = Secret.of("x")), null))
        assertFalse(CodeEdits.isAllItHolds(bare.copy(url = "https://github.com"), null))
        assertFalse(CodeEdits.isAllItHolds(bare.copy(notes = "n"), null))
        assertFalse(CodeEdits.isAllItHolds(bare.copy(attachments = listOf(Attachment("a.txt", 1))), null))
        assertFalse(CodeEdits.isAllItHolds(bare.copy(customFields = listOf(CustomField.plain("One-time code 2", link))), null), "a further code besides")

        val onlyFurther = Entry(id = "hz", title = "Hetzner", customFields = listOf(CustomField.plain("Robot", link)))
        assertTrue(CodeEdits.isAllItHolds(onlyFurther, "Robot"))
        assertFalse(CodeEdits.isAllItHolds(onlyFurther.copy(totp = own, hasCodeFields = true), "Robot"), "the own code besides")
        assertFalse(CodeEdits.isAllItHolds(microsoft, null))
    }

    // spec: app.codes-view/AC7
    @Test
    fun aReadCodeSaysWhomItIsFor() {
        val read = assertIs<ReadCode.Code>(CodeEdits.read("otpauth://totp/Dropbox:alex%40posteo.de?secret=JBSWY3DPEHPK3PXP&digits=8&issuer=Dropbox"))
        assertEquals(TotpLabel("Dropbox", "alex@posteo.de"), read.label)
        assertEquals(8, read.config.digits)
        assertEquals(TotpLabel("", ""), assertIs<ReadCode.Code>(CodeEdits.read("JBSW Y3DP EHPK 3PXP")).label)
        assertIs<ReadCode.Refused>(CodeEdits.read("hello"))
        assertEquals("No QR code found in the image.", assertIs<ReadCode.Refused>(CodeEdits.read(emptyList())).problem?.english())
        assertEquals(TotpLabel("GitHub", "alex"), assertIs<ReadCode.Code>(CodeEdits.read(listOf("https://example.com", "otpauth://totp/GitHub:alex?secret=JBSWY3DPEHPK3PXP"))).label)
    }

    // spec: app.codes-view/AC7
    @Test
    fun anEntryOfThatTitleTakesTheCodeAsItsOwnOrTheNextFurtherOne() {
        val entries = listOf(Entry(id = "a", title = "Github"), Entry(id = "d", title = "Dröpbox", totp = own, customFields = listOf(CustomField.plain("One-time code 2", link))))
        assertEquals("a", CodeEdits.sameTitle(entries, " GitHub ")?.id, "case folded")
        assertEquals("d", CodeEdits.sameTitle(entries, "Dropbox")?.id, "diacritics folded")
        assertNull(CodeEdits.sameTitle(entries, "Git"))
        assertNull(CodeEdits.sameTitle(entries, ""))

        val code = TotpConfig(Secret.of("abcdefghijabcdefghij"))
        val (first, firstField) = CodeEdits.addTo(entries[0], code)
        assertNull(firstField)
        assertIs<TotpChange.Set>(first.totp)
        val (next, nextField) = CodeEdits.addTo(entries[1], code)
        assertEquals("One-time code 3", nextField)
        assertEquals(listOf("One-time code 2", "One-time code 3"), names(next.customFields))
        assertIs<FieldValue.Protected>(next.customFields!!.last().value)
        assertNull(next.totp)

        val created = CodeEdits.newEntry(" Dropbox ", "alex@posteo.de", code)
        assertEquals("Dropbox", created.title)
        assertEquals("alex@posteo.de", created.username)
        assertEquals(code, assertIs<TotpChange.Set>(created.totp).config)
    }
}
