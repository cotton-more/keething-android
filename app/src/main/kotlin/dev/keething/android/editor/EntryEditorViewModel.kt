package dev.keething.android.editor

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.vault.databases.DatabaseSession
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

    data class Saved(val entryId: String) : SavePhase

    data class Failed(val problem: String) : SavePhase
}

/**
 * The editor of an entry, or of a new one ([entryId] null) in [groupId] (SPEC §6.2; Mac `EntryEditorModel`): the
 * draft as typed, and its save through the session (SPEC §3.3). While it is open the values are plain strings,
 * as the text fields hold them; they go with the editor (and a lock closes it).
 */
internal class EntryEditorViewModel(
    private val database: DatabaseSession,
    entryId: String?,
    groupId: String?,
    /** Values to start with (a login typed elsewhere, SPEC §7): they count as changes. */
    prefill: ((EntryDraft) -> EntryDraft)? = null,
    now: Instant = Instant.now(),
) : ViewModel() {
    /** Null when the entry is gone (deleted elsewhere) or the vault is locked: the editor closes. */
    val editor: EntryEditor? = (database.session.state.value as? SessionState.Unlocked)?.vault?.let { vault ->
        if (entryId == null) EntryEditor.create(vault, groupId, now) else vault.entries.firstOrNull { it.id == entryId }?.let { EntryEditor.edit(it, now) }
    }

    /** The vault's tags, the most used first: the tag field's completions. */
    val knownTags: List<String> = (database.session.state.value as? SessionState.Unlocked)?.vault?.let { vault ->
        SearchFacets.of(vault.entries).tags.sortedByDescending { it.count }.map { it.name }
    }.orEmpty()

    var draft: EntryDraft by mutableStateOf(editor?.start?.let { prefill?.invoke(it) ?: it } ?: EntryDraft())
        private set

    var phase: SavePhase by mutableStateOf(SavePhase.Editing)
        private set

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
            phase = outcome.problem(database.file)?.let(SavePhase::Failed) ?: SavePhase.Saved(editor.entryId)
        }
    }

    /** Back from a failed save to the draft, to change it or leave. */
    fun keepEditing() {
        if (phase is SavePhase.Failed) phase = SavePhase.Editing
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
    }
}
