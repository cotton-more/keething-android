package dev.keething.card.editing

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
        assertTrue(assertIs<AddedCode.Refused>(editor.addOneTimeCode(draft, "nope!")).problem.contains("Base32"))

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
        assertEquals(null, editor.fieldProblem(draft, draft.fields[0]))

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
        assertEquals(listOf(FieldRow.Kind.TOTP), card.rows.filter { it.label == "Admin" }.map { it.kind })
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
