package dev.keepassbar.android.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keepassbar.android.list.SectionTitle
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.SegmentGap
import dev.keepassbar.android.ui.segmentShape
import dev.keepassbar.vault.session.SessionState
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** The overview of the vault shown now, built again when the vault is read again (a save, a sync). */
@OptIn(ExperimentalCoroutinesApi::class)
internal class GroupsViewModel(database: DatabaseSession) : ViewModel() {
    val overview: StateFlow<GroupsOverview?> = database.session.state
        .map { (it as? SessionState.Unlocked)?.vault }
        .distinctUntilChanged { a, b -> a === b }
        .mapLatest { vault -> vault?.let { withContext(Dispatchers.Default) { GroupsOverview.of(it) } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * Groups & tags (SPEC §6.2): the group tree with how many entries are in each group, the recycle bin, the
 * tags with their counts. A row lists its entries: [onSearch] gets the search to set on the list.
 */
@Composable
internal fun GroupsScreen(database: DatabaseSession, onBack: () -> Unit, onSearch: (String) -> Unit) {
    val model = viewModel { GroupsViewModel(database) }
    val overview by model.overview.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Groups & tags") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val current = overview
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
        ) {
            if (current == null) {
                item(key = "loading") { LoadingIndicator(Modifier.fillMaxWidth().padding(top = 48.dp)) }
            } else {
                content(current, onSearch)
            }
        }
    }
}

private fun LazyListScope.content(overview: GroupsOverview, onSearch: (String) -> Unit) {
    item(key = "groups", contentType = "header") { SectionTitle("Groups") }
    itemsIndexed(overview.groups, key = { _, row -> "group/${row.id}" }, contentType = { _, _ -> "row" }) { index, row ->
        OverviewRow(
            icon = if (row.isRoot) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
            title = if (row.isRoot) "Top level" else row.name,
            supporting = groupCounts(row),
            count = row.total,
            indent = row.depth,
            shape = segmentShape(index, overview.groups.size),
            onClick = { onSearch(row.search) },
        )
    }
    if (overview.recycleBin > 0) {
        item(key = "bin", contentType = "row") {
            OverviewRow(
                icon = Icons.Rounded.Delete,
                title = "Recycle bin",
                supporting = null,
                count = overview.recycleBin,
                shape = segmentShape(0, 1),
                onClick = { onSearch(GroupsOverview.RECYCLE_BIN_SEARCH) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
    item(key = "gap", contentType = "gap") { Spacer(Modifier.height(18.dp)) }
    item(key = "tags", contentType = "header") { SectionTitle("Tags") }
    if (overview.tags.isEmpty()) {
        item(key = "no tags") {
            Text(
                "No entry has a tag.",
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    itemsIndexed(overview.tags, key = { _, row -> "tag/${row.name}" }, contentType = { _, _ -> "row" }) { index, row ->
        OverviewRow(
            icon = Icons.Rounded.Sell,
            title = row.name,
            supporting = row.otherSpellings.takeIf { it.isNotEmpty() }?.let { "also ${it.joinToString(", ")}" },
            count = row.count,
            shape = segmentShape(index, overview.tags.size),
            onClick = { onSearch(row.search) },
        )
    }
}

/** The total is the row's number; under the name, how many of them are right in it when some are in its groups. */
private fun groupCounts(row: GroupsOverview.GroupRow): String? = when {
    row.here == row.total -> null
    row.here == 0 -> "None here, all in its groups"
    else -> "${NumberFormat.getIntegerInstance().format(row.here)} here"
}

@Composable
private fun OverviewRow(
    icon: ImageVector,
    title: String,
    supporting: String?,
    count: Int,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indent: Int = 0,
) {
    Row(
        modifier
            .padding(bottom = SegmentGap)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(MaterialTheme.extraColors.row)
            .clickable(onClick = onClick, onClickLabel = "Show the entries")
            .padding(start = 16.dp + 20.dp * indent, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            NumberFormat.getIntegerInstance().format(count),
            Modifier.semantics { contentDescription = if (count == 1) "1 entry" else "$count entries" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
