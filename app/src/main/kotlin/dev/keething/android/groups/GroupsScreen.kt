package dev.keething.android.groups

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import dev.keething.android.databases.DatabaseLabelText
import dev.keething.android.databases.NotOpenDatabase
import dev.keething.android.databases.databaseColors
import dev.keething.android.session.OpenDatabase
import dev.keething.vault.databases.DatabaseId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
import dev.keething.android.list.SectionTitle
import dev.keething.android.ui.BarTitle
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.segmentShape
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

/**
 * A block per open database, in the window's order (keething-spec plans/android-groups.md G7): each built again when its
 * database is read again (a save, a sync), gone when it is closed or locked alone, there when one opens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class GroupsViewModel(open: StateFlow<List<OpenDatabase>>) : ViewModel() {
    private val built = GroupsBlocks()

    val blocks: StateFlow<List<GroupsBlock>?> = open
        .map { databases -> databases.map { it.mark to it.unlocked.vault } }
        .distinctUntilChanged { a, b -> a.size == b.size && a.indices.all { a[it].first == b[it].first && a[it].second === b[it].second } }
        .mapLatest { databases -> withContext(Dispatchers.Default) { built.of(databases) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * Groups & tags (SPEC §6.2): a block per open database, even with one, its header pinned while its block scrolls; in it
 * the group tree with how many entries are in each group, the recycle bin, the tags with their counts. A row lists its
 * entries: [onSearch] gets the search to set on the list, that database's only while two or more are open. With two or
 * more a header's tap folds its block ([folded], kept while the app runs). Under the blocks the window's databases
 * [notOpen]; a tap shows one's lock screen ([onLockScreen]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupsScreen(
    open: StateFlow<List<OpenDatabase>>,
    notOpen: List<NotOpenDatabase>,
    folded: MutableStateFlow<Set<DatabaseId>>,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onLockScreen: (DatabaseId) -> Unit,
) {
    val model = viewModel { GroupsViewModel(open) }
    val blocks by model.blocks.collectAsStateWithLifecycle()
    val foldedIds by folded.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { BarTitle("Groups & tags", null) },
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
        val current = blocks
        // Under the bar, not behind it: a block's header is pinned to the list's top.
        LazyColumn(
            Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = readableInset(),
                end = readableInset(),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
        ) {
            if (current == null) {
                item(key = "loading") { LoadingIndicator(Modifier.fillMaxWidth().padding(top = 48.dp)) }
            } else {
                val foldable = current.size >= 2
                for (block in current) {
                    val isOpen = !foldable || block.id !in foldedIds
                    stickyHeader(key = "head/${block.id.value}", contentType = "block header") {
                        BlockHeader(block, foldable, isOpen, onFold = { folded.update { if (isOpen) it + block.id else it - block.id } })
                    }
                    if (isOpen) content(block, onSearch)
                    item(key = "end/${block.id.value}", contentType = "gap") { Spacer(Modifier.height(20.dp)) }
                }
                if (notOpen.isNotEmpty()) item(key = "not open") { NotOpenRow(notOpen, onLockScreen) }
            }
        }
    }
}

/** A block's header: its database's dot and label, how many entries it has; with two or more open, a tap folds it. */
@Composable
private fun BlockHeader(block: GroupsBlock, foldable: Boolean, isOpen: Boolean, onFold: () -> Unit) {
    val entries = NumberFormat.getIntegerInstance().format(block.entries) + if (block.entries == 1) " entry" else " entries"
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 52.dp)
            .then(
                if (foldable) {
                    Modifier
                        .clickable(onClickLabel = if (isOpen) "Fold" else "Unfold", onClick = onFold)
                        .semantics { stateDescription = if (isOpen) "Unfolded" else "Folded" }
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { heading() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(databaseColors(block.mark.colorIndex).dot))
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            DatabaseLabelText(block.mark.label, MaterialTheme.typography.titleMedium, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(entries, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (foldable) {
            val turn by animateFloatAsState(if (isOpen) 0f else -90f, label = "chevron")
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                Modifier.size(22.dp).rotate(turn),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The window's databases not open, on one row under the blocks (G7; databases.not-open): "Not open:" and each one's dot
 * and label; a tap shows its lock screen, Back comes back here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotOpenRow(notOpen: List<NotOpenDatabase>, onLockScreen: (DatabaseId) -> Unit) {
    val single = notOpen.singleOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(segmentShape(0, 1))
            .background(MaterialTheme.extraColors.row)
            .then(if (single != null) Modifier.clickable(onClickLabel = "Unlock") { onLockScreen(single.id) } else Modifier)
            .padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        FlowRow(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Not open:",
                Modifier.align(Alignment.CenterVertically).padding(end = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notOpen.forEach { database ->
                Row(
                    Modifier
                        .heightIn(min = if (single != null) 0.dp else 40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(if (single == null) Modifier.clickable(onClickLabel = "Unlock") { onLockScreen(database.id) } else Modifier)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(databaseColors(database.line.colorIndex).dot))
                    Spacer(Modifier.width(8.dp))
                    DatabaseLabelText(database.line.label, MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (single != null) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun LazyListScope.content(block: GroupsBlock, onSearch: (String) -> Unit) {
    val overview = block.overview
    val id = block.id.value
    item(key = "groups/$id", contentType = "header") { Box(Modifier.padding(top = 6.dp)) { SectionTitle("Groups") } }
    itemsIndexed(overview.groups, key = { _, row -> "group/$id/${row.id}" }, contentType = { _, _ -> "row" }) { index, row ->
        OverviewRow(
            icon = if (row.isRoot) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
            title = if (row.isRoot) "Top level" else row.name,
            supporting = groupCounts(row),
            count = row.total,
            indent = row.depth,
            shape = segmentShape(index, overview.groups.size),
            onClick = { onSearch(block.search(row.search)) },
        )
    }
    if (overview.recycleBin > 0) {
        item(key = "bin/$id", contentType = "row") {
            OverviewRow(
                icon = Icons.Rounded.Delete,
                title = "Recycle bin",
                supporting = null,
                count = overview.recycleBin,
                shape = segmentShape(0, 1),
                onClick = { onSearch(block.search(GroupsOverview.RECYCLE_BIN_SEARCH)) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
    item(key = "gap/$id", contentType = "gap") { Spacer(Modifier.height(18.dp)) }
    item(key = "tags/$id", contentType = "header") { SectionTitle("Tags") }
    if (overview.tags.isEmpty()) {
        item(key = "no tags/$id") {
            Text(
                "No entry has a tag.",
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    itemsIndexed(overview.tags, key = { _, row -> "tag/$id/${row.name}" }, contentType = { _, _ -> "row" }) { index, row ->
        OverviewRow(
            icon = Icons.Rounded.Sell,
            title = row.name,
            supporting = row.otherSpellings.takeIf { it.isNotEmpty() }?.let { "also ${it.joinToString(", ")}" },
            count = row.count,
            shape = segmentShape(index, overview.tags.size),
            onClick = { onSearch(block.search(row.search)) },
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
