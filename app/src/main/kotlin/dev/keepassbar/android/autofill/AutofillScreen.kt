package dev.keepassbar.android.autofill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.list.EntryRowView
import dev.keepassbar.android.list.SearchField
import dev.keepassbar.android.list.SectionTitle
import dev.keepassbar.android.list.title
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.ui.Notice
import dev.keepassbar.android.ui.NoticeEffect
import dev.keepassbar.android.ui.NoticeHost
import dev.keepassbar.android.ui.SegmentGap
import dev.keepassbar.android.ui.rememberSecondsNow
import dev.keepassbar.android.ui.segmentShape
import dev.keepassbar.android.unlock.LockScreen
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.Vault
import dev.keepassbar.vault.session.SessionState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** The unlock, then the entry picker; [target] names the app or page being filled. */
@Composable
internal fun AutofillScreen(
    container: AppContainer,
    request: AutofillRequest,
    target: String,
    answerAtOnce: (Vault) -> Boolean,
    onFill: (Entry) -> Boolean,
    onClose: () -> Unit,
) {
    val database by container.sessions.current.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        val current = database ?: return@Surface NoDatabase(onClose)
        val state by current.session.state.collectAsStateWithLifecycle()
        val vault = (state as? SessionState.Unlocked)?.vault
        // After the unlock: the one matching entry is filled at once; otherwise, or once picking, the user chooses.
        var picking by rememberSaveable { mutableStateOf(request.mode == AutofillMode.PICK) }
        LaunchedEffect(vault) { if (vault != null && !picking && !answerAtOnce(vault)) picking = true }
        when {
            vault == null -> LockScreen(container, current, onChooseDatabase = {})
            picking -> EntryPicker(container, current, request, target, onFill, onClose)
            else -> Box(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun NoDatabase(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No database chosen yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Open Keething and choose the database first.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun EntryPicker(
    container: AppContainer,
    database: DatabaseSession,
    request: AutofillRequest,
    target: String,
    onFill: (Entry) -> Boolean,
    onClose: () -> Unit,
) {
    val model = viewModel(key = "picker:${database.number}") {
        AutofillPickerModel(database, container.recents, container.autofillEntries, request)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val notices = remember { Channel<Notice>(Channel.BUFFERED) }
    NoticeEffect(remember { notices.receiveAsFlow() }, snackbar)
    val now = rememberSecondsNow()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Choose an entry")
                        Text(
                            "to fill $target",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close without filling") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = { SearchField(model.query) },
        snackbarHost = { NoticeHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            if (state.preparing) {
                item(key = "preparing") {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            }
            for (section in state.sections) {
                item(key = "header/${section.kind}", contentType = "header") { SectionTitle(section.title()) }
                itemsIndexed(section.rows, key = { _, row -> "${section.kind}/${row.id}" }, contentType = { _, _ -> "row" }) { index, row ->
                    EntryRowView(
                        row = row,
                        shape = segmentShape(index, section.rows.size),
                        sharedKey = "${section.kind}/${row.id}",
                        icons = state.icons,
                        now = now,
                        onOpen = {
                            val entry = model.entry(row.id)
                            if (entry != null && !onFill(entry)) {
                                val what = if (request.form.asksOnlyForCode) "no one-time code" else "nothing for these fields"
                                notices.trySend(Notice("${entry.displayTitle} has $what", isProblem = true))
                            }
                        },
                        onCopyPassword = null,
                        onCopyCode = null,
                        onOpenLabel = "Fill",
                        modifier = Modifier.padding(bottom = SegmentGap),
                    )
                }
                item(key = "gap/${section.kind}", contentType = "gap") { Spacer(Modifier.height(18.dp)) }
            }
            if (!state.preparing && state.sections.isEmpty()) {
                item(key = "nothing") {
                    Text(
                        "Nothing matches “${model.query.text}”.",
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
