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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.keething.android.list.ConflictCopyModel.Choice
import dev.keething.android.list.ConflictCopyModel.Other
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.segmentShape
import dev.keething.vault.session.VaultSummary
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Resolve… of a conflict copy (SPEC §6.2; keething-spec kdbx.conflict-copies AC6): the open version and the other one,
 * each in brief, then Keep the open one and Use the other one, each saying what it does and asked once more before it is
 * done. While a choice is carried out
 * the sheet stays and nothing else can be chosen; closing it then lets the choice go on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConflictCopySheet(model: ConflictCopyModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The choice tapped, asked once more before it is carried out.
    var asking by remember { mutableStateOf<Choice?>(null) }
    asking?.let { choice ->
        ConfirmChoiceDialog(
            choice,
            fileName = model.file.name,
            onConfirm = {
                asking = null
                when (choice) {
                    Choice.KEEP_OPEN -> model.keepOpen()
                    Choice.USE_OTHER -> model.useOther()
                }
            },
            onDismiss = { asking = null },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Two versions of ${model.file.name}",
                Modifier.padding(horizontal = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Syncthing kept both when two devices changed the database at once. Keething does not merge them: " +
                    "keep one here, or merge them in KeePassXC (Database › Merge) to keep the changes of both.",
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Version("Open now", model.file.name, summaryText(model.open), Modifier.padding(bottom = SegmentGap).clip(segmentShape(0, 2, outer = 16.dp)))
                Version(
                    "The other one",
                    "From ${model.copy.createdAt.format(TIME)}, device ${model.copy.deviceId}",
                    when (val other = model.other) {
                        Other.Reading -> "Reading…"
                        is Other.Read -> summaryText(other.summary)
                        is Other.NotOpened -> other.why
                    },
                    Modifier.clip(segmentShape(1, 2, outer = 16.dp)),
                )
            }
            val usable = model.other !is Other.NotOpened && model.replaceRefused == null
            Column {
                ChoiceRow(
                    label = "Keep the open one",
                    detail = "Deletes the other version. A copy of it is kept in Keething's backups of this database first. " +
                        "Changes made only in the other version are not in the open one.",
                    icon = Icons.Rounded.CheckCircle,
                    working = model.working == Choice.KEEP_OPEN,
                    enabled = model.working == null,
                    onClick = { asking = Choice.KEEP_OPEN },
                    modifier = Modifier.padding(bottom = SegmentGap).clip(segmentShape(0, 2, outer = 16.dp)),
                )
                ChoiceRow(
                    label = "Use the other one",
                    detail = if (usable) {
                        "Replaces ${model.file.name} with the other version, whose file then goes. The open one is kept in " +
                            "Keething's backups first. Changes made only in the open one are lost from the database."
                    } else {
                        model.replaceRefused ?: "It cannot be used: it does not open here."
                    },
                    icon = Icons.Rounded.SwapHoriz,
                    working = model.working == Choice.USE_OTHER,
                    enabled = model.canUseOther,
                    onClick = { asking = Choice.USE_OTHER },
                    modifier = Modifier.clip(segmentShape(1, 2, outer = 16.dp)),
                )
            }
            model.problem?.let {
                Text(it, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Version(title: String, name: String, summary: String, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.extraColors.row).padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChoiceRow(label: String, detail: String, icon: ImageVector, working: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colour = if (enabled || working) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(MaterialTheme.extraColors.row)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (working) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
        } else {
            Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else colour)
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colour)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Keep the open one or Use the other one, asked once more: what goes, and that it is backed up first. */
@Composable
private fun ConfirmChoiceDialog(choice: Choice, fileName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (choice) {
                    Choice.KEEP_OPEN -> "Delete the other version?"
                    Choice.USE_OTHER -> "Replace $fileName with the other version?"
                },
            )
        },
        text = {
            Text(
                when (choice) {
                    Choice.KEEP_OPEN -> "Its file is deleted; a copy of it is kept in Keething's backups of this database first."
                    Choice.USE_OTHER -> "The open version is kept in Keething's backups first, then the other one takes its place " +
                        "and its file is deleted. Changes made only in the open one are lost from the database."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    when (choice) {
                        Choice.KEEP_OPEN -> "Delete it"
                        Choice.USE_OTHER -> "Replace"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "128 entries · last change 8 Nov 2026, 10:15". */
private fun summaryText(summary: VaultSummary): String {
    val entries = if (summary.entries == 1) "1 entry" else "${summary.entries} entries"
    val changed = summary.lastChanged?.atZone(ZoneId.systemDefault())?.format(TIME) ?: return entries
    return "$entries · last change $changed"
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
