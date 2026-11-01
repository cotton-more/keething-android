package dev.keething.android.quicknote

import dev.keething.android.clipboard.SystemClipboard
import dev.keething.card.editing.QuickNoteSource
import dev.keething.domain.vault.NoteText
import dev.keething.vault.databases.DatabaseId

/**
 * One quick note asked for (SPEC §6.4; keething-spec app.quick-note AC3, AC4, AC7; the Mac's `QuickNoteRun`): a text
 * into the editor of a new note in the Inbox of the quick notes' database, unsaved. The text is handed over (a share,
 * the selection menu) or on the clipboard (the tile, the launcher's shortcut). From the clipboard only whether it holds
 * text is looked at before an unlock, so nothing of it is in memory while the lock screen waits; a text handed over is
 * in memory already (it came in the intent) and is held only until the unlock or giving up. Once the editor has the
 * text the run keeps none of it.
 */
internal class QuickNoteRun(
    /** The quick notes' database ([dev.keething.vault.databases.QuickNoteTarget]). */
    private val target: () -> DatabaseId?,
    private val isOpen: (DatabaseId) -> Boolean,
    /** Shows the quick note's editor in that database with the text and where it came from. */
    private val openEditor: (DatabaseId, String, QuickNoteSource) -> Unit,
) {
    /** Where the text comes from. Neither says anything of the text when printed. */
    sealed interface Source {
        /**
         * Handed over with the request: a share's or the selection menu's text, and where it came from (the app, a
         * share's page), which waits for the unlock with it.
         */
        class Given(val text: String, val source: QuickNoteSource = QuickNoteSource()) : Source {
            override fun toString(): String = "Given"
        }

        /** The clipboard's, read only once the database is open. */
        class Clipboard(val clipboard: SystemClipboard) : Source {
            override fun toString(): String = "Clipboard"
        }
    }

    sealed interface State {
        data object Idle : State

        /** No text (or only blank lines): no editor. [fromClipboard]: the clipboard held none. */
        data class NoText(val fromClipboard: Boolean) : State

        /** No database in the list to put it in. */
        data object NoDatabase : State

        /** The database is locked: its lock screen is up; the clipboard not read yet. */
        data class Unlocking(val database: DatabaseId) : State

        /** The editor is open in that database. */
        data class Opened(val database: DatabaseId) : State

        /** The unlock was given up: nothing read, nothing kept, and a later unlock opens nothing. */
        data object GivenUp : State

        /** What the screen says for it (a toast); null when it has nothing to say. */
        val message: String?
            get() = when (this) {
                is NoText -> if (fromClipboard) NO_TEXT_ON_CLIPBOARD else NO_TEXT
                NoDatabase -> NO_DATABASE
                else -> null
            }
    }

    var state: State = State.Idle
        private set

    /** Where the text comes from while the unlock waits; null otherwise. */
    private var waiting: Source? = null

    /**
     * A quick note asked for. While an unlock waits, nothing new: the lock screen is already up (whether a second
     * share replaces the first is the way in's to decide).
     */
    fun start(source: Source) {
        if (state is State.Unlocking) return
        val fromClipboard = source is Source.Clipboard
        val hasText = when (source) {
            is Source.Given -> source.text.isNotBlank()
            is Source.Clipboard -> source.clipboard.hasText()
        }
        if (!hasText) {
            state = State.NoText(fromClipboard)
            return
        }
        val database = target() ?: run {
            state = State.NoDatabase
            return
        }
        if (isOpen(database)) {
            open(database, source)
        } else {
            waiting = source
            state = State.Unlocking(database)
        }
    }

    /** A database was unlocked: the one waited for opens the editor, with the clipboard's text as it is now. */
    fun unlocked(database: DatabaseId) {
        if (state != State.Unlocking(database) || !isOpen(database)) return
        val source = waiting ?: return
        waiting = null
        open(database, source)
    }

    /** The lock screen was left (Back, the screen gone, another database chosen, a lock): nothing read, nothing kept. */
    fun giveUp() {
        if (state !is State.Unlocking) return
        waiting = null
        state = State.GivenUp
    }

    private fun open(database: DatabaseId, source: Source) {
        val text = NoteText.trimmingOuterBlankLines(
            when (source) {
                is Source.Given -> source.text
                is Source.Clipboard -> source.clipboard.text().orEmpty()
            },
        )
        if (text.isEmpty()) {
            state = State.NoText(source is Source.Clipboard)
            return
        }
        // The clipboard says no app, and no browser's clip carries a page (plans/done/android-notes.md AQ0).
        openEditor(database, text, (source as? Source.Given)?.source ?: QuickNoteSource())
        state = State.Opened(database)
    }

    companion object {
        const val NO_TEXT: String = "No text to save"
        const val NO_TEXT_ON_CLIPBOARD: String = "No text on the clipboard"
        const val NO_DATABASE: String = "No database to put the quick note in"
    }
}
