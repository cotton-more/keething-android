package dev.keething.android.databases

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keething.android.AppContainer
import dev.keething.android.storage.FileDatabaseBackups
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.Notice
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.segmentShape
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
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

    /** A backup's date and time: "Today, 14:03", "Yesterday, 21:40", "Mon 9 Nov, 08:15", "Sun 9 Nov 2025, 08:15". */
    fun date(at: Instant, now: Instant, zone: ZoneId): String {
        val time = at.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val day = when (time.toLocalDate()) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> (if (time.year == today.year) DAY else DAY_YEAR).format(time)
        }
        return "$day, ${TIME.format(time)}"
    }

    /**
     * How long ago, beside the date: "just now", "5 minutes ago", "17 hours ago" within a day, then the days by the
     * calendar ("3 days ago"), "5 weeks ago", "3 months ago", "2 years ago".
     */
    fun ago(at: Instant, now: Instant, zone: ZoneId): String {
        val seconds = Duration.between(at, now).seconds
        if (seconds < 60) return "just now"
        if (seconds < 3600) return "${count(seconds / 60, "minute")} ago"
        if (seconds < 24 * 3600) return "${count(seconds / 3600, "hour")} ago"
        val days = ChronoUnit.DAYS.between(at.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate()).coerceAtLeast(1)
        return when {
            days < 14 -> count(days, "day")
            days < 60 -> count(days / 7, "week")
            days < 730 -> count(days / 30, "month")
            else -> count(days / 365, "year")
        } + " ago"
    }

    /** Under a backup's date: how long ago and its size, or that it is damaged (keething-spec databases.backups-window AC5). */
    fun detail(ago: String, size: String, damaged: Boolean): String = if (damaged) "$ago · damaged: it would not open" else "$ago · $size"

    const val DAMAGED: String = "Its file is damaged: its header does not match its checksum, so it would not open. It cannot be restored."

    fun more(count: Int, expanded: Boolean): String = if (expanded) "Show fewer" else "Show all $count"

    const val DELETE_TITLE: String = "Delete this backup?"

    fun deleteText(date: String): String = "The backup of $date is deleted from this phone. It can’t be brought back."

    fun deleted(date: String): String = "Deleted the backup of $date"

    fun deleteAllTitle(count: Int): String = if (count == 1) "Delete the backup?" else "Delete all backups?"

    fun deleteAllText(name: String, count: Int): String = if (count == 1) {
        "The backup of $name is deleted from this phone. It can’t be brought back."
    } else {
        "The $count backups of $name are deleted from this phone. They can’t be brought back."
    }

    const val DELETE_ALL_NOTE: String = "Backups made before a master key change still open with the old one: this is how they go."

    private fun count(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM uuuu", Locale.ENGLISH)
}

/**
 * The database's page, Backups (keething-spec databases.backups-window AC1-AC5; the canvas "Database backups"): its
 * switch "Back up before changes", Keep 5 · 10 · 20 · 50 while it is on, how many there are and their size, then the
 * backups newest first (the newest three, Show all N for the rest in place), each opening a sheet with Delete…, and
 * Delete all backups…. Open or locked alike: the settings are the record's, never the file's, and nothing about a
 * backup needs its key. Fewer asks first and deletes the oldest at once; turning it off asks whether the backups stay
 * or go; a deletion is said through [onNotice].
 */
@Composable
internal fun BackupsOfDatabase(container: AppContainer, id: DatabaseId, name: String, onNotice: (Notice) -> Unit) {
    val list = container.sessions.list
    val databases by list.state.collectAsStateWithLifecycle()
    val record = databases.record(id) ?: return
    val backups = remember(id) { container.sessions.backups(id) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var read by remember { mutableStateOf<Read?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var askingOff by remember { mutableStateOf(false) }
    var askingKeep by remember { mutableStateOf<Int?>(null) }
    var opened by remember { mutableStateOf<FileDatabaseBackups.Backup?>(null) }
    var askingDelete by remember { mutableStateOf<FileDatabaseBackups.Backup?>(null) }
    var askingDeleteAll by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reads++ }
    // A save from this page (its name and colour) may keep one: read again as the open database changes.
    container.sessions.state.collectAsStateWithLifecycle().value
    val state = container.sessions.session(id)?.session?.state?.let { flow -> key(flow) { flow.collectAsStateWithLifecycle().value } }
    LaunchedEffect(reads, state, record.backupsKept) { read = withContext(Dispatchers.IO) { Read(backups.list(), Instant.now()) } }
    val all = read?.backups.orEmpty()
    val count = all.size
    val zone = remember { ZoneId.systemDefault() }
    fun date(backup: FileDatabaseBackups.Backup) = BackupTexts.date(backup.at, read?.now ?: Instant.now(), zone)
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    fun change(work: () -> Unit, then: () -> Unit = {}) {
        scope.launch {
            withContext(Dispatchers.IO) { work() }
            reads++
            then()
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
                    change({ backups.deleteAll() })
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
                    change({ backups.prune(keep) })
                }) { Text("Delete $gone", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askingKeep = null }) { Text("Cancel") } },
        )
    }
    askingDelete?.let { backup ->
        val date = date(backup)
        AlertDialog(
            onDismissRequest = { askingDelete = null },
            title = { Text(BackupTexts.DELETE_TITLE) },
            text = { Text(BackupTexts.deleteText(date)) },
            confirmButton = {
                TextButton(onClick = {
                    askingDelete = null
                    var gone = false
                    change({ gone = backups.delete(backup.name) }) { if (gone) onNotice(Notice(BackupTexts.deleted(date))) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askingDelete = null }) { Text("Cancel") } },
        )
    }
    if (askingDeleteAll) {
        AlertDialog(
            onDismissRequest = { askingDeleteAll = false },
            title = { Text(BackupTexts.deleteAllTitle(count)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(BackupTexts.deleteAllText(name, count))
                    Text(BackupTexts.DELETE_ALL_NOTE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askingDeleteAll = false
                    expanded = false
                    change({ backups.deleteAll() })
                }) { Text(if (count == 1) "Delete" else "Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askingDeleteAll = false }) { Text("Cancel") } },
        )
    }
    opened?.let { backup ->
        BackupSheet(
            date = date(backup),
            detail = "${BackupTexts.ago(backup.at, read?.now ?: Instant.now(), zone)} · ${size(backup.bytes)}",
            damaged = backup.damaged,
            onDelete = {
                opened = null
                askingDelete = backup
            },
            onDismiss = { opened = null },
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
    read?.let { read ->
        Text(
            BackupTexts.lead(name, record.backupsOn, count, size(read.backups.sumOf { it.bytes })),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (count > 0) {
        val shown = if (expanded) all else all.take(SHOWN)
        Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
            shown.forEachIndexed { index, backup ->
                val date = date(backup)
                val detail = BackupTexts.detail(BackupTexts.ago(backup.at, read?.now ?: Instant.now(), zone), size(backup.bytes), backup.damaged)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clip(segmentShape(index, shown.size))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClickLabel = "Open") { opened = backup }
                        .semantics(mergeDescendants = true) { contentDescription = "Backup of $date, $detail" }
                        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(date, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (backup.damaged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column {
            if (count > SHOWN) {
                TextButton(onClick = { expanded = !expanded }) {
                    ButtonLabel(BackupTexts.more(count, expanded), if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, iconSize = 18.dp, gap = 8.dp)
                }
            }
            TextButton(onClick = { askingDeleteAll = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                ButtonLabel("Delete all backups…", Icons.Rounded.DeleteOutline, iconSize = 18.dp, gap = 8.dp)
            }
        }
    }
    Text(BackupTexts.WHERE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What the section read: the backups, newest first, and when, for how long ago each is. */
private class Read(val backups: List<FileDatabaseBackups.Backup>, val now: Instant)

/** How many the section shows before Show all N. */
private const val SHOWN = 3

/** A backup's sheet: its date, how long ago and its size, Delete…, and why a damaged one cannot be restored (AC5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSheet(date: String, detail: String, damaged: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(date, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (damaged) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(BackupTexts.DAMAGED, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text("Delete…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onDismiss, Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}
