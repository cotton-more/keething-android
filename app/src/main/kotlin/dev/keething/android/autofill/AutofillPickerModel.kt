package dev.keething.android.autofill

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.list.ListSection
import dev.keething.android.list.RecentEntries
import dev.keething.android.list.VaultListing
import dev.keething.android.list.listingOf
import dev.keething.android.list.sameListing
import dev.keething.android.session.OpenDatabase
import dev.keething.android.ui.CustomIconImages
import dev.keething.domain.vault.Entry
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * and all; typing searches every open database as the list does (keething-spec autofill.fill AC6), each entry by ref.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AutofillPickerModel(
    open: StateFlow<List<OpenDatabase>>,
    recents: RecentEntries,
    entries: AutofillEntries,
    request: AutofillRequest,
) : ViewModel() {
    /** What is typed: not a secret, but kept here as the list keeps its own. */
    val query = TextFieldState()

    private val prepared: StateFlow<PreparedPicker?> = open
        .distinctUntilChanged(::sameListing)
        .mapLatest { databases ->
            if (databases.isEmpty()) return@mapLatest null
            withContext(Dispatchers.Default) {
                val listing = listingOf(databases, Instant.now())
                val matched = entries.candidates(databases.map { OpenVault(it.database.id, it.unlocked.vault) }, request.form)
                    .mapNotNull { listing.entry(it.entry.id) }
                PreparedPicker(listing, matched, CustomIconImages(listing.vault.customIcons))
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
