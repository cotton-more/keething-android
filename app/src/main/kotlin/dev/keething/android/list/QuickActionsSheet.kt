package dev.keething.android.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.CustomIconImages
import dev.keething.android.ui.EntryMark
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.segmentShape
import dev.keething.card.TotpDisplay
import java.time.Instant

/** What the quick actions sheet asks the list to do. */
internal interface QuickActionHandler {
    fun copyUsername(id: String)
    fun copyPassword(id: String)
    fun copyCode(tile: CodeTile)

    /** The entry's two or more codes, in their sheet (keething-spec search.row-codes AC5). */
    fun showCodes(row: EntryRow)
    fun openSite(id: String, url: String)
    fun edit(id: String)
    fun removeFromRecent(id: String)
    fun moveToRecycleBin(id: String)

    /** An entry in the bin, back into the group it came from. */
    fun restore(id: String)

    /** An entry in the bin, for good: asked first (keething-spec edit.delete-for-good AC2). */
    fun deleteForGood(id: String)
}

private class Action(val label: String, val icon: ImageVector, val detail: String? = null, val code: String? = null, val danger: Boolean = false, val act: () -> Unit)

/**
 * A long press on a row (SPEC §6.2): what the card would take two taps for. Copy the username, the password or the
 * code, open the site; then Edit, Remove from Recent (a recent row) and Move to the recycle bin. An entry in the bin
 * has the copies, the site, Restore and Delete for good… (asked first). Each action closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickActionsSheet(
    actions: QuickActions,
    fromRecent: Boolean,
    icons: CustomIconImages?,
    now: State<Instant>,
    handler: QuickActionHandler,
    onDismiss: () -> Unit,
) {
    val row = actions.row
    val id = actions.id
    val done = { act: () -> Unit -> { onDismiss(); act() } }
    val copies = listOfNotNull(
        Action("Copy username", Icons.Rounded.Person, detail = actions.username, act = done { handler.copyUsername(id) })
            .takeIf { row.hasUsername },
        Action("Copy password", Icons.Rounded.Key, act = done { handler.copyPassword(id) }).takeIf { row.hasPassword },
        // One code is copied as from the row's button, two or more open their sheet (keething-spec search.row-codes AC5).
        row.codes.singleOrNull()?.let { tile ->
            Action("Copy one-time code", Icons.Rounded.Timer, code = TotpDisplay.of(tile.totp, now.value).formatted, act = done { handler.copyCode(tile) })
        } ?: row.codes.takeIf { it.size > 1 }?.let { codes ->
            Action("Copy one-time code…", Icons.Rounded.Timer, detail = "${codes.size} codes", act = done { handler.showCodes(row) })
        },
        actions.url?.let { url -> Action("Open ${actions.host}", Icons.AutoMirrored.Rounded.OpenInNew, act = done { handler.openSite(id, url) }) },
    )
    val changes = if (actions.inRecycleBin) {
        listOf(
            Action("Restore", Icons.Rounded.RestoreFromTrash, act = done { handler.restore(id) }),
            Action("Delete for good…", Icons.Rounded.DeleteForever, danger = true, act = done { handler.deleteForGood(id) }),
        )
    } else {
        listOfNotNull(
            Action("Edit", Icons.Rounded.Edit, act = done { handler.edit(id) }),
            Action("Remove from Recent", Icons.Rounded.HistoryToggleOff, act = done { handler.removeFromRecent(id) }).takeIf { fromRecent },
            Action("Move to the recycle bin", Icons.Rounded.Delete, danger = true, act = done { handler.moveToRecycleBin(id) }),
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                EntryMark(row.avatar, icons?.get(row.customIconId), row.face, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    RowSubtitle(row)
                }
            }
            for (group in listOf(copies, changes)) {
                if (group.isEmpty()) continue
                Column {
                    group.forEachIndexed { index, action -> ActionRow(action, Modifier.padding(bottom = SegmentGap).clip(segmentShape(index, group.size, outer = 16.dp))) }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(action: Action, modifier: Modifier) {
    val colour = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val iconColour = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(MaterialTheme.extraColors.row)
            .clickable(onClick = action.act)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(action.icon, contentDescription = null, tint = iconColour)
        Column(Modifier.weight(1f)) {
            Text(action.label, style = MaterialTheme.typography.bodyLarge, color = colour)
            action.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        action.code?.let {
            Text(it, fontFamily = MonospaceFamily, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
