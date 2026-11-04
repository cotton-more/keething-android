package dev.keething.android.list

import dev.keething.domain.storage.StoppedSave
import dev.keething.android.session.file
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.clipboard.SensitiveClipboard
import dev.keething.android.recyclebin.RecycleBinMoves
import dev.keething.android.databases.DatabaseText
import dev.keething.android.editor.unsavedText
import dev.keething.android.session.OpenDatabase
import dev.keething.vault.databases.EntryRef
import dev.keething.vault.databases.OpenEntry
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.SessionsState
import dev.keething.vault.session.LockReason
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ListUiState(
    /** The header: the open database's name (SPEC §3.1), or the open ones' labels joined ("Personal and Work"). */
    val databaseName: String,
    val entryCount: Int? = null,
    val sections: List<ListSection> = emptyList(),
    val icons: CustomIconImages? = null,
    /** Syncthing's conflict copies and a failed reload, each open database's. */
    val banners: List<ListBanner> = emptyList(),
    /** Building the search index of a freshly opened vault. */
    val preparing: Boolean = true,
)

/** A warning over the list about one open database. */
internal data class ListBanner(val key: String, val title: String, val text: String, val onDismiss: (() -> Unit)? = null) {
    override fun equals(other: Any?): Boolean = other is ListBanner && other.key == key && other.title == title && other.text == text

    override fun hashCode(): Int = listOf(key, title, text).hashCode()
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
    /** The "Codes" shortcut's list (SPEC §6.2); read in a snapshot, so a change lists again. */
    private val codesOnly: () -> Boolean,
    /** A card open beside the list (two panes); read in a snapshot. */
    private val besideCard: () -> Boolean = { false },
    /** The list of databases: the current one's name before its vault is listed. */
    databases: StateFlow<Databases> = MutableStateFlow(Databases(emptyList(), null)),
) : ViewModel() {
    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

    private val prepared: StateFlow<Prepared?> = open
        .distinctUntilChanged(::sameListing)
        .mapLatest { databases ->
            if (databases.isEmpty()) return@mapLatest null
            withContext(Dispatchers.Default) {
                val listing = listingOf(databases, Instant.now())
                Prepared(listing, CustomIconImages(listing.vault.customIcons))
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

    private val sections: StateFlow<List<ListSection>> =
        combine(prepared, snapshotFlow { query.text.toString() to codesOnly() }, recentIds) { prepared, (text, codes), recentIds ->
            Listed(prepared, text, codes, recentIds)
        }
            .mapLatest { (prepared, text, codes, recentIds) ->
                prepared?.let { withContext(Dispatchers.Default) { it.listing.sections(text, recentIds, codes) } }.orEmpty()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    val state: StateFlow<ListUiState> = combine(combine(app, open, ::Pair), prepared, sections, recycleBin.leaving, names) { (app, open), prepared, sections, leaving, name ->
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
            sections = sections.without(leaving).onlyOf(openIds),
            icons = prepared.icons,
            banners = banners(open),
            preparing = false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ListUiState(databases.value.current?.displayName.orEmpty()))

    /** Each open database's conflict copies and failed reload, named while two or more are open. */
    private fun banners(open: List<OpenDatabase>): List<ListBanner> = open.flatMap { database ->
        val file = database.database.file
        val named = open.size >= 2
        listOfNotNull(
            database.unlocked.conflictCopies.firstOrNull()?.let { newest ->
                ListBanner(
                    key = "conflict/${database.database.id}",
                    title = "Syncthing found two versions",
                    text = "Open: ${file.name}. The other one is from ${newest.createdAt.format(CONFLICT_TIME)} " +
                        "(device ${newest.deviceId}) — merge it in KeePassXC (Database › Merge), then delete it.",
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

    /** The code button of a row. */
    fun copyCode(id: String) {
        val entry = entry(id) ?: return
        val config = entry.totp ?: return
        clipboard.copy(TotpGenerator.code(config, Instant.now()), sensitive = true)
        used(entry)
        notice(Notice.copied("One-time code"))
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
