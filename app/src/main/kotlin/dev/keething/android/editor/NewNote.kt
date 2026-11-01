package dev.keething.android.editor

import dev.keething.android.clipboard.SystemClipboard
import dev.keething.card.editing.EntryDraft
import dev.keething.card.editing.EntryEditor
import dev.keething.card.editing.QuickNoteSource
import dev.keething.domain.vault.NoteDates
import dev.keething.domain.vault.NoteText
import dev.keething.domain.vault.Vault
import java.time.Instant

/**
 * A new note's editor asked for (SPEC §6.4; keething-spec types.note AC4), with the [text] it starts with (New note
 * from clipboard; empty for New note). It travels with the editor's screen in memory only: two are two editors, and its
 * name says nothing of the text, since a screen's name keys its saved state (app.quick-note AC7).
 */
internal class NewNote(
    val text: String,
    /**
     * A quick note's (keething-spec app.quick-note AC2, AC3): in the Inbox of the database it was opened in, which the
     * setting chose, so no Save into.
     */
    val quick: Boolean = false,
    /** Where a quick note's text came from (keething-spec app.quick-note-source): the app's tag and the page. */
    val source: QuickNoteSource = QuickNoteSource(),
) {
    override fun toString(): String = "NewNote"

    /** The editor it opens: a quick note's in the Inbox ([EntryEditor.createQuickNote]), else New note's in [groupId]. */
    fun editor(vault: Vault, groupId: String?, now: Instant, dates: NoteDates): EntryEditor =
        if (quick) EntryEditor.createQuickNote(vault, now, dates) else EntryEditor.createNote(vault, groupId, now, dates)

    /**
     * The draft its editor starts with: [start] with the text, and a quick note's app as a tag (spelt as the database's
     * [knownTags] spell it) and page in the Website, all of them changes the user can take out before Save.
     */
    fun draft(start: EntryDraft, knownTags: List<String>): EntryDraft = source.applied(start.copy(notes = text), knownTags)

    /** Whether its editor offers Save into: not a quick note's, whose database the setting chose (app.quick-note AC1). */
    val offersSaveInto: Boolean get() = !quick

    companion object {
        val EMPTY: NewNote get() = NewNote("")
    }
}

/**
 * New note from clipboard (SPEC §6.4; keething-spec app.quick-note AC6, AC7; the Mac's `startNewNote(fromClipboard:)`):
 * the clipboard's text read once, on the tap (the app in front), outer blank lines trimmed; the clipboard left as it was.
 */
internal object NoteFromClipboard {
    const val NO_TEXT: String = "No text on the clipboard"

    /** The text the note starts with; null when the clipboard holds none, or only blank lines. */
    fun read(clipboard: SystemClipboard): String? =
        clipboard.text()?.let(NoteText::trimmingOuterBlankLines)?.takeIf { it.isNotBlank() }
}
