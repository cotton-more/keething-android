package dev.keething.card.editing

import dev.keething.domain.text.english
import dev.keething.card.field
import dev.keething.card.now
import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.EntryTypeChange
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NoteDates
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A note's editor (SPEC §6.4; keething-spec types.note AC4, AC5, app.quick-note AC6, edit.new-entry AC3; the Mac's
 * `NoteEditorTests`).
 */
class NoteEditorTest {
    private val groups = listOf(VaultGroup("root", "Root", emptyList()), VaultGroup("work", "Work", listOf("Work")))
    private val vault = Vault(entries = emptyList(), groups = groups, defaultUsername = "alex")

    /** The app's formatter stands in: what matters here is which time it is given. */
    private val dates = NoteDates { "<${it.epochSecond}>" }

    private fun dated(at: Instant) = "Note, <${at.epochSecond}>"

    private fun newNote(groupId: String? = "work", at: Instant = now) = EntryEditor.createNote(vault, groupId, at, dates, id = "new")

    private fun editing(entry: Entry) = EntryEditor.edit(entry, now, dates)

    @Test
    fun aNewNoteStartsProtectedWithNoUsernamePasswordOrWebsiteAndNeedsItsText() {
        val editor = newNote()
        var draft = editor.start
        assertEquals(EditorLayout.NOTE, editor.layout)
        assertTrue(editor.isNew && draft.notesProtected)
        assertEquals("", draft.username, "no default username for a note")
        assertEquals("work", draft.groupId, "the group of a new entry (edit.new-entry AC2), not an Inbox")
        assertFalse(editor.showsUsername || editor.showsPassword || editor.showsWebsite(draft) || editor.showsCustomFields)
        assertFalse(editor.offersFields, "no custom fields, codes or kinds offered: a login is an entry")
        assertFalse(editor.isDirty(draft) || editor.canSave(draft), "nothing typed: nothing to save, Back closes at once")

        draft = draft.copy(notes = "  \n ")
        assertFalse(editor.canSave(draft), "a blank text is no note")
        draft = draft.copy(title = "A title alone")
        assertFalse(editor.canSave(draft), "a new note needs its text, not a title (edit.new-entry AC3)")
        draft = draft.copy(title = "", notes = "Dune\nArrival")
        assertTrue(editor.canSave(draft), "a text and no title")
        assertEquals(dated(now), editor.savedTitle(draft), "a protected text gives nothing of itself to the title, its date instead")
        assertEquals("Title: ${dated(now)}", editor.titlePlaceholder(draft).english())
        val changes = EntryChanges(title = dated(now), notes = "Dune\nArrival", notesProtected = true, entryType = EntryTypeChange.Set(EntryType.NOTE))
        assertEquals(changes, editor.changes(draft))
        assertEquals(VaultEdit.Create("new", GroupDestination("work"), changes), editor.edit(draft))
    }

    @Test
    fun anEmptyTitleBecomesTheFirstLineATypedOneIsKeptAndProtectionOffIsWritten() {
        val editor = newNote(groupId = null)
        assertEquals("root", editor.start.groupId)
        var draft = editor.protecting(editor.start, on = false)
        assertEquals("Title: the first line", editor.titlePlaceholder(draft).english(), "no text yet")
        draft = draft.copy(notes = "\n  Films to watch  \nDune")
        assertEquals("Title: Films to watch", editor.titlePlaceholder(draft).english(), "the line it will be")
        assertEquals("Films to watch", editor.savedTitle(draft))
        assertEquals(
            EntryChanges(title = "Films to watch", notes = "\n  Films to watch  \nDune", notesProtected = false, entryType = EntryTypeChange.Set(EntryType.NOTE)),
            editor.changes(draft),
            "off is written even where Meta/MemoryProtection would protect a new value: the user's choice is kept",
        )
        draft = draft.copy(title = "   ")
        assertEquals("Films to watch", editor.savedTitle(draft), "spaces are no title")
        draft = draft.copy(title = "Mine")
        assertEquals("Mine", editor.changes(draft).title, "a title the user typed is never replaced")
    }

    @Test
    fun anEntryReadAsANoteOpensAsOneOpeningAndClosingWritesNothingAnEditMarksIt() {
        val imported = Entry("i", title = "Films", notes = "Dune", groupId = "root", lastModified = Instant.ofEpochSecond(100))
        val editor = editing(imported)
        assertEquals(EditorLayout.NOTE, editor.layout)
        assertFalse(editor.start.notesProtected, "as it is in the file")
        assertFalse(editor.isDirty(editor.start) || editor.canSave(editor.start), "the mark alone is no change: opening and closing writes nothing")
        val draft = editor.start.copy(notes = "Dune\nArrival")
        assertEquals(EntryChanges(notes = "Dune\nArrival", entryType = EntryTypeChange.Set(EntryType.NOTE)), editor.changes(draft), "edited as a note, it is marked")

        val marked = editing(Entry("m", title = "Films", notes = "Dune", notesProtected = true, groupId = "root", entryType = " Note "))
        assertEquals(EditorLayout.NOTE, marked.layout)
        assertTrue(marked.start.notesProtected)
        var again = marked.start.copy(notes = "Dune 2")
        assertEquals(EntryChanges(notes = "Dune 2"), marked.changes(again), "a stored kind is kept as stored, protection as it was")
        again = marked.protecting(again, on = false)
        assertEquals(EntryChanges(notes = "Dune 2", notesProtected = false), marked.changes(again), "switched off is kept")

        val unknown = editing(Entry("u", title = "List", notes = "milk", groupId = "root", entryType = "checklist"))
        assertEquals(EditorLayout.ENTRY, unknown.layout, "a kind this version does not know: an ordinary entry")
        assertEquals(EntryChanges(notes = "milk, eggs"), unknown.changes(unknown.start.copy(notes = "milk, eggs")), "nothing writes note over it (AC1)")

        val plain = editing(Entry("p", title = "Mail", username = "me", notes = "n", groupId = "root"))
        assertEquals(EditorLayout.ENTRY, plain.layout)
        assertEquals(EntryChanges(notes = "m"), plain.changes(plain.start.copy(notes = "m")), "an entry's editor sets neither the kind nor the protection")
        assertEquals("Title", plain.titlePlaceholder(plain.start).english())
        val protectedNotes = editing(Entry("r", title = "Router", password = Secret.of("pw"), notes = "admin", notesProtected = true, groupId = "root"))
        assertEquals(EditorLayout.ENTRY, protectedNotes.layout)
        assertEquals(EntryChanges(notes = "admin 2"), protectedNotes.changes(protectedNotes.start.copy(notes = "admin 2")), "an entry's protected Notes keep their own protection")
    }

    @Test
    fun protectSwitchedOnEmptiesATitleMadeFromTheTextsFirstLineATypedOneStays() {
        val made = Instant.ofEpochSecond(1_700_000_000)
        val saved = Entry("n", title = "Bank PIN 4711", notes = "Bank PIN 4711\nmore", groupId = "root", entryType = "note", created = made)
        val editor = editing(saved)
        var draft = editor.protecting(editor.start, on = true)
        assertEquals("", draft.title, "the first line would stay in the unprotected Title")
        assertEquals(dated(made), editor.savedTitle(draft), "named by when the note was made, not by when it was edited")
        assertEquals("Title: ${dated(made)}", editor.titlePlaceholder(draft).english())
        assertEquals(EntryChanges(title = dated(made), notesProtected = true), editor.changes(draft))

        draft = editor.protecting(editor.start.copy(notes = "Card PIN 1234"), on = true)
        assertEquals("", draft.title, "a title made from the text as it was saved goes too")

        val typed = editing(Entry("t", title = "Mine", notes = "Bank PIN 4711", groupId = "root", entryType = "note"))
        assertEquals("Mine", typed.protecting(typed.start, on = true).title, "a title of the user's own stays")
        val named = newNote()
        draft = named.protecting(named.start, on = false).copy(title = "Note")
        assertEquals("Note", named.protecting(draft, on = true).title, "no text has no first line: a title typed as Note stays")
        val long = editing(Entry("l", title = "x".repeat(60) + "…", notes = "x".repeat(70), groupId = "root", entryType = "note"))
        assertEquals("", long.protecting(long.start, on = true).title, "a title cut from a long first line is one made from it")

        val fresh = newNote(at = made.plusSeconds(60))
        assertEquals(dated(made.plusSeconds(60)), fresh.savedTitle(fresh.start.copy(notes = "x")), "a new note: when its editor opened")
        val off = editor.protecting(editor.start, on = false)
        assertEquals(editor.start, off, "switching off changes nothing else")
    }

    @Test
    fun addWebsiteBringsTheUrlRowInANoteWithOneShowsIt() {
        val editor = editing(Entry("n", title = "Films", notes = "Dune", groupId = "root", entryType = "note"))
        assertFalse(editor.showsWebsite(editor.start))
        var draft = editor.start.copy(websiteAdded = true)
        assertTrue(editor.showsWebsite(draft))
        assertFalse(editor.isDirty(draft), "an empty row is no change")
        draft = draft.copy(url = "https://films.example")
        assertEquals(EntryChanges(url = "https://films.example"), editor.changes(draft))
        val withSite = editing(Entry("s", title = "Shop", url = "https://shop.example", notes = "socks", entryType = "note"))
        assertTrue(withSite.showsWebsite(withSite.start))
        assertTrue(withSite.showsWebsite(withSite.start.copy(url = "")), "the row stays while its site is cleared")
    }

    @Test
    fun whatAnotherAppAddedShowsAndStaysAUsernameAPasswordFieldsACode() {
        val entry = Entry(
            "x", title = "Films", username = "me", password = Secret.of("pw"), notes = "Dune", notesProtected = true, groupId = "root",
            customFields = listOf(field("Seat", "12A")), entryType = "note", totp = TotpConfig(Secret.of("12345678901234567890")), hasCodeFields = true,
        )
        val editor = editing(entry)
        assertEquals(EditorLayout.NOTE, editor.layout)
        assertTrue(editor.showsUsername && editor.showsPassword && editor.showsCustomFields)
        assertFalse(editor.offersFields, "shown and editable, still nothing new offered")
        var draft = editor.start.copy(notes = "Dune\nArrival")
        assertEquals(EntryChanges(notes = "Dune\nArrival"), editor.changes(draft), "nothing the file holds is dropped")
        draft = draft.copy(password = "new")
        assertEquals(Secret.of("new"), editor.changes(draft).password, "editable as in an entry's editor")
        assertEquals(null, editor.changes(draft).totp, "the code is not touched: its fields stay as read")
        val blankUser = editing(Entry("y", title = "T", username = "  ", notes = "n", entryType = "note"))
        assertFalse(blankUser.showsUsername, "white space is no value")
    }

    @Test
    fun aPasswordOrUsernameGivenToANotesEditorShows() {
        // Autofill's save offer may update a note with a website: the password it brings is never written unseen.
        val editor = editing(Entry("w", title = "Shop", url = "https://shop.example", notes = "socks", entryType = "note"))
        assertFalse(editor.showsPassword || editor.showsUsername)
        val given = editor.given(editor.start.copy(password = "typed-in-chrome", username = "me"))
        assertTrue(given.showsPassword && given.showsUsername, "the rows stay while their values are cleared and typed again")
        assertEquals(EntryChanges(username = "me", password = Secret.of("typed-in-chrome")), given.changes(given.start.copy(password = "typed-in-chrome", username = "me")), "still a change")
    }

    @Test
    fun anExistingNoteKeepsATextOrATitle() {
        val editor = editing(Entry("n", title = "Films", notes = "Dune", groupId = "root", entryType = "note"))
        assertTrue(editor.canSave(editor.start.copy(notes = "")), "a title alone is still something")
        assertFalse(editor.canSave(editor.start.copy(notes = " ", title = " ")), "neither")
    }

    @Test
    fun aNewNoteMovedToAnotherDatabaseKeepsNoUsername() {
        val editor = newNote()
        val draft = editor.start.copy(notes = "Dune")
        val (moved, there) = editor.movedTo(Vault(entries = emptyList(), groups = listOf(VaultGroup("w", "Work", emptyList())), defaultUsername = "boss"), draft)
        assertEquals(EditorLayout.NOTE, moved.layout)
        assertEquals("", there.username)
        assertEquals("Dune", there.notes)
        assertTrue(there.notesProtected)
        assertEquals(dated(now), moved.savedTitle(there), "made when its editor opened, wherever it goes")
        assertEquals("", moved.start.username, "nothing typed there is still nothing to discard")
    }

    @Test
    fun aNewEntrysEditorIsNoNotes() {
        val editor = EntryEditor.create(vault, groupId = "work", now = now)
        assertEquals(EditorLayout.ENTRY, editor.layout)
        val draft = editor.start.copy(title = "Mail", notes = "n")
        assertEquals(EntryChanges(title = "Mail", username = "alex", notes = "n"), editor.changes(draft), "the default username, as before")
        assertEquals(null, editor.changes(draft).entryType)
        assertEquals(null, editor.changes(draft).notesProtected, "the database's ProtectNotes decides, as before")
        assertTrue(editor.showsWebsite(editor.start) && editor.showsUsername && editor.showsPassword && editor.showsCustomFields && editor.offersFields)
    }
}
