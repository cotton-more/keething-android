package dev.keething.android.databases

import dev.keething.android.ui.withoutFullStop
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.keething.android.AppContainer
import dev.keething.android.R
import dev.keething.android.editor.describe
import dev.keething.android.storage.FileDatabaseBackups
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.Notice
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.UiText
import dev.keething.android.ui.afterColon
import dev.keething.android.ui.resolve
import dev.keething.android.ui.uiDate
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiPlural
import dev.keething.android.ui.uiText
import dev.keething.android.ui.segmentShape
import dev.keething.android.unlock.describe
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.session.RestoreOutcome
import java.io.File
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
    fun lead(name: String, on: Boolean, count: Int, size: String): UiText = when {
        !on -> if (count == 0) uiText(R.string.databases_backups_lead_off) else uiPlural(R.plurals.databases_backups_lead_off_kept, count)
        count == 0 -> uiText(R.string.databases_backups_lead_none, name)
        else -> uiPlural(R.plurals.databases_backups_lead_kept, count, count, size)
    }

    val WHERE: UiText = uiText(R.string.databases_backups_where)

    fun offTitle(name: String): UiText = uiText(R.string.databases_backups_off_title, name)

    fun offText(name: String, count: Int): UiText = uiPlural(R.plurals.databases_backups_off_text, count, name, count)

    fun offKeep(count: Int): UiText = uiPlural(R.plurals.databases_backups_off_keep, count)

    fun offDelete(count: Int): UiText = uiPlural(R.plurals.databases_backups_off_delete, count)

    fun fewerTitle(keep: Int): UiText = uiPlural(R.plurals.databases_backups_fewer_title, keep)

    fun fewerText(name: String, gone: Int): UiText = uiPlural(R.plurals.databases_backups_fewer_text, gone, gone, name)

    /** How many of [count] go when [keep] are kept; 0: nothing to ask (more kept keeps what comes). */
    fun gone(count: Int, keep: Int): Int = (count - keep).coerceAtLeast(0)

    /** A backup's date and time: "Today, 14:03", "Yesterday, 21:40", "Mon 9 Nov, 08:15", "Sun 9 Nov 2025, 08:15". */
    fun date(at: Instant, now: Instant, zone: ZoneId): UiText {
        val time = at.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        return when (time.toLocalDate()) {
            today -> uiText(R.string.databases_backups_date_today, TIME.format(time))
            today.minusDays(1) -> uiText(R.string.databases_backups_date_yesterday, TIME.format(time))
            else -> uiText(
                R.string.databases_backups_date_day,
                uiDate(time, if (time.year == today.year) R.string.databases_backups_day_pattern else R.string.databases_backups_day_year_pattern),
                TIME.format(time),
            )
        }
    }

    /**
     * How long ago, beside the date: "just now", "5 minutes ago", "17 hours ago" within a day, then the days by the
     * calendar ("3 days ago"), "5 weeks ago", "3 months ago", "2 years ago".
     */
    fun ago(at: Instant, now: Instant, zone: ZoneId): UiText {
        val seconds = Duration.between(at, now).seconds
        if (seconds < 60) return uiText(R.string.databases_backups_just_now)
        if (seconds < 3600) return ago(R.plurals.databases_backups_minutes_ago, seconds / 60)
        if (seconds < 24 * 3600) return ago(R.plurals.databases_backups_hours_ago, seconds / 3600)
        val days = ChronoUnit.DAYS.between(at.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate()).coerceAtLeast(1)
        return when {
            days < 14 -> ago(R.plurals.databases_backups_days_ago, days)
            days < 60 -> ago(R.plurals.databases_backups_weeks_ago, days / 7)
            days < 730 -> ago(R.plurals.databases_backups_months_ago, days / 30)
            else -> ago(R.plurals.databases_backups_years_ago, days / 365)
        }
    }

    /** Under a backup's date: how long ago and its size, or that it is damaged (keething-spec databases.backups-window AC5). */
    fun detail(ago: UiText, size: String, damaged: Boolean): UiText =
        if (damaged) uiText(R.string.databases_backups_detail_damaged, ago) else uiText(R.string.databases_backups_detail, ago, size)

    val DAMAGED: UiText = uiText(R.string.databases_backups_damaged)

    fun more(count: Int, expanded: Boolean): UiText =
        if (expanded) uiText(R.string.databases_backups_show_fewer) else uiPlural(R.plurals.databases_backups_show_all, count)

    val DELETE_TITLE: UiText = uiText(R.string.databases_backups_delete_title)

    fun deleteText(date: UiText): UiText = uiText(R.string.databases_backups_delete_text, date)

    fun deleted(date: UiText): UiText = uiText(R.string.databases_backups_deleted, date)

    fun deleteAllTitle(count: Int): UiText = uiPlural(R.plurals.databases_backups_delete_all_title, count)

    fun deleteAllText(name: String, count: Int): UiText = uiPlural(R.plurals.databases_backups_delete_all_text, count, count, name)

    val DELETE_ALL_NOTE: UiText = uiText(R.string.databases_backups_delete_all_note)

    /** The mark of the file as it was before a restore (keething-spec databases.backups-window AC6). */
    val BEFORE_RESTORING: UiText = uiText(R.string.databases_backups_before_restoring)

    fun restoreLine(name: String): UiText = uiText(R.string.databases_backups_restore_line, name)

    /** Why a backup is not offered for restoring in a read-only database (AC9). */
    fun readOnly(name: String): UiText = uiText(R.string.databases_backups_read_only, name)

    val RESTORE_TITLE: UiText = uiText(R.string.databases_backups_restore_title)

    fun restoreText(name: String, date: UiText): UiText = uiText(R.string.databases_backups_restore_text, name, onDate(date))

    val RESTORE_NOTE: UiText = uiText(R.string.databases_backups_restore_note)

    fun restoring(date: UiText): UiText = uiText(R.string.databases_backups_restoring, date)

    fun undoing(name: String): UiText = uiText(R.string.databases_backups_undoing, name)

    fun restored(date: UiText): UiText = uiText(R.string.databases_backups_restored, date)

    fun undone(name: String): UiText = uiText(R.string.databases_backups_undone, name)

    /**
     * Restored over an open database whose key the backup does not open with (AC7): it locked, and the fingerprint
     * waits for the password typed. [date]: the backup's, null when not known (an Undo's).
     */
    fun olderKey(name: String, date: UiText?): UiText =
        date?.let { uiText(R.string.databases_backups_older_key, name, onDate(it)) } ?: uiText(R.string.databases_backups_older_key_then, name)

    /** A restore that did not happen (AC8): the file and the backups as they were, and why. [file]: the database's. */
    fun notRestored(file: File, failure: SaveFailure): UiText = when (failure) {
        is SaveFailure.WriteFailed -> uiText(R.string.databases_backups_not_restored_write, file.name, failure.detail)
        SaveFailure.FileKeepsChanging -> uiText(R.string.databases_backups_not_restored_changing, file.name)
        SaveFailure.Locked -> uiText(R.string.databases_backups_not_restored_locked)
        is SaveFailure.CannotOpen ->
            uiText(R.string.databases_backups_not_restored_cannot_open, failure.failure.describe(file.path).afterColon())
        else -> uiText(R.string.databases_backups_not_restored, failure.describe(file).withoutFullStop())
    }

    /** A backup's [date] in a sentence: "on Mon 9 Nov, 08:15", but "today, 14:03" and "yesterday, 21:40". */
    private fun onDate(date: UiText): UiText = when {
        date is UiText.Res && date.id == R.string.databases_backups_date_today -> UiText.Res(R.string.databases_backups_on_today, date.args)
        date is UiText.Res && date.id == R.string.databases_backups_date_yesterday -> UiText.Res(R.string.databases_backups_on_yesterday, date.args)
        else -> uiText(R.string.databases_backups_on_date, date)
    }

    private fun ago(id: Int, n: Long): UiText = uiPlural(id, n.toInt())

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT) // not user text: digits only
}

/**
 * The database's page, Backups (keething-spec databases.backups-window AC1-AC9; the canvas "Database backups"): its
 * switch "Back up before changes", Keep 5 · 10 · 20 · 50 while it is on, how many there are and their size, then the
 * backups newest first (the newest three, Show all N for the rest in place, the file before a restore marked), each
 * opening a sheet with Restore this backup… and Delete…, and Delete all backups…. Open or locked alike: the settings
 * are the record's, never the file's, and nothing about a backup needs its key. Fewer asks first and deletes the
 * oldest at once; turning it off asks whether the backups stay or go. A restore asks once, then runs on the app's
 * scope with a line here while it does; done, it is said through [onNotice] with Undo ([onRestored] told, the page's
 * header shows the file's new time), a deletion so too; a failure,
 * or a database locked by a backup with an older key, is said here. Not offered for a damaged backup or in a
 * read-only database.
 */
@Composable
internal fun BackupsOfDatabase(container: AppContainer, id: DatabaseId, name: String, onNotice: (Notice) -> Unit, onRestored: () -> Unit = {}) {
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
    var askingRestore by remember { mutableStateOf<FileDatabaseBackups.Backup?>(null) }
    var restoring by remember { mutableStateOf<UiText?>(null) }
    var info by remember { mutableStateOf<UiText?>(null) }
    var problem by remember { mutableStateOf<UiText?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reads++ }
    // A save from this page (its name and colour) may keep one: read again as the open database changes.
    container.sessions.state.collectAsStateWithLifecycle().value
    val state = container.sessions.session(id)?.session?.state?.let { flow -> key(flow) { flow.collectAsStateWithLifecycle().value } }
    val readOnly = ReadOnlyReason.of(state) != null
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

    // [undo]: restoring the file as it was before the restore just done, said so; no Undo of an Undo.
    fun restore(backup: String, date: UiText?, undo: Boolean) {
        if (restoring != null) return
        restoring = if (undo) BackupTexts.undoing(name) else BackupTexts.restoring(date ?: uiPlain(""))
        info = null
        problem = null
        container.sessions.restoreBackup(id, backup) { outcome ->
            restoring = null
            reads++
            when (outcome) {
                is RestoreOutcome.Restored -> {
                    onRestored()
                    if (outcome.olderKey) info = BackupTexts.olderKey(name, date)
                    onNotice(
                        if (undo) {
                            Notice(BackupTexts.undone(name))
                        } else {
                            Notice(BackupTexts.restored(date ?: uiPlain("")), undo = { restore(outcome.beforeRestoring, date = null, undo = true) })
                        },
                    )
                }
                is RestoreOutcome.Failed -> problem = BackupTexts.notRestored(File(record.file.fileName), outcome.failure)
            }
        }
    }

    askingRestore?.let { backup ->
        val date = date(backup)
        AlertDialog(
            onDismissRequest = { askingRestore = null },
            title = { Text(BackupTexts.RESTORE_TITLE.resolve()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(BackupTexts.restoreText(name, date).resolve())
                    Text(BackupTexts.RESTORE_NOTE.resolve(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askingRestore = null
                    restore(backup.name, date, undo = false)
                }) { Text(stringResource(R.string.databases_backups_restore)) }
            },
            dismissButton = { TextButton(onClick = { askingRestore = null }) { Text(stringResource(R.string.databases_cancel)) } },
        )
    }
    if (askingOff) {
        AlertDialog(
            onDismissRequest = { askingOff = false },
            title = { Text(BackupTexts.offTitle(name).resolve()) },
            text = { Text(BackupTexts.offText(name, count).resolve()) },
            confirmButton = {
                TextButton(onClick = {
                    askingOff = false
                    list.update(id) { it.copy(backupsOn = false) }
                    change({ backups.deleteAll() })
                }) { Text(BackupTexts.offDelete(count).resolve(), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askingOff = false }) { Text(stringResource(R.string.databases_cancel)) }
                    TextButton(onClick = {
                        askingOff = false
                        list.update(id) { it.copy(backupsOn = false) }
                    }) { Text(BackupTexts.offKeep(count).resolve()) }
                }
            },
        )
    }
    askingKeep?.let { keep ->
        val gone = BackupTexts.gone(count, keep)
        AlertDialog(
            onDismissRequest = { askingKeep = null },
            title = { Text(BackupTexts.fewerTitle(keep).resolve()) },
            text = { Text(BackupTexts.fewerText(name, gone).resolve()) },
            confirmButton = {
                TextButton(onClick = {
                    askingKeep = null
                    list.update(id) { it.copy(backupsKept = keep) }
                    change({ backups.prune(keep) })
                }) { Text(pluralStringResource(R.plurals.databases_backups_fewer_delete, gone, gone), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askingKeep = null }) { Text(stringResource(R.string.databases_cancel)) } },
        )
    }
    askingDelete?.let { backup ->
        val date = date(backup)
        AlertDialog(
            onDismissRequest = { askingDelete = null },
            title = { Text(BackupTexts.DELETE_TITLE.resolve()) },
            text = { Text(BackupTexts.deleteText(date).resolve()) },
            confirmButton = {
                TextButton(onClick = {
                    askingDelete = null
                    var gone = false
                    change({ gone = backups.delete(backup.name) }) { if (gone) onNotice(Notice(BackupTexts.deleted(date))) }
                }) { Text(stringResource(R.string.databases_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askingDelete = null }) { Text(stringResource(R.string.databases_cancel)) } },
        )
    }
    if (askingDeleteAll) {
        AlertDialog(
            onDismissRequest = { askingDeleteAll = false },
            title = { Text(BackupTexts.deleteAllTitle(count).resolve()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(BackupTexts.deleteAllText(name, count).resolve())
                    Text(BackupTexts.DELETE_ALL_NOTE.resolve(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askingDeleteAll = false
                    expanded = false
                    change({ backups.deleteAll() })
                }) { Text(pluralStringResource(R.plurals.databases_backups_delete_all, count), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askingDeleteAll = false }) { Text(stringResource(R.string.databases_cancel)) } },
        )
    }
    opened?.let { backup ->
        BackupSheet(
            date = date(backup).resolve(),
            detail = BackupTexts.detail(BackupTexts.ago(backup.at, read?.now ?: Instant.now(), zone), size(backup.bytes), damaged = false).resolve(),
            damaged = backup.damaged,
            restoreLine = BackupTexts.restoreLine(name).resolve(),
            readOnly = if (readOnly) BackupTexts.readOnly(name).resolve() else null,
            restoring = restoring != null,
            onRestore = {
                opened = null
                askingRestore = backup
            },
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
        Text(stringResource(R.string.databases_backups_switch), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = record.backupsOn, onCheckedChange = null)
    }
    if (record.backupsOn) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.databases_backups_keep), style = MaterialTheme.typography.bodyLarge)
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
            BackupTexts.lead(name, record.backupsOn, count, size(read.backups.sumOf { it.bytes })).resolve(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    restoring?.let { text ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(text.resolve(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
    val note = info ?: if (readOnly && count > 0) BackupTexts.readOnly(name) else null
    note?.let { text ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text.resolve(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    problem?.let { text ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.errorContainer)
                .semantics { liveRegion = LiveRegionMode.Assertive }.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(text.resolve(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    if (count > 0) {
        val shown = if (expanded) all else all.take(SHOWN)
        Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
            shown.forEachIndexed { index, backup ->
                val date = date(backup).resolve()
                val detail = BackupTexts.detail(BackupTexts.ago(backup.at, read?.now ?: Instant.now(), zone), size(backup.bytes), backup.damaged).resolve()
                val openLabel = stringResource(R.string.databases_backups_open)
                val description = stringResource(
                    if (backup.beforeRestoring) R.string.databases_backups_row_before_restoring else R.string.databases_backups_row,
                    date,
                    detail,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clip(segmentShape(index, shown.size))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClickLabel = openLabel) { opened = backup }
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(date, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            if (backup.beforeRestoring) {
                                Text(
                                    BackupTexts.BEFORE_RESTORING.resolve(),
                                    Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
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
                    ButtonLabel(BackupTexts.more(count, expanded).resolve(), if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, iconSize = 18.dp, gap = 8.dp)
                }
            }
            TextButton(onClick = { askingDeleteAll = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                ButtonLabel(stringResource(R.string.databases_backups_delete_all_button), Icons.Rounded.DeleteOutline, iconSize = 18.dp, gap = 8.dp)
            }
        }
    }
    Text(BackupTexts.WHERE.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What the section read: the backups, newest first, and when, for how long ago each is. */
private class Read(val backups: List<FileDatabaseBackups.Backup>, val now: Instant)

/** How many the section shows before Show all N. */
private const val SHOWN = 3

/**
 * A backup's sheet: its date, how long ago and its size, Restore this backup… and Delete…; instead of Restore, why a
 * damaged one cannot be restored (AC5) or that a read-only database's are not ([readOnly], AC9). Restore waits while a
 * restore runs ([restoring]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSheet(
    date: String,
    detail: String,
    damaged: Boolean,
    restoreLine: String,
    readOnly: String?,
    restoring: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
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
            when {
                damaged -> Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(BackupTexts.DAMAGED.resolve(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                readOnly != null -> Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(readOnly, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = !restoring, onClick = onRestore)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val color = if (restoring) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface
                    Icon(Icons.Rounded.History, contentDescription = null, tint = color)
                    Column {
                        Text(stringResource(R.string.databases_backups_restore_this), style = MaterialTheme.typography.bodyLarge, color = color)
                        Text(restoreLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                Text(stringResource(R.string.databases_backups_delete_this), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onDismiss, Modifier.align(Alignment.End)) { Text(stringResource(R.string.databases_close)) }
        }
    }
}
