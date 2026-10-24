package dev.keething.android.list

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.clipboard.SensitiveClipboard
import dev.keething.android.recyclebin.RecycleBinMoves
import dev.keething.android.session.DatabaseSession
import dev.keething.android.ui.CustomIconImages
import dev.keething.android.ui.Notice
import dev.keething.android.unlock.describe
import dev.keething.card.OpenableUrl
import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.vault.Entry
import dev.keething.totp.TotpGenerator
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.session.SessionState
import java.time.Instant
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ListUiState(
    val databaseName: String,
    val entryCount: Int? = null,
    val sections: List<ListSection> = emptyList(),
    val icons: CustomIconImages? = null,
    val conflictCopies: List<ConflictCopy> = emptyList(),
    val reloadFailure: OpenFailure? = null,
    /** Building the search index of a freshly opened vault. */
    val preparing: Boolean = true,
)

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

/** A prepared vault and the icons decoded from it, replaced together when the vault is read again. */
private class Prepared(val listing: VaultListing, val icons: CustomIconImages)

@OptIn(ExperimentalCoroutinesApi::class)
internal class EntryListViewModel(
    private val database: DatabaseSession,
    private val recents: RecentEntries,
    private val recycleBin: RecycleBinMoves,
    private val clipboard: SensitiveClipboard,
    private val query: TextFieldState,
    /** The "Codes" shortcut's list (SPEC §6.2); read in a snapshot, so a change lists again. */
    private val codesOnly: () -> Boolean,
    /** A card open beside the list (two panes); read in a snapshot. */
    private val besideCard: () -> Boolean = { false },
) : ViewModel() {
    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

    private val unlocked: StateFlow<SessionState.Unlocked?> = database.session.state
        .map { it as? SessionState.Unlocked }
        .stateIn(viewModelScope, SharingStarted.Eagerly, database.session.state.value as? SessionState.Unlocked)

    private val prepared: StateFlow<Prepared?> = unlocked
        .map { it?.vault }
        .distinctUntilChanged { a, b -> a === b }
        .mapLatest { vault ->
            vault?.let { withContext(Dispatchers.Default) { Prepared(VaultListing(it, Instant.now()), CustomIconImages(it.customIcons)) } }
        }
        .onEach {
            it?.let { prepared ->
                recents.keepOnly(prepared.listing.ids)
                recycleBin.settled(prepared.listing.ids)
            }
        }
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

    /** The chips over the keyboard for the query (SPEC §6.2); built from the vault the first time they are asked for. */
    val chips: StateFlow<List<SearchChip>> =
        combine(prepared, snapshotFlow { query.text.toString() to codesOnly() }) { prepared, (text, codes) -> Triple(prepared, text, codes) }
            .mapLatest { (prepared, text, codes) ->
                prepared?.let { withContext(Dispatchers.Default) { searchChips(text, it.listing.facets, it.listing.binFacets, codes) } }.orEmpty()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val state: StateFlow<ListUiState> = combine(unlocked, prepared, sections, recycleBin.leaving) { unlocked, prepared, sections, leaving ->
        // A lock wipes the vault at once: nothing of it is shown while the screen fades out. A vault read again
        // (a save, a sync) is listed while the one before stays on screen, so the list keeps its scroll position.
        if (unlocked == null || prepared == null) {
            return@combine ListUiState(database.file.name, preparing = unlocked != null)
        }
        ListUiState(
            databaseName = database.file.name,
            entryCount = prepared.listing.vault.entries.size,
            sections = sections.without(leaving),
            icons = prepared.icons,
            conflictCopies = unlocked.conflictCopies,
            reloadFailure = unlocked.reloadFailure,
            preparing = false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ListUiState(database.file.name))

    init {
        // The list is new after every unlock: say how long the key derivation took (measured on the phone).
        viewModelScope.launch {
            unlocked.first { it != null }?.keyDerivation?.let { mutableNotices.send(Notice("Unlocked · key derivation ${it.seconds()}")) }
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
        val vault = unlocked.value?.vault ?: return null
        val inBin = vault.entries.none { it.id == row.id }
        val entry = entry(row.id) ?: return null
        val url = OpenableUrl.of(entry.url)
        return QuickActions(row.id, row, entry.username, url, url?.let { entry.urlHost ?: it }, inBin)
    }

    /** Delete from the quick actions: as the card's (SPEC §6.2), into the bin at once, with Undo. */
    fun moveToRecycleBin(id: String) {
        val entry = unlocked.value?.vault?.entries?.firstOrNull { it.id == id } ?: return
        recycleBin.moveToRecycleBin(database, entry)
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

    /** Shown for the reload failure banner. */
    fun describe(failure: OpenFailure): String = failure.describe(database.file)

    private fun entry(id: String): Entry? {
        val vault = unlocked.value?.vault ?: return null
        return vault.entries.firstOrNull { it.id == id } ?: vault.recycleBin.firstOrNull { it.id == id }
    }

    private fun used(entry: Entry) {
        val inRecycleBin = unlocked.value?.vault?.recycleBin.orEmpty().any { it.id == entry.id }
        if (!inRecycleBin) recents.note(entry.id)
    }

    private fun notice(notice: Notice) {
        mutableNotices.trySend(notice)
    }
}

/** `1.4 s`, `850 ms`, the unit kept with its number on a line. */
internal fun Duration.seconds(): String =
    if (inWholeMilliseconds < 1_000) "$inWholeMilliseconds\u00A0ms" else "%.1f\u00A0s".format(inWholeMilliseconds / 1_000.0)
