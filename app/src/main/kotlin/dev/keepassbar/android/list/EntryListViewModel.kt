package dev.keepassbar.android.list

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keepassbar.android.clipboard.SensitiveClipboard
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.ui.CustomIconImages
import dev.keepassbar.android.ui.Notice
import dev.keepassbar.android.unlock.describe
import dev.keepassbar.domain.storage.ConflictCopy
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.totp.TotpGenerator
import dev.keepassbar.vault.opening.OpenFailure
import dev.keepassbar.vault.session.SessionState
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

/** What the sections are listed from. */
private data class Listed(val prepared: Prepared?, val text: String, val codesOnly: Boolean, val recentIds: List<String>)

/** A prepared vault and the icons decoded from it, replaced together when the vault is read again. */
private class Prepared(val listing: VaultListing, val icons: CustomIconImages)

@OptIn(ExperimentalCoroutinesApi::class)
internal class EntryListViewModel(
    private val database: DatabaseSession,
    private val recents: RecentEntries,
    private val clipboard: SensitiveClipboard,
    private val query: TextFieldState,
    /** The "Codes" shortcut's list (SPEC §6.2); read in a snapshot, so a change lists again. */
    private val codesOnly: () -> Boolean,
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
        .onEach { it?.let { prepared -> recents.keepOnly(prepared.listing.ids) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val sections: StateFlow<List<ListSection>> =
        combine(prepared, snapshotFlow { query.text.toString() to codesOnly() }, recents.ids) { prepared, (text, codes), recentIds ->
            Listed(prepared, text, codes, recentIds)
        }
            .mapLatest { (prepared, text, codes, recentIds) ->
                prepared?.let { withContext(Dispatchers.Default) { it.listing.sections(text, recentIds, codes) } }.orEmpty()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<ListUiState> = combine(unlocked, prepared, sections) { unlocked, prepared, sections ->
        // A lock wipes the vault at once: nothing of it is shown while the screen fades out.
        if (unlocked == null || prepared == null || prepared.listing.vault !== unlocked.vault) {
            return@combine ListUiState(database.file.name, preparing = unlocked != null)
        }
        ListUiState(
            databaseName = database.file.name,
            entryCount = unlocked.vault.entries.size,
            sections = sections,
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

    /** A long press on a row (SPEC §6.2). */
    fun copyPassword(id: String) {
        val entry = entry(id) ?: return
        if (entry.password.isEmpty()) return notice(Notice("No password to copy", isProblem = true))
        clipboard.copy(entry.password.reveal(), sensitive = true)
        used(entry)
        notice(Notice.copied("Password"))
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

/** `1.4 s`, `850 ms`. */
internal fun Duration.seconds(): String =
    if (inWholeMilliseconds < 1_000) "$inWholeMilliseconds ms" else "%.1f s".format(inWholeMilliseconds / 1_000.0)
