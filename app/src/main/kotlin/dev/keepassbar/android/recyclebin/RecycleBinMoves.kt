package dev.keepassbar.android.recyclebin

import dev.keepassbar.android.editor.SAVE_BUSY
import dev.keepassbar.android.editor.problem
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.ui.Notice
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.VaultEdit
import dev.keepassbar.vault.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Entries into the recycle bin and out of it (SPEC §6.2): Delete moves the entry at once, no question asked, and
 * the list says so with Undo; Restore on a card in the bin. The saves run in the app's scope: the card closes
 * at once and the list shows how it went ([notices], read by the list).
 */
internal class RecycleBinMoves(private val scope: CoroutineScope) {
    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

    fun moveToRecycleBin(database: DatabaseSession, entry: Entry) {
        val title = entry.displayTitle
        save(database, VaultEdit.MoveToRecycleBin(entry.id, entry.lastModified)) { problem ->
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
        val vault = (database.session.state.value as? SessionState.Unlocked)?.vault ?: return
        val entry = vault.recycleBin.firstOrNull { it.id == entryId }
            ?: return done("This entry is not in the recycle bin any more (restored elsewhere?). Nothing was changed.")
        save(database, VaultEdit.Restore(entry.id, entry.lastModified), done)
    }

    /** Notices of a vault that is locked now go unseen. */
    fun forget() {
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
