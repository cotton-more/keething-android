package dev.keething.domain.vault

import java.text.BreakIterator
import java.time.Instant

/**
 * The kind of an entry, kept in one item of its `CustomData` (keething-spec types.note AC1; SPEC §6.4; Mac
 * `EntryType`), beside [EntryFieldTypes]' item. KeePassXC lists it as plugin data and keeps it through its saves.
 */
public enum class EntryType(public val stored: String) {
    NOTE("note");

    public companion object {
        public const val CUSTOM_DATA_KEY: String = "Keething.EntryType"

        /** The kind a stored value names, ignoring case and outer spaces; null for one this version does not know. */
        public fun of(stored: String): EntryType? {
            val name = stored.trim().lowercase()
            return entries.firstOrNull { it.stored == name }
        }
    }
}

/** The kind of an entry set or its item removed. */
public sealed interface EntryTypeChange {
    public data class Set(val type: EntryType) : EntryTypeChange

    public data object Remove : EntryTypeChange
}

/**
 * How an untitled note's time is written: the date with the month's short name and the time to the minute, in the
 * order and the clock of the phone's region (ICU's `yMMMdjmm`, which only Android's formatter resolves).
 */
public fun interface NoteDates {
    public fun format(made: Instant): String

    public companion object {
        public const val SKELETON: String = "yMMMdjmm"
    }
}

/** The title a note gets when it is saved without one (keething-spec types.note AC5; Mac `NoteTitle`). */
public object NoteTitle {
    /** At most this many user-perceived characters (grapheme clusters) of the first line, then `…`. */
    public const val MAX_LENGTH: Int = 60

    /** The title of a protected text, so nothing of it goes into the unprotected Title. */
    public const val PROTECTED: String = "Note"

    /**
     * The text's first line that is not blank, outer spaces trimmed, cut to [MAX_LENGTH] characters followed by `…`;
     * for a protected text, or one with no line to take, `Note` and the time it was [made] ("Note, Oct 31, 2026,
     * 6:45 AM"), so untitled notes are told apart; plain `Note` without a time or [dates].
     */
    public fun make(text: String, protected: Boolean, made: Instant? = null, dates: NoteDates? = null): String {
        val untitled = if (made != null && dates != null) "$PROTECTED, ${dates.format(made)}" else PROTECTED
        if (protected) return untitled
        val line = NoteText.lines(text).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return untitled
        return cut(line)
    }

    private fun cut(line: String): String {
        val characters = BreakIterator.getCharacterInstance()
        characters.setText(line)
        var end = 0
        repeat(MAX_LENGTH) {
            end = characters.next().takeIf { it != BreakIterator.DONE } ?: return line
        }
        return if (end < line.length) line.substring(0, end) + "…" else line
    }
}

/** A note's text as it is received (the clipboard, a quick note; Mac `NoteText`). */
public object NoteText {
    /** Every line break Unicode knows, `\r\n` as one. */
    private val LINE_BREAK = Regex("\r\n|[\n\r\u000B\u000C\u0085  ]")

    internal fun lines(text: String): List<String> = text.split(LINE_BREAK)

    /**
     * The text without the blank lines (empty or white space only) before its first line and after its last; what
     * is between, the first line's indent and the line endings stay as they are. Empty when nothing but blank lines.
     */
    public fun trimmingOuterBlankLines(text: String): String {
        var start = -1
        var end = -1
        var from = 0
        fun line(until: Int) {
            if (text.substring(from, until).isNotBlank()) {
                if (start < 0) start = from
                end = until
            }
        }
        for (match in LINE_BREAK.findAll(text)) {
            line(match.range.first)
            from = match.range.last + 1
        }
        line(text.length)
        return if (start < 0) "" else text.substring(start, end)
    }
}
