package dev.keepassbar.card.editing

import dev.keepassbar.card.CardSection
import dev.keepassbar.card.EntryCardBuilder
import dev.keepassbar.card.field
import dev.keepassbar.card.now
import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.EntryChanges
import dev.keepassbar.domain.vault.Vault
import dev.keepassbar.domain.vault.VaultEdit
import dev.keepassbar.domain.vault.VaultGroup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor's rules (SPEC §6.2; the Mac `EditingTests`, `OneTimeCodeTests` expectations for `EntryEditorModel`). */
class EntryEditorTest {
    private val entry = Entry(
        "e", title = "Mail", username = "me", password = Secret.of("old"), url = "https://mail.example", notes = "n",
        groupPath = listOf("Work"), tags = listOf("a", "b"),
        customFields = listOf(field("PIN", "1", protected = true), field("Note", "x")),
        lastModified = Instant.ofEpochSecond(100),
    )

    private fun EntryDraft.rename(index: Int, name: String) = changeField(fields[index].id) { it.copy(name = name) }

    private fun EntryDraft.value(index: Int, value: String) = changeField(fields[index].id) { it.copy(value = value) }

    @Test
    fun onlyWhatChangedIsSaved() {
        val editor = EntryEditor.edit(entry, now)
        var draft = editor.start
        assertEquals("old", draft.password, "the password is there to edit")
        assertEquals("a, b", draft.tags)
        assertFalse(editor.isDirty(draft) || editor.canSave(draft))

        draft = draft.copy(password = "new").rename(1, "Comment").addField()
        draft = draft.changeField(draft.fields[2].id) { it.copy(name = "Code", value = "42", isProtected = true) }
        draft = draft.copy(tags = "b, c")
        assertEquals(
            EntryChanges(
                password = Secret.of("new"),
                customFields = listOf(field("PIN", "1", protected = true), field("Comment", "x"), field("Code", "42", protected = true)),
                tags = listOf("b", "c"),
            ),
            editor.changes(draft),
        )
        draft = draft.copy(expires = true)
        assertEquals(true, editor.changes(draft).expiry?.expires)
        assertTrue(editor.changes(draft).expiry?.time != null)
        assertTrue(editor.canSave(draft))
        assertEquals(VaultEdit.Update("e", Instant.ofEpochSecond(100), editor.changes(draft)), editor.edit(draft))
    }

    @Test
    fun problemsKeepItFromSaving() {
        val editor = EntryEditor.edit(entry, now)
        var draft = editor.start.addField().rename(2, " ").value(2, "v")
        assertEquals("Give the field a name: KeePass cannot keep a value without one.", editor.fieldProblem(draft, draft.fields[2]))
        assertTrue(editor.problems(draft).isEmpty() && editor.hasFieldProblems(draft) && !editor.canSave(draft), "said at the field")
        draft = draft.rename(2, "PIN")
        assertEquals(listOf("Another field has this name.", null, "Another field has this name."), draft.fields.map { editor.fieldProblem(draft, it) })
        draft = draft.rename(2, "otp")
        assertEquals("“otp” is a name KeePass uses itself.", editor.fieldProblem(draft, draft.fields[2]))
        draft = draft.rename(2, "Password")
        assertEquals("“Password” is a name KeePass uses itself.", editor.fieldProblem(draft, draft.fields[2]))
        draft = draft.rename(2, "Fine")
        assertTrue(!editor.hasFieldProblems(draft) && editor.canSave(draft))
    }

    @Test
    fun aNewFieldStartsUnnamedAndBlankOnesAreLeftOut() {
        val editor = EntryEditor.edit(entry, now)
        var draft = editor.start.addField().addField()
        assertEquals("", draft.fields.last().name)
        assertEquals(draft.fields.map { it.id }.distinct().size, 4, "each field its own id")
        assertFalse(editor.hasFieldProblems(draft), "two unnamed, empty fields are no duplicates and no problem")
        assertTrue(editor.changes(draft).isEmpty && !editor.canSave(draft), "nor a change: they are not saved")
        draft = draft.value(3, "secret")
        assertEquals(listOf(false, false, false, true), draft.fields.map { editor.fieldProblem(draft, it) != null })
        draft = draft.rename(3, "Code")
        assertFalse(editor.hasFieldProblems(draft))
        assertEquals(listOf("PIN", "Note", "Code"), editor.changes(draft).customFields?.map { it.name }, "the blank one is left out")
        draft = draft.removeField(draft.fields[3].id)
        assertFalse(editor.isDirty(draft))
    }

    @Test
    fun namesAreSavedTrimmedButTidyingAloneIsNoChange() {
        val editor = EntryEditor.edit(entry.copy(customFields = listOf(field(" PIN ", "1"))), now)
        assertTrue(editor.changes(editor.start).customFields != null, "saved with the next change")
        assertFalse(editor.isDirty(editor.start) || editor.canSave(editor.start))
        val draft = editor.start.copy(title = "Post")
        assertEquals(listOf(field("PIN", "1")), editor.changes(draft).customFields)
    }

    @Test
    fun tagsAreSeparatedByCommasOrSemicolonsOnceEachInAnyCase() {
        val editor = EntryEditor.edit(entry, now)
        assertEquals(listOf("a", "b", "Ü"), editor.changes(editor.start.copy(tags = " a;b ,, A, Ü ;ü,")).tags)
        assertNull(editor.changes(editor.start.copy(tags = "a;b")).tags, "the same tags written differently")
        assertEquals(emptyList(), editor.changes(editor.start.copy(tags = " , ")).tags)
    }

    @Test
    fun theExpiryKeepsItsTimeOfDayAndOnlyARealChangeCounts() {
        val zone = ZoneId.of("Europe/Berlin")
        val expiring = entry.copy(expires = true, expiryTime = Instant.parse("2027-03-01T17:30:00Z"))
        val editor = EntryEditor.edit(expiring, now)
        assertEquals(Instant.parse("2027-03-01T17:30:00Z"), editor.start.expiryTime)
        var draft = editor.start.expiringOn(LocalDate.of(2027, 6, 15), zone)
        assertEquals(Instant.parse("2027-06-15T16:30:00Z"), draft.expiryTime, "18:30 in Berlin, summer time now")
        assertEquals(EntryChanges.Expiry(true, draft.expiryTime), editor.changes(draft).expiry)
        draft = editor.start.copy(expiryTime = editor.start.expiryTime.plusMillis(400))
        assertNull(editor.changes(draft).expiry, "less than a second: the file keeps seconds")
        assertEquals(EntryChanges.Expiry(false, null), editor.changes(editor.start.copy(expires = false)).expiry)

        val never = EntryEditor.edit(entry, now)
        assertEquals(now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant(), never.start.expiryTime, "a year from now to start with")
        assertNull(never.changes(never.start.copy(expiryTime = now)).expiry, "a date without expiring is no change")
    }

    @Test
    fun aNewEntryStartsWithTheDefaultUsernameAndNeedsATitleOrAddress() {
        val groups = listOf(VaultGroup("root", "Root", emptyList()), VaultGroup("web", "Web", listOf("Web")))
        val vault = Vault(entries = emptyList(), groups = groups, defaultUsername = "alex")
        val editor = EntryEditor.create(vault, groupId = null, now = now, id = "new")
        assertTrue(editor.isNew)
        assertEquals("root", editor.start.groupId, "the root group when none is given")
        assertEquals("alex", editor.start.username)
        assertFalse(editor.canSave(editor.start), "a new entry needs a title or a website")
        assertFalse(editor.isDirty(editor.start))
        val draft = editor.start.copy(url = "https://shop.example", password = "pw", groupId = "web")
        assertTrue(editor.canSave(draft))
        assertEquals(
            VaultEdit.Create("new", "web", EntryChanges(username = "alex", password = Secret.of("pw"), url = "https://shop.example")),
            editor.edit(draft),
        )
        assertEquals("web", EntryEditor.create(vault, groupId = "web", now = now).start.groupId)
        assertEquals("root", EntryEditor.create(vault, groupId = "gone", now = now).start.groupId)

        val lost = draft.copy(groupId = "gone")
        assertEquals(listOf("Choose a group."), editor.problems(lost))
        assertFalse(editor.canSave(lost))
    }

    @Test
    fun aBlockOfCodesBecomesNumberedProtectedFieldsThatTheCardListsAsCodes() {
        assertEquals(listOf("1234 5678", "abcd-efgh", "zz99", "04711"), EntryEditor.codes("1. 1234 5678\n2) abcd-efgh\n\n• zz99\n  04711  \n"))
        assertEquals(listOf("a1", "b2", "c3"), EntryEditor.codes("a1, b2; c3"), "one line split at commas and semicolons")

        val editor = EntryEditor.edit(Entry("e", title = "Shop"), now)
        var draft = assertIs<AddedCodes.Added>(editor.addCodes(editor.start, "1. 1234 5678\n2) abcd-efgh\n\n• zz99\n  04711  \n", " Backup codes ")).draft
        assertEquals(listOf("Backup codes_1", "Backup codes_2", "Backup codes_3", "Backup codes_4"), draft.fields.map { it.name })
        assertEquals(listOf("1234 5678", "abcd-efgh", "zz99", "04711"), draft.fields.map { it.value })
        assertTrue(draft.fields.all { it.isProtected })
        draft = assertIs<AddedCodes.Added>(editor.addCodes(draft, "a1\nb2", "Backup codes")).draft
        assertEquals(listOf("Backup codes_5", "Backup codes_6"), draft.fields.takeLast(2).map { it.name }, "the list's numbers go on")

        assertEquals(AddedCodes.Refused("No codes: paste or type them, one per line."), editor.addCodes(draft, "  \n ", "Backup codes"))
        assertEquals(AddedCodes.Refused("The codes need a name for their list."), editor.addCodes(draft, "x", " "))
        assertTrue(assertIs<AddedCodes.Refused>(editor.addCodes(draft, "x", "a_b")).problem.contains("“_”"))
        assertTrue(assertIs<AddedCodes.Refused>(editor.addCodes(draft, (1..51).joinToString("\n"), "B")).problem.contains("at most 50"))

        val fields = editor.changes(draft).customFields.orEmpty().take(4)
        val card = EntryCardBuilder.build(Entry("e", title = "Shop", customFields = fields), now)
        val section = card.sections.single { it.title == "Backup codes" }
        assertEquals(CardSection.Kind.Codes, section.kind)
        assertEquals(listOf("1", "2", "3", "4"), section.rows.map { it.label })
    }

    @Test
    fun theEditorFoldsAListOfCodesAsTheCardShowsIt() {
        val fields = listOf(field("Note", "x")) + (1..3).map { field("Recovery_$it", "c$it", protected = true) } +
            listOf(field("Two_1", "a"), field("Two_2", "b"), field("Recovery_4", "c4", protected = true))
        val editor = EntryEditor.edit(Entry("e", customFields = fields), now)
        val draft = editor.start
        val lists = editor.codeLists(draft)
        assertEquals(1, lists.size, "two fields are no list of codes")
        assertEquals("Recovery", lists[0].title)
        assertEquals(listOf(1, 2, 3, 6).map { draft.fields[it].id }, lists[0].fieldIds)
        assertTrue(lists[0].allProtected)
    }

    @Test
    fun theChangesSecretsAreTheEditorsOwnToWipe() {
        val editor = EntryEditor.edit(entry, now)
        val changes = editor.changes(editor.start.copy(password = "new").value(0, "2"))
        changes.wipe()
        assertTrue(changes.password!!.isWiped)
        assertTrue((changes.customFields!![0].value as dev.keepassbar.domain.vault.FieldValue.Protected).secret.isWiped)
        assertEquals(Secret.of("old"), entry.password, "the entry's own are not touched")
        assertEquals(CustomField.protected("PIN", "1"), entry.customFields[0])
    }
}
