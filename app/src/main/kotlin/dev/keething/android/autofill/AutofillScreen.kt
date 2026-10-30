package dev.keething.android.autofill

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.AppContainer
import dev.keething.android.list.EntryRowView
import dev.keething.android.list.SearchField
import dev.keething.android.list.SectionTitle
import dev.keething.android.list.title
import dev.keething.search.EntrySearch
import dev.keething.android.ui.BarTitle
import dev.keething.android.ui.Notice
import dev.keething.android.ui.NoticeEffect
import dev.keething.android.ui.NoticeHost
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.rememberSecondsNow
import dev.keething.android.ui.segmentShape
import dev.keething.android.unlock.LockScreen
import dev.keething.domain.vault.Entry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** The unlock, then the entry picker; [target] names the app or page being filled. */
@Composable
internal fun AutofillScreen(
    container: AppContainer,
    request: AutofillRequest,
    target: String,
    answerAtOnce: (List<OpenVault>) -> Boolean,
    onFill: (Entry) -> Boolean,
    onClose: () -> Unit,
) {
    val database by container.sessions.current.collectAsStateWithLifecycle()
    val open by container.sessions.open.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        val current = database ?: return@Surface NoDatabase(onClose)
        // After the unlock: the one matching entry is filled at once; otherwise, or once picking, the user chooses.
        var picking by rememberSaveable { mutableStateOf(request.mode == AutofillMode.PICK) }
        LaunchedEffect(open) {
            if (open.isNotEmpty() && !picking && !answerAtOnce(open.map { OpenVault(it.database.id, it.unlocked.vault) })) picking = true
        }
        when {
            // None open: the current database's lock screen; any open one fills (keething-spec autofill.fill AC6).
            open.isEmpty() -> LockScreen(container, current, onBack = null, onChose = {})
            picking -> EntryPicker(container, request, target, onFill, onClose)
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
    request: AutofillRequest,
    target: String,
    onFill: (Entry) -> Boolean,
    onClose: () -> Unit,
) {
    val model = viewModel(key = "picker") {
        AutofillPickerModel(container.sessions.open, container.recents, container.autofillEntries, request)
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
                title = { BarTitle("Choose an entry", "to fill $target") },
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
                        onLongPress = null,
                        onCopyCode = null,
                        onOpenLabel = "Fill",
                        modifier = Modifier.padding(bottom = SegmentGap),
                    )
                }
                item(key = "gap/${section.kind}", contentType = "gap") { Spacer(Modifier.height(18.dp)) }
            }
            // Bare signs alone (`@`) list nothing and say nothing (SPEC §6.2).
            if (!state.preparing && state.sections.isEmpty() && !EntrySearch.isEmpty(model.query.text.toString())) {
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
