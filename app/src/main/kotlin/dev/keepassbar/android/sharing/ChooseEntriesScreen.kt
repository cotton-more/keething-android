package dev.keepassbar.android.sharing

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.list.EntryRow
import dev.keepassbar.android.list.ListSection
import dev.keepassbar.android.list.RecentEntries
import dev.keepassbar.android.list.SearchField
import dev.keepassbar.android.list.SectionTitle
import dev.keepassbar.android.list.VaultListing
import dev.keepassbar.android.list.title
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.CustomIconImages
import dev.keepassbar.android.ui.EntryIcon
import dev.keepassbar.android.ui.SegmentGap
import dev.keepassbar.android.ui.segmentShape
import dev.keepassbar.sharing.payload.AnswerPayload
import dev.keepassbar.vault.session.SessionState
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

internal data class ChooseUiState(val sections: List<ListSection> = emptyList(), val icons: CustomIconImages? = null, val preparing: Boolean = true)

/** The vault searched as the list searches it, and the entries chosen so far (ids only, they survive a rotation). */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChooseEntriesModel(database: DatabaseSession, recents: RecentEntries, preselected: List<String>) : ViewModel() {
    val query = TextFieldState()
    val chosen = mutableStateListOf<String>().apply { addAll(preselected) }

    private val listing: StateFlow<Pair<VaultListing, CustomIconImages>?> = database.session.state
        .map { (it as? SessionState.Unlocked)?.vault }
        .distinctUntilChanged { a, b -> a === b }
        .mapLatest { vault -> vault?.let { withContext(Dispatchers.Default) { VaultListing(it, Instant.now()) to CustomIconImages(it.customIcons) } } }
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
    database: DatabaseSession,
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
    val model = viewModel { ChooseEntriesModel(database, container.recents, preselected) }
    val state by model.state.collectAsStateWithLifecycle()
    var full by remember { mutableStateOf(false) }
    if (full) {
        AlertDialog(
            onDismissRequest = { full = false },
            text = { Text("One code holds at most ${AnswerPayload.MAX_ENTRIES} entries. Send the rest in another one.") },
            confirmButton = { TextButton(onClick = { full = false }) { Text("OK") } },
        )
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Choose entries")
                        Text(
                            if (their != null) "for ${their.request.name} · ${their.request.reason}" else "to send without a request",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            SearchField(model.query, placeholder = "Search") {
                Button(onClick = { onNext(model.chosen.toList()) }, enabled = model.chosen.isNotEmpty(), modifier = Modifier.height(56.dp)) {
                    Text(if (model.chosen.isEmpty()) "Next" else "Next · ${model.chosen.size}")
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
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
            if (!state.preparing && state.sections.isEmpty()) {
                item(key = "nothing") { Explanation("Nothing matches", "Search for another title, user name or site.") }
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
            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.subtitle.isNotEmpty()) {
                Text(row.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Checkbox(checked = chosen, onCheckedChange = null)
    }
}
