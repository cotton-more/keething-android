package dev.keething.android.recyclebin

import dev.keething.android.editor.SAVE_BUSY
import dev.keething.android.editor.problem
import dev.keething.android.session.DatabaseSession
import dev.keething.android.ui.Notice
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.VaultEdit
import dev.keething.vault.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Entries into the recycle bin and out of it (SPEC §6.2): Delete moves the entry at once, no question asked, and
 * the list says so with Undo; Restore on a card in the bin. The saves run in the app's scope: the card closes
 * at once and the list shows how it went ([notices], read by the list).
 */
internal class RecycleBinMoves(private val scope: CoroutineScope) {
    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

    private val mutableLeaving = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Entries Delete moved, or is moving, that the list may still show: it hides them at once rather than when the
     * save is done and the vault listed again. A failed save or Undo shows them again; [settled] drops the rest.
     */
    val leaving: StateFlow<Set<String>> = mutableLeaving

    fun moveToRecycleBin(database: DatabaseSession, entry: Entry) {
        val title = entry.displayTitle
        mutableLeaving.update { it + entry.id }
        save(database, VaultEdit.MoveToRecycleBin(entry.id, entry.lastModified)) { problem ->
            if (problem != null) mutableLeaving.update { it - entry.id }
            mutableNotices.trySend(
                if (problem != null) {
                    Notice(problem, isProblem = true)
                } else {
                    Notice("Moved “$title” to the recycle bin", undo = { restore(database, entry.id) })
                },
            )
        }
    }

    /** Back out of the bin, into the group it came from; [done] gets the problem, or null. */
    fun restore(database: DatabaseSession, entryId: String, done: (problem: String?) -> Unit = ::restored) {
        mutableLeaving.update { it - entryId }
        val vault = (database.session.state.value as? SessionState.Unlocked)?.vault ?: return
        val entry = vault.recycleBin.firstOrNull { it.id == entryId }
            ?: return done("This entry is not in the recycle bin any more (restored elsewhere?). Nothing was changed.")
        save(database, VaultEdit.Restore(entry.id, entry.lastModified), done)
    }

    /** The list was built from a vault whose entries are [listed]: those not among them have left for good. */
    fun settled(listed: Set<String>) {
        mutableLeaving.update { leaving -> leaving.filterTo(HashSet()) { it in listed } }
    }

    /** Notices of a vault that is locked now go unseen. */
    fun forget() {
        mutableLeaving.value = emptySet()
        while (mutableNotices.tryReceive().isSuccess) Unit
    }

    private fun restored(problem: String?) {
        if (problem != null) mutableNotices.trySend(Notice(problem, isProblem = true))
    }

    private fun save(database: DatabaseSession, edit: VaultEdit, done: (problem: String?) -> Unit) {
        val pending = database.session.save(edit) ?: return done(SAVE_BUSY)
        scope.launch { done(pending.await().problem(database.file)) }
    }
}
