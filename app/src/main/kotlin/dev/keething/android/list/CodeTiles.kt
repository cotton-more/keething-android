package dev.keething.android.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keething.android.R
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.CodeValue
import dev.keething.android.ui.CustomIconImages
import dev.keething.android.ui.EntryIcon
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.resolve
import dev.keething.android.ui.secondsLeft
import dev.keething.android.ui.segmentShape
import dev.keething.card.TotpDisplay
import java.time.Instant

/**
 * The Codes view (keething-spec app.codes-view AC1–AC3; SPEC §6.2): a tile per one-time code, as an authenticator
 * app lists them, in one column (on a wide window it stays in the list's pane). A tap anywhere on a tile copies its
 * code; ⋯ opens [onMore]. [searched]: something is typed, so an empty view says nothing matches rather than that there
 * are no codes. [selected]: the entry whose card is open beside the view (two panes); its tiles are marked as its rows
 * are in the list.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CodeTiles(
    state: ListUiState,
    padding: PaddingValues,
    listState: LazyListState,
    now: State<Instant>,
    query: String,
    searched: Boolean,
    selected: String?,
    onCopy: (CodeTile) -> Unit,
    onCopyNext: (CodeTile) -> Unit,
    onMore: (CodeTile) -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
    ) {
        for (banner in state.banners) {
            item(key = banner.key) { Banner(title = banner.title.resolve(), text = banner.text.resolve(), onDismiss = banner.onDismiss, action = banner.action?.let { (label, act) -> label.resolve() to act }) }
        }
        if (state.preparing) {
            item(key = "preparing") {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
        }
        itemsIndexed(state.codes, key = { _, tile -> tile.key }, contentType = { _, _ -> "tile" }) { index, tile ->
            // A segmented group, as the list's rows.
            CodeTileView(
                tile, segmentShape(index, state.codes.size), state.icons, now,
                selected = tile.entryId == selected,
                onCopy = { onCopy(tile) },
                onCopyNext = { onCopyNext(tile) },
                onMore = { onMore(tile) },
                modifier = Modifier.animateItem().padding(bottom = SegmentGap),
            )
        }
        if (!state.preparing && state.codes.isEmpty()) {
            item(key = "nothing") {
                // Bare signs alone (`@`, `!`) list nothing and say nothing: the chips offer what they can be.
                val text = when {
                    searched -> stringResource(R.string.list_codes_no_match, query)
                    query.isBlank() -> stringResource(R.string.list_codes_none)
                    else -> null
                }
                text?.let {
                    Text(
                        it,
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A code's tile (the canvas's "Codes"): the entry's icon and title, under it the username or a further code's field
 * name (a field icon before it) and where the entry is, as its row says it ([GroupLine]), and the live code large with
 * the next one beside it (keething-spec card.code-timer). [selected]: its entry's card is open beside the view.
 */
@Composable
private fun CodeTileView(
    tile: CodeTile,
    shape: Shape,
    icons: CustomIconImages?,
    now: State<Instant>,
    selected: Boolean,
    onCopy: () -> Unit,
    onCopyNext: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val display = TotpDisplay.of(tile.totp, now.value)
    val left = secondsLeft(display.secondsRemaining)
    val copyCode = stringResource(R.string.list_copy_code)
    val separator = stringResource(R.string.list_separator)
    val title = tile.title.resolve()
    // The open entry's tiles marked as its rows are (keething-spec app.codes-view, Android's note).
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.extraColors.row),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = copyCode, onClick = onCopy)
                // The code and its seconds too: the description is all a screen reader says of the tile (keething-spec
                // app.accessibility AC4).
                .semantics {
                    contentDescription = listOf(title, tile.subtitle, tile.whereSaid(), display.formatted, left)
                        .filter { it.isNotBlank() }.joinToString(separator)
                    this.selected = selected
                }
                .padding(start = 16.dp, end = 56.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                EntryIcon(tile.avatar, icons?.get(tile.customIconId), 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CodeSubtitle(tile)
                    GroupLine(tile.database, tile.group)
                }
            }
            Box(Modifier.padding(start = 54.dp)) { CodeValue(display, onCopyNext, fontSize = 30.sp) }
        }
        IconButton(onClick = onMore, modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.list_more_for, tile.title.resolve()), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The username under the entry's own code; a further code's field name, a field icon before it. */
@Composable
private fun CodeSubtitle(tile: CodeTile) {
    if (tile.subtitle.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (tile.further) Icon(Icons.Rounded.Title, contentDescription = stringResource(R.string.list_field), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(tile.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A tile's ⋯ (the canvas's "A tile's ⋯"): Copy code, Open entry, Edit code… and Delete from the entry, which names the
 * entry and the field that goes so that it reads as a change to that entry (keething-spec app.codes-view AC5); the code
 * all its entry holds, Move entry to the recycle bin instead (AC6). Each closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeSheet(
    tile: CodeTile,
    icons: CustomIconImages?,
    now: State<Instant>,
    deletesEntry: Boolean,
    onCopy: () -> Unit,
    onOpenEntry: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val delete = when {
        deletesEntry -> SheetItem(
            stringResource(R.string.list_code_move_entry), Icons.Rounded.Delete,
            stringResource(R.string.list_code_move_entry_detail, tile.title.resolve()), danger = true, act = onDelete,
        )
        tile.further -> SheetItem(
            stringResource(R.string.list_code_delete), Icons.Rounded.Delete,
            stringResource(R.string.list_code_delete_field_detail, tile.subtitle, tile.title.resolve()), danger = true, act = onDelete,
        )
        else -> SheetItem(
            stringResource(R.string.list_code_delete), Icons.Rounded.Delete,
            stringResource(R.string.list_code_delete_detail, tile.title.resolve()), danger = true, act = onDelete,
        )
    }
    val actions = listOf(
        SheetItem(stringResource(R.string.list_copy_code), Icons.Rounded.ContentCopy, act = onCopy),
        SheetItem(stringResource(R.string.list_code_open_entry), Icons.Rounded.Description, act = onOpenEntry),
        SheetItem(stringResource(R.string.list_code_edit), Icons.Rounded.Edit, act = onEdit),
        delete,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val display = TotpDisplay.of(tile.totp, now.value)
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                EntryIcon(tile.avatar, icons?.get(tile.customIconId), 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(tile.title.resolve(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    CodeSubtitle(tile)
                }
                Text(display.formatted, fontFamily = MonospaceFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            }
            Column {
                actions.forEachIndexed { index, item ->
                    SheetAction(item, Modifier.padding(bottom = SegmentGap).clip(segmentShape(index, actions.size, outer = 16.dp))) {
                        onDismiss()
                        item.act()
                    }
                }
            }
        }
    }
}

private class SheetItem(val label: String, val icon: ImageVector, val detail: String? = null, val danger: Boolean = false, val act: () -> Unit)

@Composable
private fun SheetAction(item: SheetItem, modifier: Modifier, onClick: () -> Unit) {
    val colour = if (item.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(MaterialTheme.extraColors.row)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(item.icon, contentDescription = null, tint = if (item.danger) colour else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge, color = colour)
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
