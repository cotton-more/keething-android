package dev.keething.android.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.CodeValue
import dev.keething.android.ui.CustomIconImages
import dev.keething.android.ui.EntryMark
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.secondsLeft
import dev.keething.android.ui.segmentShape
import dev.keething.card.TotpDisplay
import java.time.Instant

/**
 * The codes of a row whose entry has two or more (keething-spec search.row-codes AC4): headed by the entry, each code with
 * its name, live on its draining fill with the next one under it, as the card shows a code. A tap on a code copies it, a
 * tap on the next code copies the next; either closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RowCodesSheet(
    row: EntryRow,
    icons: CustomIconImages?,
    now: State<Instant>,
    onCopy: (CodeTile) -> Unit,
    onCopyNext: (CodeTile) -> Unit,
    onDismiss: () -> Unit,
) {
    // Opened whole: half-way, a third code would start under the screen's edge.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                EntryMark(row.avatar, icons?.get(row.customIconId), row.face, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        row.title,
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(codeCount(row.codes.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column {
                row.codes.forEachIndexed { index, tile ->
                    RowCode(
                        tile, now,
                        onCopy = { onDismiss(); onCopy(tile) },
                        onCopyNext = { onDismiss(); onCopyNext(tile) },
                        modifier = Modifier.padding(bottom = SegmentGap).clip(segmentShape(index, row.codes.size, outer = 16.dp)),
                    )
                }
            }
        }
    }
}

/** "2 one-time codes": the sheet's line under the title, and what a screen reader says of a row's button (AC6). */
internal fun codeCount(count: Int): String = if (count == 1) "1 one-time code" else "$count one-time codes"

/**
 * One code of the sheet: its name (a field icon before a further code's), the code and the next one. A screen reader
 * hears the name, the code and its seconds as the tile's description, the next code as its own button (AC6).
 */
@Composable
private fun RowCode(tile: CodeTile, now: State<Instant>, onCopy: () -> Unit, onCopyNext: () -> Unit, modifier: Modifier) {
    val display = TotpDisplay.of(tile.totp, now.value)
    val name = if (tile.further) tile.subtitle else "One-time code"
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.extraColors.row)
            .clickable(onClickLabel = "Copy code", onClick = onCopy)
            .semantics { contentDescription = listOf(name, display.formatted, secondsLeft(display.secondsRemaining)).joinToString(", ") }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                if (tile.further) Icons.Rounded.Title else Icons.Rounded.Timer,
                contentDescription = null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CodeValue(display, onCopyNext)
    }
}
