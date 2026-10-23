package dev.keepassbar.android.sharing

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keepassbar.android.clipboard.SensitiveClipboard
import dev.keepassbar.android.editor.SAVE_BUSY
import dev.keepassbar.android.editor.problem
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.ui.Notice
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.VaultEdit
import dev.keepassbar.sharing.message.MalformedShareException
import dev.keepassbar.sharing.message.ShareAnswer
import dev.keepassbar.sharing.message.ShareCode
import dev.keepassbar.sharing.payload.AnswerPayload
import dev.keepassbar.sharing.payload.Receiving
import dev.keepassbar.sharing.requests.MyRequest
import dev.keepassbar.sharing.requests.ShareRequests
import dev.keepassbar.vault.session.SaveOutcome
import dev.keepassbar.vault.session.SessionState
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * An answer to a request of mine, opened (SPEC §10): its entries to look at, copy or save as new entries into a group.
 * Saving them all closes the request (its key goes). The entries' secrets are wiped when the screen goes.
 */
internal class ReceivedModel(
    private val database: DatabaseSession,
    private val requests: ShareRequests,
    private val clipboard: SensitiveClipboard,
    answerText: String,
) : ViewModel() {
    sealed interface Opening {
        class Opened(val request: MyRequest, val payload: AnswerPayload) : Opening

        /** For no request of this phone (closed, expired, another phone's). */
        data object NotHere : Opening

        /** Changed on the way, or not really for that request. */
        data object Broken : Opening
    }

    sealed interface Saving {
        data object Not : Saving

        data class Busy(val done: Int, val of: Int) : Saving

        /** All saved, the request closed; [entryIds] of the new entries. */
        data class Saved(val entryIds: List<String>, val group: String) : Saving

        /** Stopped at [saved] of them: the rest is not saved, the request kept. */
        data class Failed(val saved: Int, val problem: String) : Saving
    }

    val opening: Opening = open(answerText)

    var groupId by mutableStateOf(rootGroupId())

    var saving by mutableStateOf<Saving>(Saving.Not)
        private set

    /** Protected values shown, by "entry index/field key"; none to start with. */
    val revealed = mutableStateListOf<String>()

    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

    private fun open(text: String): Opening {
        val bytes = ShareCode.find(text).messages.singleOrNull() ?: return Opening.Broken
        val answer = try {
            ShareAnswer.decode(bytes)
        } catch (_: MalformedShareException) {
            return Opening.Broken
        }
        val opened = try {
            requests.open(answer)
        } catch (_: RuntimeException) {
            return Opening.Broken
        }
        return when (opened) {
            is ShareRequests.Opened.Entries -> Opening.Opened(opened.request, opened.payload)
            ShareRequests.Opened.NotHere -> Opening.NotHere
            ShareRequests.Opened.Broken -> Opening.Broken
        }
    }

    private fun rootGroupId(): String? =
        (database.session.state.value as? SessionState.Unlocked)?.vault?.groups?.firstOrNull { it.isRoot }?.id

    fun copy(what: String, text: String, sensitive: Boolean) {
        clipboard.copy(text, sensitive)
        mutableNotices.trySend(Notice.copied(what))
    }

    fun toggle(key: String) {
        if (!revealed.remove(key)) revealed += key
    }

    /** The entries saved so far, in order: a save tried again goes on after them. */
    private val savedIds = mutableListOf<String>()

    /**
     * Each entry a save of its own, one after the other; then the request goes with its key. One that failed stops
     * there; trying again saves the rest only.
     */
    fun save() {
        val opened = opening as? Opening.Opened ?: return
        val group = groupId ?: return
        if (saving is Saving.Busy || saving is Saving.Saved) return
        val entries = opened.payload.entries
        viewModelScope.launch {
            for (index in savedIds.size until entries.size) {
                saving = Saving.Busy(index, entries.size)
                val id = Entry.newId()
                val edit = VaultEdit.Create(id, group, Receiving.changes(entries[index]))
                val pending = startSave(edit)
                if (pending == null) {
                    edit.fields.wipe()
                    saving = Saving.Failed(index, SAVE_BUSY)
                    return@launch
                }
                pending.invokeOnCompletion { edit.fields.wipe() }
                val problem = pending.await().problem(database.file)
                if (problem != null) {
                    Log.i(TAG, "saving a received entry failed")
                    saving = Saving.Failed(index, problem)
                    return@launch
                }
                savedIds += id
            }
            requests.delete(opened.request.request.id)
            saving = Saving.Saved(savedIds.toList(), groupName(group))
        }
    }

    /**
     * The session takes one save at a time and is free again only a moment after the last one told its outcome
     * (this screen resumes at once): it is asked again for a short while before giving up.
     */
    private suspend fun startSave(edit: VaultEdit): Deferred<SaveOutcome>? {
        repeat(BUSY_TRIES) {
            database.session.save(edit)?.let { return it }
            delay(BUSY_WAIT)
        }
        return null
    }

    /** Closes the request without saving: the key goes, the answer can no longer be opened. */
    fun close() {
        val opened = opening as? Opening.Opened ?: return
        requests.delete(opened.request.request.id)
    }

    private fun groupName(id: String): String {
        val group = (database.session.state.value as? SessionState.Unlocked)?.vault?.groups?.firstOrNull { it.id == id }
        return when {
            group == null -> "the database"
            group.isRoot -> "the top level"
            else -> group.path.joinToString(" › ")
        }
    }

    override fun onCleared() {
        (opening as? Opening.Opened)?.payload?.wipe()
    }

    private companion object {
        const val TAG = "Sharing"
        const val BUSY_TRIES = 30
        val BUSY_WAIT = 100.milliseconds
    }
}
