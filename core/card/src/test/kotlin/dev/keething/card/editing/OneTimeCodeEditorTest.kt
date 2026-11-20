package dev.keething.card.editing

import dev.keething.domain.text.english
import dev.keething.card.EntryCardBuilder
import dev.keething.card.FieldRow
import dev.keething.card.now
import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.TotpChange
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One-time codes in the editor (keething-spec edit.one-time-code; the Mac `OneTimeCodeTests` "one-time codes in the editor"). */
class OneTimeCodeEditorTest {
    private val key = "12345678901234567890".encodeToByteArray() // RFC 6238's test key
    private val base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    private fun config(digits: Int = 6, period: Int = 30, algorithm: TotpConfig.Algorithm = TotpConfig.Algorithm.SHA1) =
        TotpConfig(Secret.adopt(key.copyOf()), digits, period, algorithm)

    private fun added(result: AddedCode): AddedCode.Added = assertIs<AddedCode.Added>(result)

    // spec: edit.one-time-code/AC5
    @Test
    fun theFirstCodeIsTheEntrysOwnAndMoreGoIntoProtectedFields() {
        val editor = EntryEditor.edit(Entry("e", title = "Shop", username = "me"), now)
        var draft = editor.start
        assertNull(draft.code)
        assertTrue(assertIs<AddedCode.Refused>(editor.addOneTimeCode(draft, "nope!")).problem.english().contains("Base32"))

        val own = added(editor.addOneTimeCode(draft, base32))
        assertNull(own.field)
        draft = own.draft
        assertEquals(CodeDraft(base32), draft.code)
        assertEquals(
            TotpChange.Set(config(), "otpauth://totp/Shop:me?secret=$base32&period=30&digits=6&issuer=Shop"),
            editor.changes(draft).totp,
        )
        assertTrue(editor.canSave(draft))

        val another = added(editor.addOneTimeCode(draft, "otpauth://totp/X?secret=$base32&digits=7"))
        assertEquals("One-time code 2", another.field)
        draft = another.draft
        assertEquals(listOf("One-time code 2"), draft.fields.map { it.name })
        assertTrue(draft.fields[0].isProtected)
        assertEquals(config(digits = 7), editor.oneTimeCode(draft.fields[0]))
        assertEquals("otpauth://totp/Shop:me?secret=$base32&period=30&digits=7&issuer=Shop", draft.fields[0].value)
        assertEquals(CodeDraft(base32), draft.code, "the entry's own stays")
        assertEquals(null, editor.fieldProblem(draft, draft.fields[0])?.english())

        // A name taken is skipped: 2 renamed by the user, the next is 3 only when 2 is still there.
        draft = added(editor.addOneTimeCode(draft, base32)).draft
        assertEquals(listOf("One-time code 2", "One-time code 3"), draft.fields.map { it.name })
        draft = draft.removeField(draft.fields[0].id)
        assertEquals("One-time code 2", added(editor.addOneTimeCode(draft, base32)).field)
        assertEquals(
            listOf(CustomField("One-time code 3", FieldValue.Protected(Secret.of(draft.fields[0].value)))),
            editor.changes(draft).customFields,
        )
    }

    // spec: edit.one-time-code/AC6
    @Test
    fun theEntrysOwnCodeIsRemovedOrItsSettingsChangedBeforeSaving() {
        val editor = EntryEditor.edit(Entry("e", title = "Shop", totp = config()), now)
        var draft = editor.start
        assertEquals(CodeDraft(base32), draft.code)
        assertFalse(editor.codeChanged(draft) || editor.isDirty(draft))
        assertNull(editor.changes(draft).totp)

        draft = draft.copy(code = null)
        assertTrue(editor.codeChanged(draft) && editor.canSave(draft))
        assertEquals(TotpChange.Remove, editor.changes(draft).totp)
        draft = draft.copy(code = editor.start.code)
        assertFalse(editor.codeChanged(draft) || editor.isDirty(draft), "back to the code the entry had")

        draft = draft.copy(code = draft.code!!.copy(digits = 8, period = 60, algorithm = TotpConfig.Algorithm.SHA512))
        val set = assertIs<TotpChange.Set>(editor.changes(draft).totp)
        assertEquals(config(8, 60, TotpConfig.Algorithm.SHA512), set.config)
        assertTrue(set.uri.endsWith("&period=60&digits=8&issuer=Shop&algorithm=SHA512"))

        // Removed and the same code added again: nothing to save.
        val readded = added(editor.addOneTimeCode(editor.start.copy(code = null), " ${base32.lowercase()} ")).draft
        assertFalse(editor.isDirty(readded))
    }

    private fun protectedField(name: String, value: String) = CustomField(name, FieldValue.Protected(Secret.of(value)))

    private fun texts(change: TotpChange?): List<Triple<String, String, Boolean>> =
        assertIs<TotpChange.Fields>(change).fields.map { Triple(it.name, it.text(), it.isProtected) }

    @Test
    fun aCodeThatCannotBeUsedIsEditedAsTheTextOfItsFieldsAndNeverOverwritten() {
        val steam = "otpauth://totp/Steam:alex?secret=$base32&encoder=steam"
        val entry = Entry("e", title = "Steam", hasCodeFields = true, codeFields = listOf(protectedField("otp", steam)))
        val editor = EntryEditor.edit(entry, now)
        val start = editor.start
        assertNull(start.code)
        assertEquals(listOf(CodeFieldDraft("otp", steam, isProtected = true)), start.codeFields)
        assertEquals("This code cannot be used here: Steam codes.", editor.codeFieldsProblem(start)?.english())
        assertNull(editor.codeFieldsCode(start))
        assertNull(editor.changes(start).totp, "an ordinary edit keeps the fields")
        assertTrue(editor.offersAnotherCode(start))

        // Edited until it reads: its live code instead of the reason, saved as typed, its protection kept.
        val fixed = "otpauth://totp/Steam:alex?secret=$base32"
        val reads = start.withCodeField("otp", fixed)
        assertNull(editor.codeFieldsProblem(reads))
        assertEquals(config(), editor.codeFieldsCode(reads))
        assertEquals(listOf(Triple("otp", fixed, true)), texts(editor.changes(reads).totp))
        assertTrue(editor.canSave(reads))

        // Left broken, saved as typed all the same.
        val other = start.withCodeField("otp", "$steam&digits=5")
        assertEquals("This code cannot be used here: Steam codes.", editor.codeFieldsProblem(other)?.english())
        assertEquals(listOf(Triple("otp", "$steam&digits=5", true)), texts(editor.changes(other).totp))

        // Removed with the save; Undo puts the text back.
        val removed = start.copy(codeFields = null)
        assertEquals(TotpChange.Remove, editor.changes(removed).totp)
        assertNull(editor.changes(removed.copy(codeFields = start.codeFields)).totp)

        // A code added goes into a field of its own; the Steam code stays.
        val another = added(editor.addOneTimeCode(start, base32))
        assertEquals("One-time code 2", another.field)
        assertNull(another.draft.code)
        assertEquals(start.codeFields, another.draft.codeFields)
        assertNull(editor.changes(another.draft).totp)
    }

    @Test
    fun legacyFieldsAreEditedOneByOne() {
        val entry = Entry(
            "e", title = "Old", hasCodeFields = true,
            codeFields = listOf(protectedField("TOTP Seed", base32), CustomField("TOTP Settings", FieldValue.Plain("30;S"))),
        )
        val editor = EntryEditor.edit(entry, now)
        assertEquals("This code cannot be used here: Steam codes.", editor.codeFieldsProblem(editor.start)?.english())
        val reads = editor.start.withCodeField("TOTP Settings", "30;6")
        assertEquals(config(), editor.codeFieldsCode(reads))
        assertEquals(listOf(Triple("TOTP Seed", base32, true), Triple("TOTP Settings", "30;6", false)), texts(editor.changes(reads).totp))
    }

    @Test
    fun anEntryWithAWorkingCodeHasNoCodeFieldsToEdit() {
        val entry = Entry(
            "e", title = "T", totp = config(), hasCodeFields = true,
            codeFields = listOf(protectedField("otp", "otpauth://totp/T?secret=$base32")),
        )
        val editor = EntryEditor.edit(entry, now)
        assertNull(editor.start.codeFields)
        assertEquals(CodeDraft(base32), editor.start.code)
    }

    // A link is a code in the editor only as the editor opens it or as the code dialog adds it (the owner's rule, AC7).
    @Test
    fun aLinkTypedIntoAFieldStaysTextAndABrokenOneIsWarnedOfAndMasked() {
        val link = "otpauth://totp/Admin?secret=$base32"
        val entry = Entry("e", title = "T", customFields = listOf(CustomField("Hotp", FieldValue.Plain("otpauth://hotp/X?secret=$base32&counter=1"))))
        val editor = EntryEditor.edit(entry, now)
        val broken = editor.start.fields.single()
        assertNull(editor.oneTimeCode(broken))
        assertEquals("Not a one-time code: only time-based (TOTP) links are supported.", editor.fieldWarning(broken)?.english())
        assertTrue(editor.isMasked(broken), "masked as the card masks it, protected or not")

        // Typed (or pasted) into a field: text while the editor is open, no warning, masked.
        var draft = editor.start.addField()
        val typed = draft.fields.last()
        draft = draft.changeField(typed.id) { it.copy(name = "Typed", value = "otpauth://totp/Admin?secret=AB") }
        assertNull(editor.oneTimeCode(draft.fields.last()), "a short secret no longer makes it a code mid-typing")
        draft = draft.changeField(typed.id) { it.copy(value = link) }
        assertNull(editor.oneTimeCode(draft.fields.last()))
        assertNull(editor.fieldWarning(draft.fields.last()))
        assertTrue(editor.isMasked(draft.fields.last()))
        assertEquals(link, editor.changes(draft).customFields?.last()?.text())

        // Edited into a broken link: warned of, saved all the same.
        draft = draft.changeField(typed.id) { it.copy(value = "otpauth://totp/Admin") }
        assertEquals("Not a one-time code: the link has no secret.", editor.fieldWarning(draft.fields.last())?.english())
        assertTrue(editor.canSave(draft))
        assertFalse(editor.isMasked(draft.copy(fields = listOf(FieldDraft(99, "Plain", "hello", isProtected = false))).fields.single()))
    }

    @Test
    fun aFieldHoldingACodeLinkIsAnotherCodeNamedAndRemovedAsAnyField() {
        val link = "otpauth://totp/Admin?secret=$base32"
        val entry = Entry(
            "e", title = "T",
            customFields = listOf(
                CustomField("Admin code", FieldValue.Protected(Secret.of(link))),
                CustomField("Note", FieldValue.Plain("otpauth://broken")),
            ),
        )
        val editor = EntryEditor.edit(entry, now)
        val (code, broken) = editor.start.fields
        assertEquals(config(), editor.oneTimeCode(code))
        assertNull(editor.oneTimeCode(broken), "a link that does not parse stays a field to edit")

        val renamed = editor.start.changeField(code.id) { it.copy(name = "Admin") }
        assertEquals(listOf("Admin", "Note"), editor.changes(renamed).customFields?.map { it.name })
        assertEquals(listOf("Note"), editor.changes(editor.start.removeField(code.id)).customFields?.map { it.name })

        // The card shows it as a live code too.
        val card = EntryCardBuilder.build(Entry("e", title = "T", customFields = editor.changes(renamed).customFields!!), now)
        assertEquals(listOf(FieldRow.Kind.TOTP), card.rows.filter { it.label.english() == "Admin" }.map { it.kind })
    }

    // spec: edit.one-time-code/AC4
    @Test
    fun theFirstQrCodeThatIsACodeIsTaken() {
        val editor = EntryEditor.edit(Entry("e", title = "Shop", username = "me"), now)
        val own = added(editor.addOneTimeCode(editor.start, listOf("https://example.org", "otpauth://totp/X?secret=$base32&digits=7")))
        assertEquals(CodeDraft(base32, digits = 7), own.draft.code)
        assertEquals(EntryEditor.NO_QR_CODE, assertIs<AddedCode.Refused>(editor.addOneTimeCode(editor.start, emptyList())).problem)
        assertEquals("No QR code found in the image.", EntryEditor.NO_QR_CODE.english())
        val refused = assertIs<AddedCode.Refused>(editor.addOneTimeCode(editor.start, listOf("otpauth://hotp/X?secret=$base32&counter=1", "nope!")))
        assertTrue(refused.problem.english().startsWith("This code cannot be used here"), "the first one's problem")
    }

    @Test
    fun aNoteOffersNoCodeAndANewEntryKeepsOne() {
        val vault = Vault(entries = emptyList())
        val editor = EntryEditor.create(vault, groupId = null, now = now)
        val draft = added(editor.addOneTimeCode(editor.start.copy(title = "New"), base32)).draft
        assertIs<TotpChange.Set>(assertIs<VaultEdit.Create>(editor.edit(draft)).fields.totp)
        assertFalse(EntryEditor.createNote(vault, groupId = null, now = now, dates = null).offersFields)
    }
}

private fun CustomField.text(): String = when (val v = value) {
    is FieldValue.Plain -> v.text
    is FieldValue.Protected -> v.secret.reveal()
}
