package dev.keething.android.editor

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.databases.DatabaseMark
import dev.keething.domain.vault.Vault
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.EntryRef
import kotlinx.coroutines.flow.Flow
import dev.keething.card.editing.AddedCodes
import dev.keething.card.editing.EntryDraft
import dev.keething.card.editing.EntryEditor
import dev.keething.domain.vault.VaultEdit
import dev.keething.vault.session.SaveOutcome
import dev.keething.search.SearchFacets
import dev.keething.vault.session.SessionState
import java.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.launch

/** Where the editor's save stands (the sheet of the mock-up: "Saving and checking…", then saved or not). */
internal sealed interface SavePhase {
    data object Editing : SavePhase

    data object Saving : SavePhase

    /** [ref]: the entry in its database (a new one's id). */
    data class Saved(val ref: EntryRef) : SavePhase

    data class Failed(val problem: String) : SavePhase
}

/**
 * What a new entry's editor needs of the open databases (keething-spec databases.one-list AC4–AC6): them as they
 * change, a database's session, the window's databases to name those not open, and where the last new entry went.
 */
internal class NewEntryPlaces(
    val open: Flow<List<NewEntryDatabase>>,
    val session: (DatabaseId) -> DatabaseSession?,
    val window: () -> List<DatabaseMark>,
    val last: () -> DatabaseId?,
    val created: (DatabaseId) -> Unit,
)

/**
 * The editor of an entry, or of a new one ([entryId] null) in [groupId] (SPEC §6.2; Mac `EntryEditorModel`): the
 * draft as typed, and its save through the session (SPEC §3.3). While it is open the values are plain strings,
 * as the text fields hold them; they go with the editor (and a lock closes it). A new entry with [places] may move to
 * another open database (Save into); its draft goes when its database locks, not when another does.
 */
internal class EntryEditorViewModel(
    database: DatabaseSession,
    entryId: String?,
    groupId: String?,
    /** Values to start with (a login typed elsewhere, SPEC §7): they count as changes. */
    prefill: ((EntryDraft) -> EntryDraft)? = null,
    private val places: NewEntryPlaces? = null,
    now: Instant = Instant.now(),
) : ViewModel() {
    /** The database the entry is saved into: a new entry's chosen one. */
    var database: DatabaseSession by mutableStateOf(database)
        private set

    /** Null when the entry is gone (deleted elsewhere) or its database locked: the editor closes. */
    var editor: EntryEditor? by mutableStateOf(
        (database.session.state.value as? SessionState.Unlocked)?.vault?.let { vault ->
            if (entryId == null) EntryEditor.create(vault, groupId, now) else vault.entries.firstOrNull { it.id == entryId }?.let { EntryEditor.edit(it, now) }
        },
    )
        private set

    /** The database's tags, the most used first: the tag field's completions. */
    var knownTags: List<String> by mutableStateOf((database.session.state.value as? SessionState.Unlocked)?.vault?.let(::tagsOf).orEmpty())
        private set

    var draft: EntryDraft by mutableStateOf(editor?.start?.let { prefill?.invoke(it) ?: it } ?: EntryDraft())
        private set

    /** The open databases as a new entry sees them; empty for an entry being edited. */
    private var open: List<NewEntryDatabase> by mutableStateOf(emptyList())

    /** Save into's choices while two or more are open (keething-spec databases.one-list AC5); empty otherwise. */
    val offered: List<NewEntryDatabase> get() = if (editor?.isNew == true) NewEntryDatabases.offered(open) else emptyList()

    /** The database chosen, as Save into shows it. */
    val chosen: NewEntryDatabase? get() = open.firstOrNull { it.id == database.id }

    /** Under Save into's choices: "Not open: Family Shared, Crypto". */
    val notOpen: String? get() = places?.let { NewEntryDatabases.notOpen(it.window(), open) }

    var phase: SavePhase by mutableStateOf(SavePhase.Editing)
        private set

    /** Whether the draft stays as the open databases change: its own database locking takes it, a save in flight not. */
    private val follower = DraftFollower()

    init {
        val places = places
        if (entryId == null && places != null) {
            viewModelScope.launch {
                places.open.collect { open ->
                    this@EntryEditorViewModel.open = open
                    val database = this@EntryEditorViewModel.database
                    if (follower.follow(database.id, database.number, open, editing = phase == SavePhase.Editing)) editor = null
                }
            }
        }
    }

    fun update(draft: EntryDraft) {
        if (phase == SavePhase.Saving) return
        this.draft = draft
    }

    /** Adds a block of codes; the problem when none could be added. */
    fun addCodes(text: String, prefix: String): String? {
        val editor = editor ?: return null
        return when (val added = editor.addCodes(draft, text, prefix)) {
            is AddedCodes.Added -> {
                update(added.draft)
                null
            }
            is AddedCodes.Refused -> added.problem
        }
    }

    fun save() {
        val editor = editor ?: return
        val database = database
        if (!editor.canSave(draft) || phase == SavePhase.Saving) return
        val edit = editor.edit(draft)
        val pending = database.session.save(edit)
        if (pending == null) {
            edit.wipe()
            phase = SavePhase.Failed(SAVE_BUSY)
            return
        }
        // The session reads the edit's secrets until the save is over, even when this editor has gone.
        pending.invokeOnCompletion { edit.wipe() }
        phase = SavePhase.Saving
        val started = TimeSource.Monotonic.markNow()
        viewModelScope.launch {
            val outcome = pending.await()
            val how = when (outcome) {
                SaveOutcome.Saved -> "saved"
                is SaveOutcome.Failed -> "failed"
                SaveOutcome.Interrupted -> "interrupted"
            }
            Log.i(TAG, "$how after ${started.elapsedNow()}")
            val problem = outcome.problem(database.file)
            if (problem == null && editor.isNew) places?.created?.invoke(database.id)
            phase = problem?.let(SavePhase::Failed) ?: SavePhase.Saved(EntryRef(database.id, editor.entryId))
        }
    }

    /**
     * Save into: the new entry moves to the open database [id]; its groups, tags and default username become that
     * database's, the group goes to its root and the username to its default unless changed (databases.one-list AC5).
     */
    fun saveInto(id: DatabaseId) {
        val editor = editor ?: return
        if (!editor.isNew || phase != SavePhase.Editing || id == database.id) return
        val target = open.firstOrNull { it.id == id && it.takesEntries } ?: return
        val session = places?.session?.invoke(id)?.takeIf { it.number == target.session } ?: return
        val (moved, movedDraft) = editor.movedTo(target.vault, draft)
        database = session
        follower.moved(session.number)
        this.editor = moved
        draft = movedDraft
        knownTags = tagsOf(target.vault)
    }

    /** Back from a failed save to the draft, to change it or leave. */
    fun keepEditing() {
        if (phase !is SavePhase.Failed) return
        phase = SavePhase.Editing
        // Its database locked while the sheet showed: the draft goes now.
        if (follower.backToEditing()) editor = null
    }

    private fun VaultEdit.wipe() {
        when (this) {
            is VaultEdit.Update -> changes.wipe()
            is VaultEdit.Create -> fields.wipe()
            is VaultEdit.MoveToRecycleBin, is VaultEdit.Restore -> {}
        }
    }

    private companion object {
        const val TAG = "Save"

        fun tagsOf(vault: Vault): List<String> = SearchFacets.of(vault.entries).tags.sortedByDescending { it.count }.map { it.name }
    }
}
