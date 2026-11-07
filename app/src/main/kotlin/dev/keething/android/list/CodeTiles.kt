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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.CodeRing
import dev.keething.android.ui.CustomIconImages
import dev.keething.android.ui.EntryIcon
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.segmentShape
import dev.keething.card.TotpDisplay
import java.time.Instant

/** From this width the tiles go in two columns (keething-spec app.wide-screens). */
private val TWO_COLUMNS = 840.dp

/**
 * The Codes view (keething-spec app.codes-view AC1–AC3; SPEC §6.2): a tile per one-time code, as an authenticator
 * app lists them. A tap anywhere on a tile copies its code; ⋯ opens [onMore]. Two columns on a wide window. [searched]:
 * something is typed, so an empty view says nothing matches rather than that there are no codes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CodeTiles(
    state: ListUiState,
    width: Dp,
    padding: PaddingValues,
    gridState: LazyGridState,
    now: State<Instant>,
    query: String,
    searched: Boolean,
    onCopy: (CodeTile) -> Unit,
    onMore: (CodeTile) -> Unit,
) {
    val columns = if (width >= TWO_COLUMNS) 2 else 1
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (banner in state.banners) {
            item(key = banner.key, span = { GridItemSpan(maxLineSpan) }) { Banner(title = banner.title, text = banner.text, onDismiss = banner.onDismiss) }
        }
        if (state.preparing) {
            item(key = "preparing", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
        }
        itemsIndexed(state.codes, key = { _, tile -> tile.key }, contentType = { _, _ -> "tile" }) { index, tile ->
            // One column: a segmented group, as the list's rows; two: each tile on its own.
            val shape = if (columns == 1) segmentShape(index, state.codes.size) else RoundedCornerShape(20.dp)
            CodeTileView(
                tile, shape, state.icons, now,
                onCopy = { onCopy(tile) },
                onMore = { onMore(tile) },
                modifier = Modifier.animateItem().padding(bottom = if (columns == 1) SegmentGap else 8.dp),
            )
        }
        if (!state.preparing && state.codes.isEmpty()) {
            item(key = "nothing", span = { GridItemSpan(maxLineSpan) }) {
                // Bare signs alone (`@`, `!`) list nothing and say nothing: the chips offer what they can be.
                val text = when {
                    searched -> "No code matches “$query”."
                    query.isBlank() -> "No entry has a one-time code."
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
 * its ring.
 */
@Composable
private fun CodeTileView(
    tile: CodeTile,
    shape: Shape,
    icons: CustomIconImages?,
    now: State<Instant>,
    onCopy: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val display = TotpDisplay.of(tile.totp, now.value)
    val colour = if (display.secondsRemaining <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth().clip(shape).background(MaterialTheme.extraColors.row)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Copy code", onClick = onCopy)
                .semantics { contentDescription = "${tile.title}, ${tile.subtitle}".trimEnd(',', ' ') }
                .padding(start = 16.dp, end = 56.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                EntryIcon(tile.avatar, icons?.get(tile.customIconId), 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(tile.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CodeSubtitle(tile)
                    GroupLine(tile.database, tile.group)
                }
            }
            Row(
                Modifier.padding(start = 54.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    display.formatted,
                    fontFamily = MonospaceFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 30.sp,
                    lineHeight = 38.sp,
                    letterSpacing = 1.sp,
                    color = colour,
                )
                CodeRing(display.period, colour, MaterialTheme.colorScheme.outlineVariant, size = 22.dp)
            }
        }
        IconButton(onClick = onMore, modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${tile.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The username under the entry's own code; a further code's field name, a field icon before it. */
@Composable
private fun CodeSubtitle(tile: CodeTile) {
    if (tile.subtitle.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (tile.further) Icon(Icons.Rounded.Title, contentDescription = "Field", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        deletesEntry -> SheetItem("Move entry to the recycle bin", Icons.Rounded.Delete, "Its one-time code is all “${tile.title}” holds", danger = true, act = onDelete)
        tile.further -> SheetItem("Delete from the entry", Icons.Rounded.Delete, "The field “${tile.subtitle}” goes from ${tile.title}", danger = true, act = onDelete)
        else -> SheetItem("Delete from the entry", Icons.Rounded.Delete, "Its one-time code goes from ${tile.title}", danger = true, act = onDelete)
    }
    val actions = listOf(
        SheetItem("Copy code", Icons.Rounded.ContentCopy, act = onCopy),
        SheetItem("Open entry", Icons.Rounded.Description, act = onOpenEntry),
        SheetItem("Edit code…", Icons.Rounded.Edit, act = onEdit),
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
                    Text(tile.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
