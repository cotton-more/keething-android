package dev.keething.android.history

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
import dev.keething.android.session.DatabaseSession
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

/**
 * An entry's earlier versions (SPEC §6.2; Mac SPEC §3.22 without restoring): the file is read again with the key
 * in memory, keeping this entry's history. The versions hold its secrets: wiped when the screen goes (a lock
 * closes it).
 */
internal class EntryHistoryViewModel(database: DatabaseSession, entryId: String, sizeText: (Int?) -> String) : ViewModel() {
    var state: HistoryState by mutableStateOf(HistoryState.Loading)
        private set

    /** "versionId/field" of the protected values shown. */
    val revealed = SnapshotStateSet<String>()

    private var read: EntryVersions? = null

    init {
        viewModelScope.launch {
            val result = database.session.history(entryId) ?: return@launch
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
                        else -> failure.describe(database.file)
                    },
                )
            }
        }
    }

    fun toggle(key: String) {
        if (!revealed.remove(key)) revealed.add(key)
    }

    override fun onCleared() {
        read?.wipe()
        read = null
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val WHEN: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun dayText(instant: Instant): String = DAY.format(instant.atZone(ZoneId.systemDefault()))

private fun sizeText(context: Context, bytes: Int?): String =
    bytes?.let { Formatter.formatShortFileSize(context, it.toLong()) } ?: "size unknown"

/**
 * The versions newest first, the current one marked, each with what it changed from the one before: old values
 * struck through above the new, protected ones masked with an eye each. The oldest has nothing to compare with.
 */
@Composable
internal fun EntryHistoryScreen(database: DatabaseSession, entryId: String, onBack: () -> Unit) {
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
                        VersionCard(version, isRevealed = { "${version.id}/$it" in model.revealed }, onToggle = { model.toggle("${version.id}/$it") })
                    }
                }
            }
        }
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
private fun VersionCard(version: HistoryVersion, isRevealed: (field: String) -> Boolean, onToggle: (field: String) -> Unit) {
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
