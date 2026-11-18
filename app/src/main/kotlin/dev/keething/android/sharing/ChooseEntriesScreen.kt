package dev.keething.android.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.AppContainer
import dev.keething.android.R
import dev.keething.android.list.RowSubtitle
import dev.keething.android.list.EntryRow
import dev.keething.android.list.ListSection
import dev.keething.android.list.RecentEntries
import dev.keething.android.list.SearchField
import dev.keething.android.list.SectionTitle
import dev.keething.android.list.VaultListing
import dev.keething.android.list.title
import dev.keething.android.list.listingOf
import dev.keething.android.list.sameListing
import dev.keething.android.session.OpenDatabase
import dev.keething.android.ui.resolve
import dev.keething.search.EntrySearch
import dev.keething.vault.databases.EntryRef
import kotlinx.coroutines.launch
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.BarTitle
import dev.keething.android.ui.CustomIconImages
import dev.keething.android.ui.EntryIcon
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.segmentShape
import dev.keething.sharing.payload.AnswerPayload
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

internal data class ChooseUiState(val sections: List<ListSection> = emptyList(), val icons: CustomIconImages? = null, val preparing: Boolean = true)

/**
 * The open databases searched as the list searches them, and the entries chosen so far (refs only, they survive a
 * rotation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChooseEntriesModel(open: StateFlow<List<OpenDatabase>>, recents: RecentEntries, preselected: List<String>) : ViewModel() {
    val query = TextFieldState()
    val chosen = mutableStateListOf<String>().apply { addAll(preselected) }

    init {
        // A chosen entry's database locked meanwhile: it is not chosen any more (its row has gone, it could not be sent).
        viewModelScope.launch {
            open.collect { databases ->
                if (databases.isEmpty()) return@collect
                chosen.retainAll { ref -> databases.any { it.database.id == EntryRef.of(ref).database } }
            }
        }
    }

    private val listing: StateFlow<Pair<VaultListing, CustomIconImages>?> = open
        .distinctUntilChanged(::sameListing)
        .mapLatest { databases ->
            if (databases.isEmpty()) return@mapLatest null
            withContext(Dispatchers.Default) { listingOf(databases, Instant.now()).let { it to CustomIconImages(it.vault.customIcons) } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<ChooseUiState> = combine(listing, snapshotFlow { query.text.toString() }, recents.ids) { listing, text, recentIds ->
        Triple(listing, text, recentIds)
    }
        .mapLatest { (listing, text, recentIds) ->
            if (listing == null) return@mapLatest ChooseUiState()
            val sections = withContext(Dispatchers.Default) { listing.first.sections(text, recentIds).filter { it.kind != ListSection.Kind.RECYCLE_BIN } }
            ChooseUiState(sections, listing.second, preparing = false)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChooseUiState())

    /** False when [id] cannot be added: an answer holds at most [AnswerPayload.MAX_ENTRIES]. */
    fun toggle(id: String): Boolean {
        if (chosen.remove(id)) return true
        if (chosen.size >= AnswerPayload.MAX_ENTRIES) return false
        chosen += id
        return true
    }
}

/**
 * Choosing what to send for their request [id], or without a request when it is null (SPEC §10): the list with a box on
 * each row, searched as the list is; Next goes on to what of them to send ([onNext]). The recycle bin is not offered.
 */
@Composable
internal fun ChooseEntriesScreen(
    container: AppContainer,
    id: String?,
    preselected: List<String>,
    onClose: () -> Unit,
    onNext: (entryIds: List<String>) -> Unit,
) {
    val book by container.shareRequests.book.collectAsStateWithLifecycle()
    val their = id?.let { id -> book?.theirs?.firstOrNull { it.request.id == id } }
    val gone = book == null || (id != null && their == null)
    LaunchedEffect(gone) { if (gone) onClose() }
    if (gone) return
    val model = viewModel { ChooseEntriesModel(container.sessions.open, container.recents, preselected) }
    val state by model.state.collectAsStateWithLifecycle()
    var full by remember { mutableStateOf(false) }
    if (full) {
        AlertDialog(
            onDismissRequest = { full = false },
            text = { Text(pluralStringResource(R.plurals.sharing_choose_full, AnswerPayload.MAX_ENTRIES, AnswerPayload.MAX_ENTRIES)) },
            confirmButton = { TextButton(onClick = { full = false }) { Text(stringResource(R.string.sharing_ok)) } },
        )
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    BarTitle(
                        stringResource(R.string.sharing_choose_entries),
                        if (their != null) {
                            stringResource(R.string.sharing_choose_for, their.request.name, their.request.reason)
                        } else {
                            stringResource(R.string.sharing_choose_without)
                        },
                    )
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.sharing_close)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            SearchField(model.query, placeholder = stringResource(R.string.sharing_search)) {
                Button(onClick = { onNext(model.chosen.toList()) }, enabled = model.chosen.isNotEmpty(), modifier = Modifier.height(56.dp)) {
                    Text(if (model.chosen.isEmpty()) stringResource(R.string.sharing_next) else pluralStringResource(R.plurals.sharing_next_count, model.chosen.size, model.chosen.size))
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = readableInset(), end = readableInset(), top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            if (state.preparing) {
                item(key = "preparing") { Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            }
            for (section in state.sections) {
                item(key = "header/${section.kind}") { SectionTitle(section.title()) }
                itemsIndexed(section.rows, key = { _, row -> "${section.kind}/${row.id}" }) { index, row ->
                    ChoosableRow(row, segmentShape(index, section.rows.size), state.icons, row.id in model.chosen) {
                        if (!model.toggle(row.id)) full = true
                    }
                }
                item(key = "gap/${section.kind}") { Spacer(Modifier.height(18.dp)) }
            }
            // Bare signs alone (`@`, `!`) list nothing and say nothing (SPEC §6.2).
            if (!state.preparing && state.sections.isEmpty() && !EntrySearch.isEmpty(model.query.text.toString())) {
                item(key = "nothing") { Explanation(stringResource(R.string.sharing_choose_nothing_title), stringResource(R.string.sharing_choose_nothing_text)) }
            }
        }
    }
}

@Composable
private fun ChoosableRow(row: EntryRow, shape: RoundedCornerShape, icons: CustomIconImages?, chosen: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .padding(bottom = SegmentGap)
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.extraColors.row)
            .toggleable(value = chosen, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(row.avatar, icons?.get(row.customIconId), 44.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(row.title.resolve(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            RowSubtitle(row)
        }
        Checkbox(checked = chosen, onCheckedChange = null)
    }
}
