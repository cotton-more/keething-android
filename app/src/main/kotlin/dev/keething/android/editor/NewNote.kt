package dev.keething.android.editor

import dev.keething.android.clipboard.SystemClipboard
import dev.keething.domain.vault.NoteText

/**
 * A new note's editor asked for (SPEC §6.4; keething-spec types.note AC4), with the [text] it starts with (New note
 * from clipboard; empty for New note). It travels with the editor's screen in memory only: two are two editors, and its
 * name says nothing of the text, since a screen's name keys its saved state (app.quick-note AC7).
 */
internal class NewNote(val text: String) {
    override fun toString(): String = "NewNote"

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
