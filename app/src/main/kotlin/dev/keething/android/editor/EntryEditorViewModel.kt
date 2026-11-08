package dev.keething.android.editor

import dev.keething.android.session.file
import android.net.Uri
import android.util.Log
import dev.keething.android.attachment.NewAttachments
import dev.keething.android.attachment.PickedFile
import dev.keething.domain.secret.Secret
import dev.keething.android.session.SizeTexts
import dev.keething.vault.session.SaveMemory
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.databases.DatabaseMark
import dev.keething.domain.vault.NoteDates
import dev.keething.domain.vault.Vault
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.EntryRef
import kotlinx.coroutines.flow.Flow
import dev.keething.card.editing.AddedCodes
import dev.keething.card.editing.EntryDraft
import dev.keething.card.editing.EntryEditor
import dev.keething.card.editing.GroupChoice
import dev.keething.card.editing.GroupTree
import dev.keething.domain.vault.VaultGroup
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
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

    /** [ref]: the entry in its database (a new one's id); [deletedGroups]: the empty groups a move deleted. */
    data class Saved(val ref: EntryRef, val deletedGroups: List<VaultGroup> = emptyList()) : SavePhase

    data class Failed(val problem: String) : SavePhase
}

/** What a file picked asks before it is attached. */
internal sealed interface AttachQuestion {
    /** Large, or it makes a save's room worse (keething-spec app.large-database AC4): [text] says how and what to do. */
    data class Large(val text: String) : AttachQuestion

    /** The entry has an attachment [name] already: Replace it, or Keep both, the new one as [other]. [inHistory]: the entry's own, kept by its history. */
    data class SameName(val name: String, val other: String, val inHistory: Boolean) : AttachQuestion
}

internal enum class AttachAnswer { ATTACH, REPLACE, KEEP_BOTH, SKIP }

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
@OptIn(ExperimentalCoroutinesApi::class)
internal class EntryEditorViewModel(
    database: DatabaseSession,
    entryId: String?,
    groupId: String?,
    /** Values to start with (a login typed elsewhere, SPEC §7): they count as changes. */
    prefill: ((EntryDraft) -> EntryDraft)? = null,
    private val places: NewEntryPlaces? = null,
    /** A new note's editor, with the text it starts with (SPEC §6.4): it counts as a change. */
    note: NewNote? = null,
    now: Instant = Instant.now(),
    dates: NoteDates = PhoneNoteDates,
) : ViewModel() {
    /** The database the entry is saved into: a new entry's chosen one. */
    var database: DatabaseSession by mutableStateOf(database)
        private set

    /** Null when the entry is gone (deleted elsewhere) or its database locked: the editor closes. */
    var editor: EntryEditor? by mutableStateOf(
        (database.session.state.value as? SessionState.Unlocked)?.vault?.let { vault ->
            when {
                entryId != null -> vault.entries.firstOrNull { it.id == entryId }?.let { EntryEditor.edit(it, now, dates) }
                note != null -> note.editor(vault, groupId, now, dates)
                else -> EntryEditor.create(vault, groupId, now)
            }
        },
    )
        private set

    /** The database's tags, the most used first: the tag field's completions. */
    var knownTags: List<String> by mutableStateOf((database.session.state.value as? SessionState.Unlocked)?.vault?.let(::tagsOf).orEmpty())
        private set

    var draft: EntryDraft by mutableStateOf(
        editor?.start?.let { start -> note?.draft(start, knownTags) ?: prefill?.invoke(start) ?: start } ?: EntryDraft(),
    )
        private set

    init {
        // A save offer's username and password show in a note's editor too (SPEC §6.4).
        if (prefill != null) editor = editor?.given(draft)
    }

    /** The database's groups as they are now (read again after a save or a sync): the group row and its picker. */
    var groups: GroupTree? by mutableStateOf((database.session.state.value as? SessionState.Unlocked)?.vault?.let(GroupTree::of))
        private set

    /** The open databases as a new entry sees them; empty for an entry being edited. */
    private var open: List<NewEntryDatabase> by mutableStateOf(emptyList())

    /** A quick note's editor: its database is the setting's (keething-spec app.quick-note AC1), not chosen here. */
    private val quick = note?.offersSaveInto == false

    /**
     * Save into's choices while two or more are open (keething-spec databases.one-list AC5); empty otherwise, and for
     * a quick note.
     */
    val offered: List<NewEntryDatabase> get() = if (editor?.isNew == true && !quick) NewEntryDatabases.offered(open) else emptyList()

    /** The database chosen, as Save into shows it. */
    val chosen: NewEntryDatabase? get() = open.firstOrNull { it.id == database.id }

    /** Under Save into's choices: "Not open: Family Shared, Crypto". */
    val notOpen: String? get() = places?.let { NewEntryDatabases.notOpen(it.window(), open) }

    var phase: SavePhase by mutableStateOf(SavePhase.Editing)
        private set

    /** Files being read to be attached: Save waits for them. */
    var readingFiles: Boolean by mutableStateOf(false)
        private set

    /** Why the files last picked were not all attached (keething-spec edit.attachments AC2); null when they were. */
    var attachmentProblem: String? by mutableStateOf(null)
        private set

    /** Every file's content read for this editor, removed again or not: wiped when it goes. */
    private val readContents = mutableListOf<Secret>()

    /** What a file read asks before it is attached; null when nothing. */
    var attachQuestion: AttachQuestion? by mutableStateOf(null)
        private set

    private var attachAnswer: CompletableDeferred<AttachAnswer>? = null

    /** Whether the draft stays as the open databases change: its own database locking takes it, a save in flight not. */
    private val follower = DraftFollower()

    init {
        viewModelScope.launch {
            snapshotFlow { this@EntryEditorViewModel.database }
                .flatMapLatest { it.session.state }
                .map { (it as? SessionState.Unlocked)?.vault }
                .distinctUntilChanged { a, b -> a === b }
                .mapLatest { vault -> vault?.let { withContext(Dispatchers.Default) { GroupTree.of(it) } } }
                .collect { tree -> tree?.let { groups = it } }
        }
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

    /** Another group chosen in the picker: a new entry's, or a move of an existing one with the save. */
    fun choose(group: GroupChoice) = update(draft.choosing(group))

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

    /**
     * The files picked attached in their order (keething-spec edit.attachments AC1, AC2), each read by [files] off the main
     * thread; a folder, one too big or one that cannot be read is said instead.
     */
    fun addFiles(uris: List<Uri>, files: NewAttachments) {
        if (uris.isEmpty() || readingFiles || phase != SavePhase.Editing) return
        readingFiles = true
        attachmentProblem = null
        viewModelScope.launch {
            val problems = mutableListOf<String>()
            try {
                for (uri in uris) {
                    when (val picked = withContext(Dispatchers.IO) { files.read(uri) }) {
                        is PickedFile.Read -> {
                            readContents += picked.content
                            attach(picked, files)
                        }
                        is PickedFile.Refused -> problems += picked.problem
                    }
                }
            } finally {
                attachmentProblem = problems.joinToString(" ").ifEmpty { null }
                readingFiles = false
            }
        }
    }

    /**
     * [picked] attached, asked first when it is over 10 MiB or makes the database's room for a save worse, the files
     * already added in this editor counted (keething-spec app.large-database AC4); then, when the entry has an attachment
     * of its name, whether it replaces that one or is kept beside it, numbered (keething-spec edit.attachments AC1; the
     * owner, 2026-11-08). Not attached, its content is wiped.
     */
    private suspend fun attach(picked: PickedFile.Read, files: NewAttachments) {
        val content = picked.content
        val session = database.session
        val added = draft.attachments.sumOf { it.content?.size?.toLong() ?: 0L }
        val bytes = content.size.toLong()
        val before = session.roomWith(added)
        val after = session.roomWith(added + bytes)
        val large = if (before != null && after != null) SaveMemory.question(bytes, before, after) else null
        if (large != null) {
            val text = SizeTexts.largeAttachment(picked.name.ifBlank { "The file" }, files.sizeText(bytes), large, database.file.name)
            if (ask(AttachQuestion.Large(text)) != AttachAnswer.ATTACH) return content.wipe()
        }
        val same = draft.attachmentNamed(picked.name)
        if (same == null) {
            draft = draft.addAttachment(picked.name, content)
            return
        }
        val question = AttachQuestion.SameName(same.name.trim(), draft.freeAttachmentName(picked.name), inHistory = same.original != null)
        draft = when (ask(question)) {
            AttachAnswer.REPLACE -> draft.replaceAttachment(same.id, content)
            AttachAnswer.KEEP_BOTH -> draft.addAttachment(picked.name, content)
            AttachAnswer.ATTACH, AttachAnswer.SKIP -> return content.wipe()
        }
    }

    private suspend fun ask(question: AttachQuestion): AttachAnswer {
        val answer = CompletableDeferred<AttachAnswer>()
        attachAnswer = answer
        attachQuestion = question
        try {
            return answer.await()
        } finally {
            attachQuestion = null
            attachAnswer = null
        }
    }

    /** The answer to [attachQuestion]. */
    fun answer(answer: AttachAnswer) {
        attachAnswer?.complete(answer)
    }

    override fun onCleared() {
        readContents.forEach(Secret::wipe)
        readContents.clear()
    }

    fun save() {
        val editor = editor ?: return
        val database = database
        if (!editor.canSave(draft) || phase == SavePhase.Saving || readingFiles) return
        val edit = editor.edit(draft)
        val pending = database.session.save(edit)
        if (pending == null) {
            edit.wipe()
            phase = SavePhase.Failed(SAVE_LOCKED)
            return
        }
        // The session reads the edit's secrets until the save is over, even when this editor has gone.
        pending.invokeOnCompletion { edit.wipe() }
        phase = SavePhase.Saving
        val started = TimeSource.Monotonic.markNow()
        viewModelScope.launch {
            val outcome = pending.await()
            val how = when (outcome) {
                is SaveOutcome.Saved -> "saved"
                is SaveOutcome.Failed -> "failed"
                SaveOutcome.Interrupted -> "interrupted"
            }
            Log.i(TAG, "$how after ${started.elapsedNow()}")
            val problem = outcome.problem(database.file)
            if (problem == null && editor.isNew) places?.created?.invoke(database.id)
            phase = problem?.let(SavePhase::Failed)
                ?: SavePhase.Saved(EntryRef(database.id, editor.entryId), (outcome as? SaveOutcome.Saved)?.deletedGroups.orEmpty())
        }
    }

    /**
     * Save into: the new entry moves to the open database [id]; its groups, tags and default username become that
     * database's, the group goes to its root and the username to its default unless changed (databases.one-list AC5).
     */
    fun saveInto(id: DatabaseId) {
        val editor = editor ?: return
        if (!editor.isNew || quick || phase != SavePhase.Editing || id == database.id) return
        val target = open.firstOrNull { it.id == id && it.takesEntries } ?: return
        val session = places?.session?.invoke(id)?.takeIf { it.number == target.session } ?: return
        val (moved, movedDraft) = editor.movedTo(target.vault, draft)
        database = session
        follower.moved(session.number)
        this.editor = moved
        draft = movedDraft
        knownTags = tagsOf(target.vault)
        groups = GroupTree.of(target.vault)
    }

    /** Back from a failed save to the draft, to change it or leave. */
    fun keepEditing() {
        if (phase !is SavePhase.Failed) return
        phase = SavePhase.Editing
        // Its database locked while the sheet showed: the draft goes now.
        if (follower.backToEditing()) editor = null
    }

    private fun VaultEdit.EntryEdit.wipe() {
        when (this) {
            is VaultEdit.Update -> changes.wipe()
            is VaultEdit.Create -> fields.wipe()
            is VaultEdit.MoveToRecycleBin, is VaultEdit.Restore, is VaultEdit.DeleteForGood -> {}
        }
    }

    private companion object {
        const val TAG = "Save"

        fun tagsOf(vault: Vault): List<String> = SearchFacets.of(vault.entries).tags.sortedByDescending { it.count }.map { it.name }
    }
}
