package dev.keething.android.sharing

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.clipboard.SensitiveClipboard
import dev.keething.android.editor.SAVE_BUSY
import dev.keething.android.editor.problem
import dev.keething.android.session.DatabaseSession
import dev.keething.android.ui.Notice
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.VaultEdit
import dev.keething.sharing.message.MalformedShareException
import dev.keething.sharing.message.ShareCode
import dev.keething.sharing.message.ShareEnvelope
import dev.keething.sharing.payload.AnswerPayload
import dev.keething.sharing.payload.Receiving
import dev.keething.sharing.pictures.Pictures
import dev.keething.sharing.requests.MyRequest
import dev.keething.sharing.requests.ShareRequests
import dev.keething.vault.session.SaveOutcome
import dev.keething.vault.session.SessionState
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An answer to a request of mine, or an envelope, opened (SPEC §10): its entries to look at, copy or save as new entries
 * into a group. Saving them all closes the request (its key goes), or marks the envelope saved. An envelope opens with
 * its ten pictures, tapped or pasted; they stay in this model only, never in saved state. The entries' secrets are
 * wiped when the screen goes.
 */
internal class ReceivedModel(
    private val database: DatabaseSession,
    private val requests: ShareRequests,
    private val clipboard: SensitiveClipboard,
    codeText: String,
) : ViewModel() {
    sealed interface Opening {
        /** [request] null: from an envelope. */
        class Opened(val request: MyRequest?, val payload: AnswerPayload) : Opening

        /** An envelope, waiting for its pictures. */
        class Sealed(val envelope: ShareEnvelope) : Opening

        /** An envelope that cannot be opened now: expired, saved already, own, its dates wrong. */
        class Unavailable(val arrival: ShareRequests.Arrival) : Opening

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

    var opening by mutableStateOf(open(codeText))
        private set

    /** An envelope's, once opened: what saving marks. */
    private var envelope: ShareEnvelope? = null

    /** The envelope's pictures tapped or pasted so far, in order. */
    val heard = mutableStateListOf<Int>()

    /** Opening the envelope with them, about a third of a second. */
    var unsealing by mutableStateOf(false)
        private set

    /** The ten tapped do not open it. */
    var wrongPictures by mutableStateOf(false)
        private set

    var groupId by mutableStateOf(rootGroupId())

    var saving by mutableStateOf<Saving>(Saving.Not)
        private set

    /** Protected values shown, by "entry index/field key"; none to start with. */
    val revealed = mutableStateListOf<String>()

    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

    private fun open(text: String): Opening {
        val bytes = ShareCode.find(text).messages.singleOrNull() ?: return Opening.Broken
        val read = try {
            ShareCode.read(bytes)
        } catch (_: MalformedShareException) {
            return Opening.Broken
        }
        if (read is ShareCode.Read.Envelope) {
            return when (val arrival = requests.arrived(read.envelope)) {
                ShareRequests.Arrival.ToOpen -> Opening.Sealed(read.envelope)
                else -> Opening.Unavailable(arrival)
            }
        }
        val answer = (read as? ShareCode.Read.Answer)?.answer ?: return Opening.Broken
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

    fun tap(picture: Int) {
        if (unsealing || wrongPictures || heard.size >= Pictures.ENVELOPE_COUNT) return
        heard += picture
        if (heard.size == Pictures.ENVELOPE_COUNT) unseal()
    }

    fun undo() {
        if (unsealing || heard.isEmpty()) return
        heard.removeAt(heard.lastIndex)
        wrongPictures = false
    }

    fun tapAgain() {
        heard.clear()
        wrongPictures = false
    }

    /** The pictures in a pasted message, if it holds ten in a row; else a notice says what it held. */
    fun paste(text: String?) {
        if (unsealing) return
        val found = Pictures.find(text.orEmpty(), Pictures.ENVELOPE_COUNT)
        val pictures = found.pictures
        if (pictures == null) {
            mutableNotices.trySend(
                Notice(
                    when (found.longest) {
                        0 -> "No pictures in what was copied"
                        else -> "Ten pictures in a row are needed; that has ${found.longest}"
                    },
                    isProblem = true,
                ),
            )
            return
        }
        heard.clear()
        heard.addAll(pictures)
        wrongPictures = false
        unseal()
    }

    private fun unseal() {
        val waiting = opening as? Opening.Sealed ?: return
        val pictures = heard.toList()
        unsealing = true
        viewModelScope.launch {
            val started = TimeSource.Monotonic.markNow()
            val unsealed = withContext(Dispatchers.Default) { runCatching { requests.open(waiting.envelope, pictures) } }
            Log.i(TAG, "envelope tried in ${started.elapsedNow()}")
            unsealing = false
            when (val result = unsealed.getOrElse { ShareRequests.Unsealed.Broken }) {
                is ShareRequests.Unsealed.Entries -> {
                    envelope = waiting.envelope
                    heard.clear()
                    opening = Opening.Opened(null, result.payload)
                }
                ShareRequests.Unsealed.WrongPictures -> wrongPictures = true
                ShareRequests.Unsealed.Broken -> opening = Opening.Broken
                is ShareRequests.Unsealed.NotNow -> opening = Opening.Unavailable(result.arrival)
            }
        }
    }

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
            opened.request?.let { requests.delete(it.request.id) } ?: envelope?.let(requests::saved)
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
        opened.request?.let { requests.delete(it.request.id) }
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
        heard.clear()
    }

    private companion object {
        const val TAG = "Sharing"
        const val BUSY_TRIES = 30
        val BUSY_WAIT = 100.milliseconds
    }
}
