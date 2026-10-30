package dev.keething.android.autofill

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.list.ListSection
import dev.keething.android.list.RecentEntries
import dev.keething.android.list.VaultListing
import dev.keething.android.list.ListedDatabase
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.EntryRef
import dev.keething.android.ui.CustomIconImages
import dev.keething.domain.vault.Entry
import dev.keething.vault.session.SessionState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

internal data class PickerUiState(
    val sections: List<ListSection> = emptyList(),
    val icons: CustomIconImages? = null,
    val preparing: Boolean = true,
)

/** A prepared vault for the picker: the list's search and the entries matched to the form. */
private class PreparedPicker(val listing: VaultListing, val suggested: List<Entry>, val icons: CustomIconImages)

/**
 * Autofill's entry picker (SPEC §7): nothing typed shows the matched entries, then the recently used ones
 * and all; typing searches the whole vault as the list does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AutofillPickerModel(
    database: DatabaseSession,
    recents: RecentEntries,
    entries: AutofillEntries,
    request: AutofillRequest,
) : ViewModel() {
    /** What is typed: not a secret, but kept here as the list keeps its own. */
    val query = TextFieldState()

    private val prepared: StateFlow<PreparedPicker?> = database.session.state
        .map { (it as? SessionState.Unlocked)?.vault }
        .distinctUntilChanged { a, b -> a === b }
        .mapLatest { vault ->
            vault?.let {
                withContext(Dispatchers.Default) {
                    // The current database's entries, by ref as Recent keeps them (autofill across databases is plan AD11's).
                    val listing = VaultListing(listOf(ListedDatabase(database.id, it)), Instant.now())
                    val matched = entries.candidates(it, request.form).mapNotNull { c -> listing.entry(EntryRef(database.id, c.entry.id).text) }
                    PreparedPicker(listing, matched, CustomIconImages(listing.vault.customIcons))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<PickerUiState> = combine(prepared, snapshotFlow { query.text.toString() }, recents.ids) { prepared, text, recentIds ->
        Triple(prepared, text, recentIds)
    }
        .mapLatest { (prepared, text, recentIds) ->
            if (prepared == null) return@mapLatest PickerUiState()
            val sections = withContext(Dispatchers.Default) {
                val listed = prepared.listing.sections(text, recentIds)
                if (text.isBlank()) listOfNotNull(prepared.listing.section(ListSection.Kind.SUGGESTED, prepared.suggested)) + listed else listed
            }
            PickerUiState(sections, prepared.icons, preparing = false)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PickerUiState())

    fun entry(id: String): Entry? = prepared.value?.listing?.entry(id)
}
