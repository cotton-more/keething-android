package dev.keething.android.recyclebin

import dev.keething.android.session.file
import dev.keething.android.editor.SAVE_LOCKED
import dev.keething.android.editor.problem
import dev.keething.android.editor.savedNotice
import dev.keething.android.editor.syncWarning
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.EntryRef
import dev.keething.android.R
import dev.keething.android.ui.Notice
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiText
import dev.keething.card.GroupPathFormatter
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
 * the list says so with Undo; Restore on a card in the bin or a bin row's long press; Delete for good, asked first on the card, from the bin (or
 * anywhere when the bin is turned off), the list then saying so, with no Undo. Each is saved into the entry's own database (keething-spec
 * databases.one-list AC2, AC3: its session follows its own bin setting). The saves run in the app's scope: the card
 * closes at once and the list shows how it went ([notices], read by the list).
 */
internal class RecycleBinMoves(private val scope: CoroutineScope) {
    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

    private val mutableLeaving = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Entries Delete moved, or is moving, that the list may still show, by ref: it hides them at once rather than when
     * the save is done and the vault listed again. A failed save or Undo shows them again; [settled] drops the rest.
     */
    val leaving: StateFlow<Set<String>> = mutableLeaving

    /** [entry] as [database]'s vault has it, its own id. */
    fun moveToRecycleBin(database: DatabaseSession, entry: Entry) {
        val title = entry.displayTitle
        val ref = EntryRef(database.id, entry.id).text
        mutableLeaving.update { it + ref }
        save(database, VaultEdit.MoveToRecycleBin(entry.id, entry.lastModified)) { problem, warn ->
            if (problem != null) mutableLeaving.update { it - ref }
            mutableNotices.trySend(
                if (problem != null) {
                    Notice(problem, isProblem = true)
                } else {
                    savedNotice(uiText(R.string.recyclebin_moved, title), warn, undo = { restore(database, entry.id) })
                },
            )
        }
    }

    /**
     * Back out of the bin, into the group it came from; [entryId] is its own in [database]; [done] gets the problem, or
     * null, and whether the file may not be synced ([savedNotice]).
     */
    fun restore(database: DatabaseSession, entryId: String, done: (problem: UiText?, warn: Boolean) -> Unit = ::restored) {
        mutableLeaving.update { it - EntryRef(database.id, entryId).text }
        val vault = (database.session.state.value as? SessionState.Unlocked)?.vault ?: return
        val entry = vault.recycleBin.firstOrNull { it.id == entryId }
            ?: return done(uiText(R.string.recyclebin_restore_gone), false)
        save(database, VaultEdit.Restore(entry.id, entry.lastModified), done)
    }

    /** Restore from a row's quick actions: as on the card, the list then saying where it went ("Restored to …"). */
    fun restoreSaying(database: DatabaseSession, entryId: String) {
        restore(database, entryId) { problem, warn ->
            val vault = (database.session.state.value as? SessionState.Unlocked)?.vault
            val group = vault?.entries?.firstOrNull { it.id == entryId }?.groupPath.orEmpty()
            mutableNotices.trySend(
                problem?.let { Notice(it, isProblem = true) }
                    ?: savedNotice(restoredTo(group), warn),
            )
        }
    }

    /** For good (keething-spec edit.delete-for-good), the card having asked: [entry] as [database]'s vault has it. */
    fun deleteForGood(database: DatabaseSession, entry: Entry) {
        val title = entry.displayTitle
        val ref = EntryRef(database.id, entry.id).text
        mutableLeaving.update { it + ref }
        save(database, VaultEdit.DeleteForGood(entry.id, entry.lastModified)) { problem, warn ->
            if (problem != null) mutableLeaving.update { it - ref }
            mutableNotices.trySend(problem?.let { Notice(it, isProblem = true) } ?: savedNotice(uiText(R.string.recyclebin_deleted_for_good, title), warn))
        }
    }

    /** The list was built from vaults whose entries' refs are [listed]: those not among them have left for good. */
    fun settled(listed: Set<String>) {
        mutableLeaving.update { leaving -> leaving.filterTo(HashSet()) { it in listed } }
    }

    /** Notices of a vault that is locked now go unseen. */
    fun forget() {
        mutableLeaving.value = emptySet()
        while (mutableNotices.tryReceive().isSuccess) Unit
    }

    private fun restored(problem: UiText?, warn: Boolean) {
        if (problem != null) mutableNotices.trySend(Notice(problem, isProblem = true))
        if (problem == null && warn) mutableNotices.trySend(savedNotice(uiText(R.string.editor_saved), true))
    }

    private fun save(database: DatabaseSession, edit: VaultEdit.EntryEdit, done: (problem: UiText?, warn: Boolean) -> Unit) {
        val pending = database.session.save(edit) ?: return done(SAVE_LOCKED, false)
        scope.launch {
            val outcome = pending.await()
            done(outcome.problem(database.file), outcome.syncWarning)
        }
    }

    companion object {
        /** "Restored to Finanzen › Banken", or to the top group: where a restored entry went. */
        fun restoredTo(group: List<String>): UiText =
            GroupPathFormatter.text(group).let { if (it.isEmpty()) uiText(R.string.recyclebin_restored_to_top) else uiText(R.string.recyclebin_restored_to, it) }
    }
}
