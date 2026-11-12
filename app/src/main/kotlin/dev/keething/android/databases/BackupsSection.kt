package dev.keething.android.databases

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keething.android.AppContainer
import dev.keething.android.storage.FileDatabaseBackups
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Backups section and its questions say (the canvas "Database backups"). */
internal object BackupTexts {
    /** The line under the switch: how many are kept and their size, or that there are none or it is off. */
    fun lead(name: String, on: Boolean, count: Int, size: String): String = when {
        !on -> "Off: nothing is copied before a change." + when (count) {
            0 -> ""
            1 -> " The one kept stays until deleted."
            else -> " The $count kept stay until deleted."
        }
        count == 0 -> "None yet: the first is kept before $name’s first change after an unlock, the file as it was."
        else -> "$count kept, $size: the file as it was before the first change of each unlock."
    }

    const val WHERE: String = "On this phone only, apart from other databases’: removing Keething, or its data, removes them."

    fun offTitle(name: String): String = "Stop backing up $name?"

    fun offText(name: String, count: Int): String =
        "$name is no longer copied before its changes. " + if (count == 1) "Its backup can stay here or go." else "Its $count backups can stay here or go."

    fun offKeep(count: Int): String = if (count == 1) "Keep it" else "Keep them"

    fun offDelete(count: Int): String = if (count == 1) "Delete it" else "Delete them"

    fun fewerTitle(keep: Int): String = "Keep $keep backups?"

    fun fewerText(name: String, gone: Int): String = if (gone == 1) {
        "The oldest backup of $name is deleted from this phone now. It can’t be brought back."
    } else {
        "The $gone oldest backups of $name are deleted from this phone now. They can’t be brought back."
    }

    /** How many of [count] go when [keep] are kept; 0: nothing to ask (more kept keeps what comes). */
    fun gone(count: Int, keep: Int): Int = (count - keep).coerceAtLeast(0)
}

/**
 * The database's page, Backups (keething-spec databases.backups-window AC2, AC3; the canvas "Database backups"): its
 * switch "Back up before changes", Keep 5 · 10 · 20 · 50 while it is on, how many there are and their size. Open or
 * locked alike: the settings are the record's, never the file's. Fewer asks first and deletes the oldest at once;
 * turning it off asks whether the backups stay or go.
 */
@Composable
internal fun BackupsOfDatabase(container: AppContainer, id: DatabaseId, name: String) {
    val list = container.sessions.list
    val databases by list.state.collectAsStateWithLifecycle()
    val record = databases.record(id) ?: return
    val backups = remember(id) { container.sessions.backups(id) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<FileDatabaseBackups.Summary?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    var askingOff by remember { mutableStateOf(false) }
    var askingKeep by remember { mutableStateOf<Int?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reads++ }
    // A save from this page (its name and colour) may keep one: read again as the open database changes.
    container.sessions.state.collectAsStateWithLifecycle().value
    val state = container.sessions.session(id)?.session?.state?.let { flow -> key(flow) { flow.collectAsStateWithLifecycle().value } }
    LaunchedEffect(reads, state, record.backupsKept) { summary = withContext(Dispatchers.IO) { backups.summary() } }
    val count = summary?.count ?: 0

    fun change(work: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { work() }
            reads++
        }
    }

    if (askingOff) {
        AlertDialog(
            onDismissRequest = { askingOff = false },
            title = { Text(BackupTexts.offTitle(name)) },
            text = { Text(BackupTexts.offText(name, count)) },
            confirmButton = {
                TextButton(onClick = {
                    askingOff = false
                    list.update(id) { it.copy(backupsOn = false) }
                    change { backups.deleteAll() }
                }) { Text(BackupTexts.offDelete(count), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askingOff = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        askingOff = false
                        list.update(id) { it.copy(backupsOn = false) }
                    }) { Text(BackupTexts.offKeep(count)) }
                }
            },
        )
    }
    askingKeep?.let { keep ->
        val gone = BackupTexts.gone(count, keep)
        AlertDialog(
            onDismissRequest = { askingKeep = null },
            title = { Text(BackupTexts.fewerTitle(keep)) },
            text = { Text(BackupTexts.fewerText(name, gone)) },
            confirmButton = {
                TextButton(onClick = {
                    askingKeep = null
                    list.update(id) { it.copy(backupsKept = keep) }
                    change { backups.prune(keep) }
                }) { Text("Delete $gone", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askingKeep = null }) { Text("Cancel") } },
        )
    }

    Row(
        Modifier.fillMaxWidth().toggleable(
            value = record.backupsOn,
            role = Role.Switch,
            onValueChange = { on ->
                when {
                    on -> list.update(id) { it.copy(backupsOn = true) }
                    count == 0 -> list.update(id) { it.copy(backupsOn = false) }
                    else -> askingOff = true
                }
            },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Back up before changes", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = record.backupsOn, onCheckedChange = null)
    }
    if (record.backupsOn) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Keep", style = MaterialTheme.typography.bodyLarge)
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                DatabaseRecord.BACKUPS_KEPT.forEachIndexed { index, keep ->
                    SegmentedButton(
                        selected = keep == record.backupsKept,
                        onClick = {
                            when {
                                keep == record.backupsKept -> Unit
                                BackupTexts.gone(count, keep) > 0 -> askingKeep = keep
                                else -> list.update(id) { it.copy(backupsKept = keep) }
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, DatabaseRecord.BACKUPS_KEPT.size),
                        icon = {},
                    ) { Text("$keep") }
                }
            }
        }
    }
    if (summary != null) {
        Text(
            BackupTexts.lead(name, record.backupsOn, count, Formatter.formatShortFileSize(context, summary?.bytes ?: 0)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(BackupTexts.WHERE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
