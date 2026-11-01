package dev.keething.card.editing

import dev.keething.card.now
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.EntryTypeChange
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NoteDates
import dev.keething.domain.vault.QuickNote
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A quick note's editor (SPEC §6.4; keething-spec app.quick-note AC2, AC3; the Mac's `QuickNoteTests`, "The note and
 * its Inbox"): a new note with the text given, protected, in the top-level Inbox.
 */
class QuickNoteEditorTest {
    private val root = VaultGroup("R", "Root", emptyList())
    private val work = VaultGroup("W", "Work", listOf("Work"))
    private val deeperInbox = VaultGroup("WI", "Inbox", listOf("Work", "Inbox"))
    private val dates = NoteDates { "<${it.epochSecond}>" }

    private fun quickNote(vararg groups: VaultGroup) =
        EntryEditor.createQuickNote(Vault(entries = emptyList(), groups = groups.toList(), defaultUsername = "alex"), now, dates, id = "new")

    @Test
    fun aNewProtectedNoteWithTheTextItsGroupTheTopLevelInboxInAnyCase() {
        val inbox = VaultGroup("I", "inBOX", listOf("inBOX"))
        val editor = quickNote(root, work, deeperInbox, inbox)
        val draft = editor.start.copy(notes = "Dune\nArrival")
        assertEquals(EditorLayout.NOTE, editor.layout)
        assertTrue(editor.isNew && draft.notesProtected)
        assertEquals("", draft.username, "no default username for a note")
        assertEquals(GroupChoice("I"), draft.group, "the top-level one, not Work › Inbox")
        assertEquals(GroupDestination("I"), editor.destination(draft))
        assertTrue(editor.isDirty(draft), "the text is the user's: Back asks first")
        assertTrue(editor.canSave(draft))
        assertEquals(
            EntryChanges(title = "Note, <${now.epochSecond}>", notes = "Dune\nArrival", notesProtected = true, entryType = EntryTypeChange.Set(EntryType.NOTE)),
            editor.changes(draft),
        )
    }

    @Test
    fun noTopLevelInboxSoOneIsMadeAtTheTopLevelWithTheNoteADeeperOneIsNotIt() {
        val editor = quickNote(root, work, deeperInbox)
        val draft = editor.start.copy(notes = "Dune")
        assertEquals(GroupChoice("R", listOf(QuickNote.INBOX)), draft.group)
        val destination = editor.destination(draft)!!
        assertEquals("R", destination.groupId)
        assertEquals(listOf("Inbox"), destination.newGroups.map { it.name })
        assertEquals(destination.newGroups.single().id, destination.finalGroupId)
        assertEquals("Inbox", QuickNote.INBOX)
        assertEquals(listOf(GroupTree.Level("Inbox", isNew = true)), GroupTree(listOf(root, work, deeperInbox)).levels(draft.group), "the editor's \"new\" badge")
    }

    @Test
    fun theFirstTopLevelInboxOfTwoAndOneWithAccentsOrSpacesIsAnInboxToo() {
        val first = VaultGroup("I1", "INBOX", listOf("INBOX"))
        val second = VaultGroup("I2", "Inbox", listOf("Inbox"))
        assertEquals(GroupChoice("I1"), quickNote(root, work, first, second).start.group, "the first of the name, in the file's order")
        assertEquals(GroupChoice("I3"), quickNote(root, VaultGroup("I3", " Inbox ", listOf(" Inbox "))).start.group, "blanks around")
    }

    @Test
    fun theGroupCanBeChangedAndTheNoteGoesThere() {
        val editor = quickNote(root, work)
        val draft = editor.start.copy(notes = "Dune").choosing(GroupChoice("W"))
        assertEquals(GroupDestination("W"), editor.destination(draft))
        assertTrue(editor.canSave(draft))
    }

    @Test
    fun nothingTypedBeyondTheTextNoTitleNoWebsiteAndNothingOfTheTextInTheStart() {
        val editor = quickNote(root)
        assertEquals("", editor.start.notes, "the text goes into the draft: the start is what Back compares with")
        assertEquals("", editor.start.title)
        assertFalse(editor.showsWebsite(editor.start))
        assertFalse(editor.canSave(editor.start), "no text: nothing to save")
    }
}
