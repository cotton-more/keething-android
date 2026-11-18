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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.keething.android.R
import dev.keething.android.list.ConflictCopyModel.Choice
import dev.keething.android.list.ConflictCopyModel.Other
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.resolve
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
                stringResource(R.string.list_conflict_title, model.file.name),
                Modifier.padding(horizontal = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.list_conflict_text),
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Version(stringResource(R.string.list_conflict_open_now), model.file.name, summaryText(model.open), Modifier.padding(bottom = SegmentGap).clip(segmentShape(0, 2, outer = 16.dp)))
                Version(
                    stringResource(R.string.list_conflict_other),
                    stringResource(R.string.list_conflict_other_from, model.copy.createdAt.format(TIME), model.copy.deviceId),
                    when (val other = model.other) {
                        Other.Reading -> stringResource(R.string.list_conflict_reading)
                        is Other.Read -> summaryText(other.summary)
                        is Other.NotOpened -> other.why.resolve()
                    },
                    Modifier.clip(segmentShape(1, 2, outer = 16.dp)),
                )
            }
            val usable = model.other !is Other.NotOpened && model.replaceRefused == null
            Column {
                ChoiceRow(
                    label = stringResource(R.string.list_conflict_keep),
                    detail = stringResource(R.string.list_conflict_keep_detail),
                    icon = Icons.Rounded.CheckCircle,
                    working = model.working == Choice.KEEP_OPEN,
                    enabled = model.working == null,
                    onClick = { asking = Choice.KEEP_OPEN },
                    modifier = Modifier.padding(bottom = SegmentGap).clip(segmentShape(0, 2, outer = 16.dp)),
                )
                ChoiceRow(
                    label = stringResource(R.string.list_conflict_use),
                    detail = if (usable) {
                        stringResource(R.string.list_conflict_use_detail, model.file.name)
                    } else {
                        model.replaceRefused?.resolve() ?: stringResource(R.string.list_conflict_use_not_opened)
                    },
                    icon = Icons.Rounded.SwapHoriz,
                    working = model.working == Choice.USE_OTHER,
                    enabled = model.canUseOther,
                    onClick = { asking = Choice.USE_OTHER },
                    modifier = Modifier.clip(segmentShape(1, 2, outer = 16.dp)),
                )
            }
            model.problem?.let {
                Text(it.resolve(), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
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
                    Choice.KEEP_OPEN -> stringResource(R.string.list_conflict_confirm_keep_title)
                    Choice.USE_OTHER -> stringResource(R.string.list_conflict_confirm_use_title, fileName)
                },
            )
        },
        text = {
            Text(
                when (choice) {
                    Choice.KEEP_OPEN -> stringResource(R.string.list_conflict_confirm_keep_text)
                    Choice.USE_OTHER -> stringResource(R.string.list_conflict_confirm_use_text)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    when (choice) {
                        Choice.KEEP_OPEN -> stringResource(R.string.list_conflict_confirm_keep)
                        Choice.USE_OTHER -> stringResource(R.string.list_conflict_confirm_use)
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.list_cancel)) } },
    )
}

/** "128 entries · last change 8 Nov 2026, 10:15". */
@Composable
private fun summaryText(summary: VaultSummary): String {
    val changed = summary.lastChanged?.atZone(ZoneId.systemDefault())?.format(TIME)
        ?: return pluralStringResource(R.plurals.list_conflict_entries, summary.entries, summary.entries)
    return pluralStringResource(R.plurals.list_conflict_entries_changed, summary.entries, summary.entries, changed)
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
