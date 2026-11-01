package dev.keething.card

import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.NoteText
import dev.keething.domain.vault.NoteTitle

/** A note's line in the list (keething-spec types.note AC7, SPEC §6.4; Mac `NoteLine`). */
public object NoteLine {
    /** What a protected text shows: nothing of it is read for the list. */
    public const val PROTECTED: String = "Protected note"

    /**
     * `Protected note` for a protected text, decided before the text is looked at; otherwise its first line that is
     * not blank and not the title (both trimmed; a title [NoteTitle] made from that line counts as it), or null.
     */
    public fun of(entry: Entry): String? {
        if (entry.notesProtected) return PROTECTED
        val title = entry.title.trim()
        return NoteText.lines(entry.notes).asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != title && NoteTitle.make(it, protected = false) != title }
    }
}

/** A part of a shown text to copy through the app ([EntryCard.selectionToCopy]); [sensitive] as its row's Copy. */
public data class NoteSelection(val text: String, val sensitive: Boolean)
