package dev.keething.android.history

import dev.keething.android.session.file
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.keething.android.editor.SAVE_LOCKED
import dev.keething.android.editor.problem
import dev.keething.card.history.EntryVersionDiff
import dev.keething.domain.vault.VaultEdit
import dev.keething.vault.session.SessionState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.ui.spokenMasked
import dev.keething.vault.databases.DatabaseSession
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.BarTitle
import dev.keething.android.ui.readableInset
import dev.keething.android.unlock.describe
import dev.keething.card.history.HistoryVersion
import dev.keething.card.history.HistoryVersions
import dev.keething.card.history.VersionChange
import dev.keething.vault.opening.EntryVersions
import dev.keething.vault.opening.HistoryResult
import dev.keething.vault.opening.OpenFailure
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface HistoryState {
    data object Loading : HistoryState

    /** Newest first; [title] is the entry's title as the file has it now. */
    data class Loaded(val title: String, val versions: List<HistoryVersion>) : HistoryState

    data class Failed(val problem: String) : HistoryState
}

/** Whether the versions can be restored (keething-spec edit.history-restore AC1), and why not. */
internal enum class Restorable { YES, IN_RECYCLE_BIN, READ_ONLY }

/** The restore asked about: the [version], what it changes from the current one, and what the history then loses. */
internal class RestoreQuestion(val version: HistoryVersion, val changes: List<VersionChange>, val trimmed: String?)

/**
 * An entry's earlier versions (SPEC §6.2; Mac SPEC §3.22): the file is read again with the key in memory, keeping this
 * entry's history. Any version but the current one can be restored (keething-spec edit.history-restore), asked once
 * with what it changes; a restore is a save in its turn. The versions hold its secrets: wiped when the screen goes (a
 * lock closes it).
 */
internal class EntryHistoryViewModel(
    private val database: DatabaseSession,
    private val entryId: String,
    private val sizeText: (Int?) -> String,
) : ViewModel() {
    var state: HistoryState by mutableStateOf(HistoryState.Loading)
        private set

    /** "versionId/field" of the protected values shown; "ask/field" in the question. */
    val revealed = SnapshotStateSet<String>()

    /** Read when the screen opens: the entry in the recycle bin or the database read-only offer no restore. */
    val restorable: Restorable = (database.session.state.value as? SessionState.Unlocked).let { unlocked ->
        when {
            unlocked == null || unlocked.readOnly -> Restorable.READ_ONLY
            unlocked.vault.recycleBin.any { it.id == entryId } -> Restorable.IN_RECYCLE_BIN
            else -> Restorable.YES
        }
    }

    var question: RestoreQuestion? by mutableStateOf(null)
        private set

    /** The version being restored (its [HistoryVersion.id]). */
    var restoring: Int? by mutableStateOf(null)
        private set

    /** Why the last restore did nothing. */
    var problem: String? by mutableStateOf(null)
        private set

    private var read: EntryVersions? = null

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val result = database.session.history(entryId) ?: return@launch
            read?.wipe()
            read = null
            revealed.clear()
            state = when (result) {
                is HistoryResult.Read -> {
                    read = result.versions
                    val versions = result.versions
                    val list = withContext(Dispatchers.Default) {
                        HistoryVersions.of(versions.history + versions.current, day = ::dayText, size = sizeText)
                    }
                    HistoryState.Loaded(versions.current.displayTitle, list)
                }
                is HistoryResult.Failed -> HistoryState.Failed(
                    when (val failure = result.failure) {
                        null -> "This entry is no longer in the file."
                        OpenFailure.WrongPassword -> "The database's password was changed since the unlock."
                        else -> failure.describe(database.file.path)
                    },
                )
            }
        }
    }

    fun toggle(key: String) {
        if (!revealed.remove(key)) revealed.add(key)
    }

    /** Asks before going back to [version]: what it changes, and which old versions the history then drops. */
    fun ask(version: HistoryVersion) {
        val versions = read ?: return
        if (restorable != Restorable.YES || version.isCurrent || restoring != null) return
        problem = null
        revealed.removeAll { it.startsWith("ask/") }
        val changes = EntryVersionDiff.changes(versions.current, version.entry, ::dayText, sizeText)
        question = RestoreQuestion(version, changes, trimmedText(versions, version.id))
    }

    fun cancel() {
        question = null
    }

    /** Restores the version asked about; [done] gets what to say once it is saved. */
    fun confirm(done: (String) -> Unit) {
        val asked = question ?: return
        val versions = read ?: return
        question = null
        val version = asked.version
        val pending = database.session.save(
            VaultEdit.RestoreVersion(entryId, versions.current.lastModified, version.id, version.entry.lastModified),
        )
        if (pending == null) {
            problem = SAVE_LOCKED
            return
        }
        restoring = version.id
        viewModelScope.launch {
            val outcome = pending.await()
            restoring = null
            when (val failure = outcome.problem(database.file)) {
                null -> done("Back to the version of ${whenText(version.entry.lastModified)}")
                else -> {
                    problem = failure
                    // Changed elsewhere or not, the versions shown are read again.
                    load()
                }
            }
        }
    }

    override fun onCleared() {
        read?.wipe()
        read = null
    }
}

/**
 * What the history loses when a restore puts the current version into it (`HistoryMaxItems`): the oldest, said by its
 * [date]; the version restored ([index]) itself among them leaves the history as it becomes the current one. Null when
 * nothing goes.
 */
internal fun trimmedText(versions: EntryVersions, index: Int, date: (Instant?) -> String = ::whenText): String? {
    val max = versions.maxItems
    val gone = versions.history.size + 1 - max
    if (max < 0 || gone <= 0) return null
    val kept = if (max == 1) "1 earlier version" else "$max earlier versions"
    val leaves = index < gone
    return when {
        gone == 1 && leaves -> "This database keeps $kept: this one leaves the history as it becomes the current one."
        gone == 1 -> "This database keeps $kept, so the oldest, from ${date(versions.history[0].lastModified)}, goes."
        else -> "This database keeps $kept, so the $gone oldest go, the newest of them from " +
            "${date(versions.history[gone - 1].lastModified)}." + if (leaves) " This one becomes the current one." else ""
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val WHEN: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun dayText(instant: Instant): String = DAY.format(instant.atZone(ZoneId.systemDefault()))

private fun whenText(instant: Instant?): String = instant?.let { WHEN.format(it.atZone(ZoneId.systemDefault())) } ?: "a date not known"

private fun sizeText(context: Context, bytes: Int?): String =
    bytes?.let { Formatter.formatShortFileSize(context, it.toLong()) } ?: "size unknown"

/**
 * The versions newest first, the current one marked, each with what it changed from the one before: old values
 * struck through above the new, protected ones masked with an eye each. The oldest has nothing to compare with. Every
 * version but the current one has Go back to this version… (keething-spec edit.history-restore; the canvas "Restoring a
 * version"), asked once with what it changes; [onRestored] gets what to say once it is saved.
 */
@Composable
internal fun EntryHistoryScreen(database: DatabaseSession, entryId: String, onBack: () -> Unit, onRestored: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val model = viewModel { EntryHistoryViewModel(database, entryId) { sizeText(context, it) } }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val state = model.state
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { BarTitle("History", (state as? HistoryState.Loaded)?.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to the entry") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = readableInset(),
                end = readableInset(),
                top = padding.calculateTopPadding() + 4.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val loaded = state as? HistoryState.Loaded
            if (loaded != null && loaded.versions.size > 1) {
                when (model.restorable) {
                    Restorable.YES -> {}
                    Restorable.IN_RECYCLE_BIN -> item(key = "why") { Info("In the recycle bin: restore the entry first to go back to one of its versions.") }
                    Restorable.READ_ONLY -> item(key = "why") { Info("This database is read only on this phone: its versions are shown, not restored.") }
                }
            }
            model.problem?.let { problem -> item(key = "problem") { Problem(problem) } }
            when (state) {
                HistoryState.Loading -> item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator()
                        Text("Reading the file…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is HistoryState.Failed -> item(key = "failed") { Note(state.problem) }
                is HistoryState.Loaded -> {
                    if (state.versions.size == 1) item(key = "none") { Note("No earlier versions are kept for this entry.") }
                    items(state.versions, key = { it.id }) { version ->
                        VersionCard(
                            version,
                            isRevealed = { "${version.id}/$it" in model.revealed },
                            onToggle = { model.toggle("${version.id}/$it") },
                            restore = when {
                                model.restorable != Restorable.YES || version.isCurrent -> null
                                model.restoring == version.id -> RestoreButton.WORKING
                                model.restoring != null -> RestoreButton.OFF
                                else -> RestoreButton.READY
                            },
                            onRestore = { model.ask(version) },
                        )
                    }
                }
            }
        }
    }
    model.question?.let { question ->
        RestoreDialog(
            question,
            title = (state as? HistoryState.Loaded)?.title ?: "The entry",
            isRevealed = { "ask/$it" in model.revealed },
            onToggle = { model.toggle("ask/$it") },
            onConfirm = { model.confirm(onRestored) },
            onDismiss = model::cancel,
        )
    }
}

private enum class RestoreButton { READY, WORKING, OFF }

/** Go back to this version?: what the entry becomes and every change that undoes, then what the history loses. */
@Composable
private fun RestoreDialog(
    question: RestoreQuestion,
    title: String,
    isRevealed: (field: String) -> Boolean,
    onToggle: (field: String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go back to this version?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "$title becomes as it was on ${whenText(question.version.entry.lastModified)}, with everything changed since undone. " +
                        "The current version goes into the history, so you can come back to it the same way.",
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.extraColors.row)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "What changes",
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (question.changes.isEmpty()) {
                        Text(
                            "Nothing shown here (an icon, a colour or auto-type, for example).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    question.changes.forEach { change -> ChangeRow(change, isRevealed(change.field)) { onToggle(change.field) } }
                }
                question.trimmed?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Go back") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Info(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Problem(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
        Text("Not restored: $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VersionCard(
    version: HistoryVersion,
    isRevealed: (field: String) -> Boolean,
    onToggle: (field: String) -> Unit,
    restore: RestoreButton?,
    onRestore: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.extraColors.row)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                version.entry.lastModified?.let { WHEN.format(it.atZone(ZoneId.systemDefault())) } ?: "Date not known",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            if (version.isCurrent) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        "Current",
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        val changes = version.changes
        when {
            changes == null -> Text(
                "The oldest version kept: nothing to compare it with.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            changes.isEmpty() -> Text(
                "Saved without a change that is shown here (an icon, a colour or auto-type, for example).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> changes.forEach { change -> ChangeRow(change, isRevealed(change.field)) { onToggle(change.field) } }
        }
        if (restore != null) {
            val date = version.entry.lastModified?.let { WHEN.format(it.atZone(ZoneId.systemDefault())) }
            FilledTonalButton(
                onClick = onRestore,
                enabled = restore == RestoreButton.READY,
                modifier = Modifier.align(Alignment.End).semantics {
                    if (date != null && restore != RestoreButton.WORKING) contentDescription = "Go back to the version of $date"
                },
                contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
            ) {
                if (restore == RestoreButton.WORKING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.History, contentDescription = null, Modifier.size(18.dp))
                }
                Text(if (restore == RestoreButton.WORKING) "Going back…" else "Go back to this version…", Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ChangeRow(change: VersionChange, revealed: Boolean, onToggle: () -> Unit) {
    val masked = change.isProtected && (change.old != null || change.new != null)
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${change.field} · ${change.kindText()}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            change.old?.let { ValueText(it, change.isProtected, masked && !revealed, struck = true) }
            change.new?.let { ValueText(it, change.isProtected, masked && !revealed, struck = false) }
        }
        if (masked) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (revealed) "Hide ${change.field}" else "Show ${change.field}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ValueText(value: String, secret: Boolean, masked: Boolean, struck: Boolean) {
    Text(
        when {
            masked -> "••••••"
            value.isEmpty() -> "(empty)"
            else -> value
        },
        if (masked) Modifier.spokenMasked("••••••") else Modifier,
        style = MaterialTheme.typography.bodyMedium.copy(
            // A secret shown is monospaced, as on the card.
            fontFamily = if (secret && !masked) MonospaceFamily else null,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
        ),
        color = if (struck) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun VersionChange.kindText(): String = when (kind) {
    VersionChange.Kind.ADDED -> "added"
    VersionChange.Kind.REMOVED -> "removed"
    VersionChange.Kind.CHANGED -> "changed"
}
