package dev.keething.android.quicknote

import androidx.lifecycle.ViewModel
import dev.keething.android.editor.NewNote
import dev.keething.android.quicknote.QuickNoteRun.Source
import dev.keething.android.quicknote.QuickNoteRun.State
import dev.keething.domain.vault.Vault
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The quick note's screen over another app (SPEC §6.4; keething-spec app.quick-note AC3, AC4, AC7; the Mac's
 * `QuickNoteWindow`): the text a share or the selection menu handed over, or the clipboard's (the tile, the launcher's
 * shortcut; looked at once the screen has the window focus), goes into the quick note's editor in the quick notes'
 * database, after that database's lock screen when it is locked ([QuickNoteRun]). It goes once the note is
 * saved, and when it is given up: Back; the screen left (stopped while nothing it opened itself is in front); a lock of
 * the app (Lock all, the timeout, a stay away past it); its database locked while the editor is open, or gone from the
 * window; a newer quick note; a screen re-created after its process died, whose intent is not read again. Once it goes
 * it keeps nothing of the text. Call it on the main thread.
 */
internal class QuickNoteModel(
    /** The quick notes' database ([dev.keething.vault.databases.QuickNoteTarget]). */
    target: () -> DatabaseId?,
    /** Whether a database is open, as the sessions say now: they decide (the window's [state] follows them). */
    isOpen: (DatabaseId) -> Boolean,
    /**
     * A database's state while it is in the window; null while it is not. It follows the sessions a moment late: just
     * after a lock it can still say unlocked (seen on the Pixel 6, the timeout's lock as the screen started), so an
     * unlocked state opens the editor only when [isOpen] agrees.
     */
    private val state: (DatabaseId) -> Flow<SessionState?>,
    /** Puts a database of the list into the window, so that its lock screen can show. */
    private val intoWindow: (DatabaseId) -> Unit,
    /** The quick notes started in the process, counted: a newer one gives this one up. */
    private val started: MutableStateFlow<Int>,
    /**
     * The app's locks, counted ([dev.keething.android.session.AppLocks]): also a stay away past the lock timeout, which
     * a frozen app cannot time itself and counts when it is back in front.
     */
    private val locks: StateFlow<Int>,
    private val scope: CoroutineScope,
) : ViewModel(scope) {
    /** What the screen shows. None of them says anything of the text when printed. */
    sealed interface Showing {
        /** Not started yet. */
        data object Nothing : Showing

        /** The database's lock screen, "Unlock to write the quick note". */
        data class Unlock(val database: DatabaseId) : Showing

        /** The quick note's editor in that database. */
        data class Editor(val database: DatabaseId, val note: NewNote) : Showing

        /** The screen goes, saying [message] when there is one (a toast). */
        data class Gone(val message: String?) : Showing
    }

    private val mutableShowing = MutableStateFlow<Showing>(Showing.Nothing)

    val showing: StateFlow<Showing> = mutableShowing.asStateFlow()

    private val run = QuickNoteRun(target, isOpen) { database, text, source ->
        mutableShowing.value = Showing.Editor(database, NewNote(text, quick = true, source = source))
    }

    /** What follows the database, the app's locks and the newer quick notes; ended when the screen goes. */
    private val watching = Job(scope.coroutineContext[Job])

    /** Whether the screen has the window focus: only then may it read the clipboard (Android 10+). */
    private val focused = MutableStateFlow(false)

    /** The clipboard's quick note, asked for before the screen had the focus; it starts once it has. */
    private var waitingForFocus: Source.Clipboard? = null

    /**
     * The screen's first start: [source] reads the intent, unless the screen was [recreated] after its process died
     * (gives up). The clipboard's starts once the screen has the window focus ([focus]): before, Android says it holds
     * nothing. Again for the same screen (a configuration change): nothing. No text or no database leaves a quick note
     * already waiting alone.
     */
    fun start(recreated: Boolean, source: () -> Source?) {
        if (showing.value != Showing.Nothing || waitingForFocus != null) return
        if (recreated) return finish(null)
        when (val asked = source() ?: Source.Given("")) {
            is Source.Clipboard -> if (focused.value) begin(asked) else waitingForFocus = asked
            is Source.Given -> begin(asked)
        }
    }

    /** The screen gained or lost the window focus (the fingerprint prompt takes it while it shows). */
    fun focus(has: Boolean) {
        focused.value = has
        if (has) waitingForFocus?.let {
            waitingForFocus = null
            begin(it)
        }
    }

    private fun begin(source: Source) {
        val locked = locks.value
        run.start(source)
        val database = when (val state = run.state) {
            is State.Unlocking -> state.database.also {
                intoWindow(it)
                mutableShowing.value = Showing.Unlock(it)
            }
            is State.Opened -> state.database
            else -> return finish(state.message)
        }
        val number = started.value + 1
        started.value = number
        scope.launch(watching) {
            started.first { it != number }
            finish(null)
        }
        scope.launch(watching) {
            locks.first { it != locked }
            finish(null)
        }
        follow(database, readsClipboard = source is Source.Clipboard)
    }

    /**
     * The database's state: unlocked, the editor opens; locked while the editor is open, or gone from the window once
     * it was there, the quick note is given up. The clipboard is read only with the focus: unlocked under the fingerprint
     * prompt, the editor waits for it to come back.
     */
    private fun follow(database: DatabaseId, readsClipboard: Boolean) {
        scope.launch(watching) {
            var seen = false
            var opened = false
            combine(state(database), focused, ::Pair).collect { (state, focus) ->
                if (state != null) seen = true
                if (state is SessionState.Unlocked) {
                    opened = true
                    if (showing.value is Showing.Unlock && (focus || !readsClipboard)) {
                        run.unlocked(database)
                        run.state.message?.let(::finish)
                    }
                } else if ((state == null && seen) || (opened && showing.value is Showing.Editor)) {
                    finish(null)
                }
            }
        }
    }

    /** Back on the lock screen (the editor's Back is its own: [editorClosed]). */
    fun back() = finish(null)

    /**
     * The screen stopped: given up, unless [ownInFront] (a key file picker, Locate…), which waits for its return or a
     * lock of the app.
     */
    fun stopped(ownInFront: Boolean) {
        if (!ownInFront) finish(null)
    }

    /** The editor closed without saving: its X or Back, the draft discarded. */
    fun editorClosed() = finish(null)

    /** The note saved as [entryId]: "Saved to Inbox", the group it went to by [vault] as read after the save. */
    fun saved(vault: Vault?, entryId: String) {
        val entry = vault?.entries?.firstOrNull { it.id == entryId }
        val group = entry?.let { it.groupPath.lastOrNull() ?: vault.groups.firstOrNull { group -> group.isRoot }?.name }
        finish(group?.let { "Saved to $it" } ?: "Saved")
    }

    /** The screen goes; nothing of the text stays. */
    private fun finish(message: String?) {
        if (showing.value is Showing.Gone) return
        waitingForFocus = null
        run.giveUp()
        mutableShowing.value = Showing.Gone(message)
        watching.cancel()
    }
}
