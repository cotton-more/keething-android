package dev.keething.android.sharing

import dev.keething.android.R
import dev.keething.android.session.file
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.clipboard.SensitiveClipboard
import dev.keething.android.editor.SAVE_LOCKED
import dev.keething.android.editor.problem
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiPlural
import dev.keething.android.ui.uiText
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import dev.keething.android.editor.NewEntryDatabase
import dev.keething.android.editor.NewEntryDatabases
import dev.keething.android.editor.NewEntryPlaces
import dev.keething.android.editor.syncWarning
import dev.keething.android.ui.Notice
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.card.editing.GroupChoice
import dev.keething.card.editing.GroupTree
import dev.keething.domain.vault.VaultEdit
import dev.keething.sharing.message.MalformedShareException
import dev.keething.sharing.message.ShareCode
import dev.keething.sharing.message.ShareEnvelope
import dev.keething.sharing.payload.AnswerPayload
import dev.keething.sharing.payload.Receiving
import dev.keething.sharing.pictures.Pictures
import dev.keething.sharing.requests.MyRequest
import dev.keething.sharing.requests.ShareRequests
import dev.keething.vault.session.SessionState
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An answer to a request of mine, or an envelope, opened (SPEC §10): its entries to look at, copy or save as new entries
 * into a group of an open database (keething-spec sharing.receiving AC8). Saving them all closes the request (its key
 * goes), or marks the envelope saved. An envelope opens with its ten pictures, tapped or pasted; they stay in this model
 * only, never in saved state. The entries' secrets are wiped when the screen goes.
 */
internal class ReceivedModel(
    private val places: NewEntryPlaces,
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

        /** For no request of this phone, which gave up its unreadable requests in the last week (keething-spec sharing.book AC3). */
        data object Lost : Opening

        /** Changed on the way, or not really for that request. */
        data object Broken : Opening
    }

    sealed interface Saving {
        data object Not : Saving

        data class Busy(val done: Int, val of: Int) : Saving

        /**
         * All saved, the request closed; [refs] of the new entries. [mayNotSync]: the last save's file may not be synced
         * (databases.saf AC6), said under it.
         */
        data class Saved(val refs: List<EntryRef>, val group: UiText, val mayNotSync: Boolean = false) : Saving

        /** Stopped at [saved] of them: the rest is not saved, the request kept. */
        data class Failed(val saved: Int, val problem: UiText) : Saving
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

    /** The entries saved so far, in order: a save tried again goes on after them. */
    private val savedIds = mutableStateListOf<String>()

    /** Each entry's id, kept from its first try: a try again uses it again. */
    private val triedIds = HashMap<Int, String>()

    /** A save was tried: its database stays (a write interrupted by a lock may have landed in it). */
    private var tried by mutableStateOf(false)

    /** The open databases as new entries see them. */
    private var open: List<NewEntryDatabase> by mutableStateOf(emptyList())

    /** The database the entries go into: the last new entry's, else the first open one, to start with. */
    var database: NewEntryDatabase? by mutableStateOf(null)
        private set

    /** Where the entries go in [database]: a group, perhaps with new groups made in it (the group picker). */
    var group by mutableStateOf<GroupChoice?>(null)
        private set

    /**
     * [group] with its new groups' ids, made once: every entry's save names the same ones; the first makes them, the
     * rest find them where it made them (keething-spec groups.new-groups AC3).
     */
    private var destination: GroupDestination? = null

    /** Save into's databases while two or more are open and nothing is saved yet; empty otherwise. */
    val offered: List<NewEntryDatabase> get() = if (!tried && saving !is Saving.Busy) NewEntryDatabases.offered(open) else emptyList()

    /** Under the databases offered: "Not open: Family Shared, Crypto". */
    val notOpen: UiText? get() = NewEntryDatabases.notOpen(places.window(), open)

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
            ShareRequests.Opened.Lost -> Opening.Lost
            ShareRequests.Opened.Broken -> Opening.Broken
        }
    }

    init {
        viewModelScope.launch {
            places.open.collect { open ->
                this@ReceivedModel.open = open
                val next = NewEntryDatabases.following(database, open, places.last(), keep = tried)
                val before = database
                database = next
                if (before != null && next != null && next.id != before.id) {
                    mutableNotices.trySend(Notice(uiText(R.string.sharing_received_locked_moved, before.mark.label.full, next.mark.label.full)))
                }
                // Another database: its top level; the same one read again keeps the group while it is there.
                if (next?.id != before?.id || next?.vault?.groups?.none { it.id == group?.groupId } == true) putGroup(next?.let(::rootGroupId)?.let(::GroupChoice))
            }
        }
    }

    /** Save into: another open database, its top level; not once a save was tried (the rest go where it went). */
    fun saveInto(id: DatabaseId) {
        if (id == database?.id) return
        val target = offered.firstOrNull { it.id == id } ?: return
        database = target
        putGroup(rootGroupId(target)?.let(::GroupChoice))
    }

    /** The group can be chosen until a save is tried: the rest then go where the first went, as with Save into. */
    val groupChangeable: Boolean get() = !tried && saving !is Saving.Busy

    /** A group from the picker. */
    fun chooseGroup(choice: GroupChoice) {
        if (groupChangeable) putGroup(choice)
    }

    private fun putGroup(choice: GroupChoice?) {
        group = choice
        destination = choice?.let { GroupDestination(it.groupId, it.newNames.map { name -> NewGroup(Entry.newId(), name) }) }
    }

    private fun rootGroupId(database: NewEntryDatabase): String? =
        database.vault.groups.firstOrNull { it.isRoot }?.id ?: database.vault.groups.firstOrNull()?.id

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
                        0 -> uiText(R.string.sharing_received_paste_none)
                        else -> uiPlural(R.plurals.sharing_received_paste_short, found.longest)
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


    /**
     * Each entry a save of its own, one after the other; then the request goes with its key. One that failed stops
     * there; trying again saves the rest only.
     */
    fun save() {
        val opened = opening as? Opening.Opened ?: return
        val destination = destination ?: return
        val place = database ?: return
        if (saving is Saving.Busy || saving is Saving.Saved) return
        val entries = opened.payload.entries
        viewModelScope.launch {
            var mayNotSync = false
            for (index in savedIds.size until entries.size) {
                // Its database locked since: the rest wait for it (they never go into another one).
                val database = places.session(place.id)?.takeIf { NewEntryDatabases.isOpen(place.id, it.number, open) }
                if (database == null) {
                    saving = Saving.Failed(index, uiText(R.string.sharing_received_locked, place.mark.label.full))
                    return@launch
                }
                saving = Saving.Busy(index, entries.size)
                // Tried again: the same id. A write that landed although its outcome said otherwise (interrupted by a
                // lock) is in the file read again: saved, not saved twice.
                tried = true
                val id = triedIds.getOrPut(index) { Entry.newId() }
                if ((database.session.state.value as? SessionState.Unlocked)?.vault?.entries?.any { it.id == id } == true) {
                    savedIds += id
                    continue
                }
                val edit = VaultEdit.Create(id, destination, Receiving.changes(entries[index]))
                val pending = database.session.save(edit)
                if (pending == null) {
                    edit.fields.wipe()
                    saving = Saving.Failed(index, SAVE_LOCKED)
                    return@launch
                }
                pending.invokeOnCompletion { edit.fields.wipe() }
                val outcome = pending.await()
                val problem = outcome.problem(database.file)
                // Only the last save's file counts: a later save replaces it with a time of its own.
                mayNotSync = outcome.syncWarning
                if (problem != null) {
                    Log.i(TAG, "saving a received entry failed")
                    saving = Saving.Failed(index, problem)
                    return@launch
                }
                savedIds += id
            }
            opened.request?.let { requests.delete(it.request.id) } ?: envelope?.let(requests::saved)
            places.created(place.id)
            saving = Saving.Saved(
                savedIds.map { EntryRef(place.id, it) },
                groupName(place, GroupChoice(destination.groupId, destination.newGroups.map { it.name })),
                mayNotSync,
            )
        }
    }

    /** Closes the request without saving: the key goes, the answer can no longer be opened. */
    fun close() {
        val opened = opening as? Opening.Opened ?: return
        opened.request?.let { requests.delete(it.request.id) }
    }

    private fun groupName(database: NewEntryDatabase, choice: GroupChoice): UiText {
        val vault = (places.session(database.id)?.session?.state?.value as? SessionState.Unlocked)?.vault
        val levels = vault?.let { GroupTree.of(it).levels(choice) }
        return when {
            levels == null -> uiText(R.string.sharing_received_in_database)
            levels.isEmpty() -> uiText(R.string.sharing_received_in_top_level)
            else -> uiPlain(levels.joinToString(" › ") { it.name })
        }
    }

    override fun onCleared() {
        (opening as? Opening.Opened)?.payload?.wipe()
        heard.clear()
    }

    private companion object {
        const val TAG = "Sharing" // not user text
    }
}
