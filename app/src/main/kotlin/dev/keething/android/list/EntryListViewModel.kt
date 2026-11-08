package dev.keething.android.list

import dev.keething.domain.storage.StoppedSave
import dev.keething.android.session.file
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.clipboard.SensitiveClipboard
import dev.keething.android.recyclebin.RecycleBinMoves
import dev.keething.android.databases.DatabaseText
import dev.keething.android.editor.unsavedText
import dev.keething.android.editor.SAVE_LOCKED
import dev.keething.android.editor.problem
import dev.keething.card.editing.CodeEdits
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.VaultEdit
import dev.keething.vault.databases.DatabaseSession
import dev.keething.android.session.OpenDatabase
import dev.keething.android.session.SizeTexts
import dev.keething.vault.databases.EntryRef
import dev.keething.vault.databases.OpenEntry
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.SessionsState
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SaveRoom
import dev.keething.vault.session.VaultSummary
import dev.keething.vault.session.SessionState
import dev.keething.android.ui.CustomIconImages
import dev.keething.android.ui.Notice
import dev.keething.android.unlock.describe
import dev.keething.card.OpenableUrl
import dev.keething.domain.vault.Entry
import dev.keething.totp.TotpGenerator
import dev.keething.vault.databases.Databases
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

internal data class ListUiState(
    /** The header: the open database's name (SPEC §3.1), or the open ones' labels joined ("Personal and Work"). */
    val databaseName: String,
    val entryCount: Int? = null,
    val sections: List<ListSection> = emptyList(),
    /** The Codes view's tiles, in its place of the sections (keething-spec app.codes-view). */
    val codes: List<CodeTile> = emptyList(),
    val icons: CustomIconImages? = null,
    /** Syncthing's conflict copies and a failed reload, each open database's. */
    val banners: List<ListBanner> = emptyList(),
    /** Building the search index of a freshly opened vault. */
    val preparing: Boolean = true,
)

/** A warning over the list about one open database; [action]: a button that does something about it, by its label. */
internal data class ListBanner(
    val key: String,
    val title: String,
    val text: String,
    val onDismiss: (() -> Unit)? = null,
    val action: Pair<String, () -> Unit>? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is ListBanner && other.key == key && other.title == title && other.text == text && other.action?.first == action?.first

    override fun hashCode(): Int = listOf(key, title, text, action?.first).hashCode()
}

/** What a long press on a row offers (SPEC §6.2), read from the entry when the sheet opens. */
internal data class QuickActions(
    val id: String,
    val row: EntryRow,
    val username: String,
    /** The site to open, as the card's link would (`OpenableUrl`); null when there is none. */
    val url: String?,
    val host: String?,
    val inRecycleBin: Boolean,
)

/** What the sections are listed from. */
private data class Listed(val prepared: Prepared?, val text: String, val codesOnly: Boolean, val recentIds: List<String>)

/** What the list shows: the sections, or the Codes view's tiles. */
private data class Shown(val sections: List<ListSection> = emptyList(), val codes: List<CodeTile> = emptyList())

/** The open databases prepared and the icons decoded from them, replaced together when one is read again. */
private class Prepared(val listing: VaultListing, val icons: CustomIconImages)

/**
 * The list over the open databases (plan AD9; keething-spec databases.one-list): one search and one Recent, each row its
 * own database's entry by its ref, saved through that database's session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class EntryListViewModel(
    /** The open databases, as [dev.keething.android.session.Sessions.open]. */
    private val open: StateFlow<List<OpenDatabase>>,
    /** The app's state: nothing open hides the list at once. */
    private val app: StateFlow<SessionsState>,
    /** An entry in its own open database, with that database's session. */
    private val openEntry: (EntryRef) -> OpenEntry?,
    private val recents: RecentEntries,
    /** The unlocks already told, the app's. */
    private val toldUnlocks: ToldUnlocks,
    private val recycleBin: RecycleBinMoves,
    private val clipboard: SensitiveClipboard,
    private val query: TextFieldState,
    /** The Codes view (keething-spec app.codes-view); read in a snapshot, so a change lists again. */
    private val codesOnly: () -> Boolean,
    /** A card open beside the list (two panes); read in a snapshot. */
    private val besideCard: () -> Boolean = { false },
    /** The list of databases: the current one's name before its vault is listed. */
    databases: StateFlow<Databases> = MutableStateFlow(Databases(emptyList(), null)),
    /** A new entry was saved into this database (a code added, keething-spec app.codes-view AC7): the next one starts there. */
    private val newEntryMade: (DatabaseId) -> Unit = {},
) : ViewModel() {
    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

    private val mutableConflict = MutableStateFlow<ConflictCopyModel?>(null)

    /** The conflict copy being resolved, its sheet's (keething-spec kdbx.conflict-copies AC6); null when none. */
    val conflict: StateFlow<ConflictCopyModel?> = mutableConflict.asStateFlow()

    private val prepared: StateFlow<Prepared?> = open
        .distinctUntilChanged(::sameListing)
        .mapLatest { databases ->
            if (databases.isEmpty()) return@mapLatest null
            withContext(Dispatchers.Default) {
                val started = TimeSource.Monotonic.markNow()
                val listing = listingOf(databases, Instant.now())
                Prepared(listing, CustomIconImages(listing.vault.customIcons))
                    // Times and counts only (keething-spec app.large-database).
                    .also { Log.i(TAG, "listing prepared in ${started.elapsedNow().inWholeMilliseconds} ms: ${listing.ids.size} entries") }
            }
        }
        .onEach { it?.let { prepared -> recycleBin.settled(prepared.listing.ids) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Recent as listed: beside a card its order holds, so that the row just tapped does not jump to the top under the
     * finger (opening a card makes it recent); an entry taken off Recent still leaves. It catches up when the card closes.
     */
    private var recentShown = recents.ids.value
    private val recentIds: Flow<List<String>> =
        combine(recents.ids, snapshotFlow { besideCard() }) { ids, holding ->
            if (holding && recentShown.isNotEmpty()) recentShown.filter { it in ids } else ids
        }.onEach { recentShown = it }

    private val shown: StateFlow<Shown> =
        combine(prepared, snapshotFlow { query.text.toString() to codesOnly() }, recentIds) { prepared, (text, codes), recentIds ->
            Listed(prepared, text, codes, recentIds)
        }
            .mapLatest { (prepared, text, codes, recentIds) ->
                val listing = prepared?.listing ?: return@mapLatest Shown()
                withContext(Dispatchers.Default) {
                    val started = TimeSource.Monotonic.markNow()
                    (if (codes) Shown(codes = listing.codes(text)) else Shown(listing.sections(text, recentIds))).also { shown ->
                        val rows = shown.codes.size + shown.sections.sumOf { it.rows.size }
                        Log.i(TAG, "search of ${text.length} characters in ${started.elapsedNow().inWholeMilliseconds} ms: $rows rows")
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Shown())

    /** The chips over the keyboard for the query (SPEC §6.2): the filters, the names a filter can be, or narrowing. */
    val chips: StateFlow<ChipLine> =
        combine(prepared, snapshotFlow { query.text.toString() to codesOnly() }) { prepared, (text, codes) -> Triple(prepared, text, codes) }
            .mapLatest { (prepared, text, codes) ->
                prepared?.let { withContext(Dispatchers.Default) { searchChips(text, it.listing, codes) } } ?: ChipLine(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ChipLine(emptyList()))

    private val names: Flow<String> = combine(open, databases) { open, databases ->
        when {
            open.isEmpty() -> databases.current?.displayName.orEmpty()
            open.size == 1 -> open.single().mark.label.name
            else -> DatabaseText.joined(open.map { it.mark.label.full })
        }
    }.distinctUntilChanged()

    val state: StateFlow<ListUiState> = combine(combine(app, open, ::Pair), prepared, shown, recycleBin.leaving, names) { (app, open), prepared, shown, leaving, name ->
        // A lock wipes the vaults at once: nothing of them is shown while the screen fades out, nor the rows of one
        // that locked alone while the others are listed again. A vault read again (a save, a sync) is listed while the
        // one before stays on screen, so the list keeps its scroll position.
        if (app !is SessionsState.Open || prepared == null) {
            return@combine ListUiState(name, preparing = app is SessionsState.Open)
        }
        val openIds = app.vaults.mapTo(HashSet()) { it.id }
        ListUiState(
            databaseName = name,
            entryCount = prepared.listing.vault.entries.size,
            sections = shown.sections.without(leaving).onlyOf(openIds),
            codes = shown.codes.filter { it.entryId !in leaving && EntryRef.of(it.entryId).database in openIds },
            icons = prepared.icons,
            banners = banners(open),
            preparing = false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ListUiState(databases.value.current?.displayName.orEmpty()))

    /**
     * Each open database's size against the memory a save needs, its conflict copies and failed reload, named while two
     * or more are open.
     */
    private fun banners(open: List<OpenDatabase>): List<ListBanner> = open.flatMap { database ->
        val file = database.database.file
        val named = open.size >= 2
        val unlocked = database.unlocked
        listOfNotNull(
            // keething-spec app.large-database AC3: read-only while it is shown; near the limit once an unlock, until OK.
            when (val room = unlocked.room) {
                is SaveRoom.TooLarge -> ListBanner(
                    key = "too-large/${database.database.id}",
                    title = if (named) "${database.mark.label.name}: read only, too large for this phone" else "Read only: too large for this phone",
                    text = SizeTexts.tooLarge(file.name, unlocked.size, room.alone),
                )
                SaveRoom.Near -> if (unlocked.changeableAgain) null else ListBanner(
                    key = "near-limit/${database.database.id}",
                    title = if (named) "${database.mark.label.name}: near this phone's limit" else "Near this phone's limit",
                    text = SizeTexts.nearLimit(file.name, unlocked.size),
                    onDismiss = database.database.session::nearLimitSaid,
                )
                SaveRoom.Enough -> null
            },
            // keething-spec app.large-database AC3: one read-only beside others became changeable once they locked.
            if (unlocked.changeableAgain) {
                ListBanner(
                    key = "changeable-again/${database.database.id}",
                    title = if (named) "${database.mark.label.name}: can be changed again" else "Can be changed again",
                    text = SizeTexts.changeableAgain(file.name, near = unlocked.room == SaveRoom.Near),
                    onDismiss = {
                        database.database.session.changeableAgainSaid()
                        database.database.session.nearLimitSaid()
                    },
                )
            } else {
                null
            },
            // keething-spec kdbx.conflict-copies AC2, AC6: the newest copy, resolved here without a merge.
            database.unlocked.conflictCopies.firstOrNull()?.let { newest ->
                ListBanner(
                    key = "conflict/${database.database.id}",
                    title = if (named) "${database.mark.label.name}: Syncthing found two versions" else "Syncthing found two versions",
                    text = "Open: ${file.name}. The other one is from ${newest.createdAt.format(CONFLICT_TIME)} " +
                        "(device ${newest.deviceId}). Keep one of them here, or merge them in KeePassXC (Database › Merge).",
                    action = "Resolve…" to { resolveConflict(database.database.id) },
                )
            },
            // keething-spec databases.saf AC5: which way, said until OK.
            database.unlocked.stoppedSave?.let { stopped ->
                ListBanner(
                    key = "stopped-save/${database.database.id}",
                    title = if (named) "${database.mark.label.name}: the last save was put right" else "The last save was put right",
                    text = "Keething was closed while it saved ${file.name}. " + when (stopped) {
                        StoppedSave.FINISHED -> "The save was finished: the file has the change."
                        StoppedSave.UNDONE -> "It could not be finished, so the file is as it was before (or as a sync made it since): make the change again if it is missing."
                    },
                    onDismiss = database.database.session::stoppedSaveSaid,
                )
            },
            // keething-spec kdbx.hardening AC3: what a lock kept out of the file, said until OK.
            unsavedText(database.unlocked.unsaved, database.unlocked.vault)?.let { text ->
                ListBanner(
                    key = "unsaved/${database.database.id}",
                    title = if (named) "${database.mark.label.name}: not saved" else "Not saved",
                    text = text,
                    onDismiss = database.database.session::unsavedSaid,
                )
            },
            database.unlocked.reloadFailure?.let { failure ->
                ListBanner(
                    key = "reload/${database.database.id}",
                    title = if (named) "${database.mark.label.name}: showing the version read before" else "Showing the version read before",
                    text = failure.describe(file.path),
                )
            },
        )
    }

    init {
        // Each unlock (its key derived: not a reload) says once how long the key derivation took (measured on the phone),
        // also when the list is built again over a database still open ([toldUnlocks]). One whose password was changed
        // elsewhere locks alone, the others staying open: it says so.
        viewModelScope.launch {
            var listed: List<OpenDatabase> = emptyList()
            open.collect { list ->
                // The copy resolved elsewhere, synced away, or its database locked: its sheet goes.
                mutableConflict.value?.let { sheet ->
                    if (list.none { database -> database.unlocked.conflictCopies.any { it.fileName == sheet.copy.fileName && database.database.file == sheet.file } }) {
                        closeConflict()
                    }
                }
                for (gone in listed) {
                    if (list.isEmpty() || list.any { it.database === gone.database }) continue
                    val locked = gone.database.session.state.value as? SessionState.Locked ?: continue
                    if (locked.reason != LockReason.PasswordChanged) continue
                    mutableNotices.send(Notice("${gone.mark.label.full} locked: its password was changed elsewhere", isProblem = true))
                }
                listed = list
                for (database in list) {
                    val derivation = database.unlocked.keyDerivation ?: continue
                    if (!toldUnlocks.firstTime(ToldUnlocks.key(database.database.number, database.database.session.locks))) continue
                    val what = if (list.size >= 2) "${database.mark.label.name} unlocked" else "Unlocked"
                    mutableNotices.send(Notice("$what · key derivation ${derivation.seconds()}"))
                }
            }
        }
    }

    /** Resolve… on [id]'s conflict banner: the sheet for its newest copy (keething-spec kdbx.conflict-copies AC6). */
    fun resolveConflict(id: DatabaseId) {
        val database = open.value.firstOrNull { it.database.id == id } ?: return
        val copy = database.unlocked.conflictCopies.firstOrNull() ?: return
        val session = database.database.session
        val unlocked = database.unlocked
        mutableConflict.value = ConflictCopyModel(
            file = database.database.file,
            copy = copy,
            open = VaultSummary.of(unlocked.vault),
            replaceRefused = when {
                unlocked.viewOnly -> "This database is KDBX 3.1, viewed only: the other version can only be deleted here."
                unlocked.room is SaveRoom.TooLarge -> "This database is read only on this phone: the other version can only be deleted here."
                else -> null
            },
            contents = { session.copyContents(copy) },
            take = { session.takeConflictCopy(copy) },
            remove = { session.removeConflictCopy(copy) },
            scope = viewModelScope,
            onDone = { said ->
                closeConflict()
                notice(said)
            },
        )
    }

    /** The conflict sheet closed (not while a choice is carried out: it goes on, and says how it went). */
    fun closeConflict() {
        mutableConflict.value = null
    }

    /** A row swiped right, or its quick actions (SPEC §6.2). */
    fun copyPassword(id: String) {
        val entry = entry(id) ?: return
        if (entry.password.isEmpty()) return notice(Notice("No password to copy", isProblem = true))
        clipboard.copy(entry.password.reveal(), sensitive = true)
        used(entry)
        notice(Notice.copied("Password"))
    }

    /** A row swiped left, or its quick actions (SPEC §6.2). */
    fun copyUsername(id: String) {
        val entry = entry(id) ?: return
        if (entry.username.isEmpty()) return notice(Notice("No username to copy", isProblem = true))
        clipboard.copy(entry.username, sensitive = false)
        used(entry)
        notice(Notice.copied("Username"))
    }

    /** A password made by the generator, without an entry (SPEC §6.2). */
    fun copyGenerated(password: String) {
        clipboard.copy(password, sensitive = true)
        notice(Notice.copied("Password"))
    }

    /** A tile of the Codes view, or Copy code in its sheet (keething-spec app.codes-view AC2): as any code copy. */
    fun copyCode(tile: CodeTile) {
        val entry = entry(tile.entryId) ?: return
        clipboard.copy(TotpGenerator.code(tile.totp, Instant.now()), sensitive = true)
        used(entry)
        notice(Notice.copied(if (tile.further) tile.subtitle else "One-time code"))
    }

    /** The code button of a row. */
    fun copyCode(id: String) {
        val entry = entry(id) ?: return
        val config = entry.totp ?: return
        clipboard.copy(TotpGenerator.code(config, Instant.now()), sensitive = true)
        used(entry)
        notice(Notice.copied("One-time code"))
    }

    /**
     * Whether deleting [tile]'s code moves its entry to the recycle bin instead (keething-spec app.codes-view AC6): the
     * code is all it holds but its title.
     */
    fun deletesEntry(tile: CodeTile): Boolean = openEntry(EntryRef.of(tile.entryId))?.entry?.let { CodeEdits.isAllItHolds(it, tile.fieldName) } == true

    /**
     * Edit code (AC4): [tile]'s code becomes [config], a further one renamed [name]; saved as any edit, the old version
     * into the history. Why it cannot be saved as asked (a name taken), or null once the save is under way.
     */
    fun editCode(tile: CodeTile, config: TotpConfig, name: String?): String? {
        val found = openEntry(EntryRef.of(tile.entryId)) ?: return SAVE_LOCKED
        val field = tile.fieldName
        if (field != null && name != null) CodeEdits.nameProblem(found.entry, field, name)?.let { return it }
        val what = if (field == null) "One-time code" else "“${name?.trim()?.ifEmpty { null } ?: field}”"
        save(found.database, VaultEdit.Update(found.entry.id, found.entry.lastModified, CodeEdits.edit(found.entry, field, config, name))) { problem ->
            notice(problem?.let { Notice(it, isProblem = true) } ?: Notice("$what changed in ${found.entry.displayTitle}"))
        }
        return null
    }

    /**
     * Delete from the entry (AC5): [tile]'s code goes from its entry and nothing else; the notice names them and offers
     * Undo, which puts the fields back as they were. The code all its entry holds: the entry to the recycle bin (AC6).
     */
    fun deleteCode(tile: CodeTile) {
        val found = openEntry(EntryRef.of(tile.entryId))?.takeIf { !it.inRecycleBin } ?: return
        val before = found.entry
        val field = tile.fieldName
        if (CodeEdits.isAllItHolds(before, field)) return recycleBin.moveToRecycleBin(found.database, before)
        val title = before.displayTitle
        save(found.database, VaultEdit.Update(before.id, before.lastModified, CodeEdits.delete(before, field))) { problem ->
            val text = if (field == null) "One-time code deleted from $title" else "“${tile.subtitle}” deleted from $title"
            notice(problem?.let { Notice(it, isProblem = true) } ?: Notice(text, undo = { restoreCode(tile, before) }))
        }
    }

    /** Undo of [deleteCode]: the fields as they were in [before], into the entry as it is now. */
    private fun restoreCode(tile: CodeTile, before: Entry) {
        val current = openEntry(EntryRef.of(tile.entryId)) ?: return notice(Notice(SAVE_LOCKED, isProblem = true))
        val changes = CodeEdits.restore(before, current.entry, tile.fieldName)
        save(current.database, VaultEdit.Update(current.entry.id, current.entry.lastModified, changes)) { problem ->
            problem?.let { notice(Notice(it, isProblem = true)) }
        }
    }

    /**
     * Add a code (AC7): into [into] of [database] (its own code when it has none, else the next "One-time code N"), or a
     * new entry [title] with [username] in the database's root group.
     */
    fun addCode(database: DatabaseSession, into: Entry?, title: String, username: String, config: TotpConfig) {
        if (into != null) {
            val (changes, field) = CodeEdits.addTo(into, config)
            save(database, VaultEdit.Update(into.id, into.lastModified, changes)) { problem ->
                val what = field?.let { "“$it”" } ?: "One-time code"
                notice(problem?.let { Notice(it, isProblem = true) } ?: Notice("$what added to ${into.displayTitle}"))
            }
            return
        }
        val vault = (database.session.state.value as? SessionState.Unlocked)?.vault ?: return notice(Notice(SAVE_LOCKED, isProblem = true))
        val root = vault.groups.firstOrNull { it.isRoot } ?: return
        val changes = CodeEdits.newEntry(title, username, config)
        save(database, VaultEdit.Create(Entry.newId(), GroupDestination(root.id), changes)) { problem ->
            if (problem == null) newEntryMade(database.id)
            notice(problem?.let { Notice(it, isProblem = true) } ?: Notice("“${changes.title.orEmpty().ifEmpty { "(Untitled)" }}” added with its code"))
        }
    }

    /** [edit] saved in [database]; [done] gets why it failed, or null. The change's secrets are wiped once it is over. */
    private fun save(database: DatabaseSession, edit: VaultEdit, done: (problem: String?) -> Unit) {
        val changes = when (edit) {
            is VaultEdit.Update -> edit.changes
            is VaultEdit.Create -> edit.fields
            else -> null
        }
        val pending = database.session.save(edit)
        if (pending == null) {
            changes?.wipe()
            return done(SAVE_LOCKED)
        }
        pending.invokeOnCompletion { changes?.wipe() }
        viewModelScope.launch { done(pending.await().problem(database.file)) }
    }

    /** The quick actions of a row; null when the entry has gone (deleted elsewhere, or locked). */
    fun quickActions(row: EntryRow): QuickActions? {
        val listing = prepared.value?.listing ?: return null
        val entry = entry(row.id) ?: return null
        val url = OpenableUrl.of(entry.url)
        return QuickActions(row.id, row, entry.username, url, url?.let { entry.urlHost ?: it }, listing.inRecycleBin(row.id))
    }

    /** Delete from the quick actions: as the card's (SPEC §6.2), into its own database's bin at once, with Undo. */
    fun moveToRecycleBin(id: String) {
        val found = openEntry(EntryRef.of(id))?.takeIf { !it.inRecycleBin } ?: return
        recycleBin.moveToRecycleBin(found.database, found.entry)
    }

    /** Restore from the quick actions (SPEC §6.2): an entry in its database's bin, back where it came from. */
    fun restore(id: String) {
        val found = openEntry(EntryRef.of(id))?.takeIf { it.inRecycleBin } ?: return
        recycleBin.restoreSaying(found.database, found.entry.id)
    }

    /** Delete for good from the quick actions, once asked (keething-spec edit.delete-for-good): only an entry in its database's bin. */
    fun deleteForGood(id: String) {
        val found = openEntry(EntryRef.of(id))?.takeIf { it.inRecycleBin } ?: return
        recycleBin.deleteForGood(found.database, found.entry)
    }

    /** Opening the site from the quick actions counts as using the entry, as copying does. */
    fun opened(id: String) {
        entry(id)?.let(::used)
    }

    /**
     * "Remove from Recent" in a recent row's quick actions (SPEC §6.2): off the list, with Undo; the entry stays in the database. A row no
     * longer on the list says nothing, so a notice with a working Undo is never replaced by one without.
     */
    fun removeFromRecent(id: String) {
        val undo = recents.remove(id) ?: return
        notice(Notice("Removed from Recent", undo = undo))
    }

    /** The entry of a row, a copy whose id is its ref; null once its database is not open. */
    private fun entry(id: String): Entry? {
        val database = EntryRef.of(id).database
        if ((app.value as? SessionsState.Open)?.vaults?.any { it.id == database } != true) return null
        return prepared.value?.listing?.entry(id)
    }

    private fun used(entry: Entry) {
        if (prepared.value?.listing?.inRecycleBin(entry.id) == false) recents.note(entry.id)
    }

    private fun notice(notice: Notice) {
        mutableNotices.trySend(notice)
    }
}

private val CONFLICT_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** `1.4 s`, `850 ms`, the unit kept with its number on a line. */
internal fun Duration.seconds(): String =
    if (inWholeMilliseconds < 1_000) "$inWholeMilliseconds\u00A0ms" else "%.1f\u00A0s".format(inWholeMilliseconds / 1_000.0)

private const val TAG = "List"
