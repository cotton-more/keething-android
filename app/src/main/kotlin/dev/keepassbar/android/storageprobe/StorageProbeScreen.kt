package dev.keepassbar.android.storageprobe

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.storage.AllFilesAccess
import dev.keepassbar.android.storage.FolderPaths

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageProbeScreen(container: AppContainer) {
    val viewModel: StorageProbeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { StorageProbeViewModel(container.databaseStoreAt, container.probeSettings) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(FolderPaths::of)?.let(viewModel::chooseFolder)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.checkOnReturn() }

    Scaffold(topBar = { TopAppBar(title = { Text("Storage probe") }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding(), 16.dp, padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("Access") {
                    Text(if (state.allFilesAccess) "All-files access: granted" else "All-files access: NOT granted")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.allFilesAccess) {
                            Button({ context.startActivity(AllFilesAccess.settingsIntent(context)) }) { Text("Grant") }
                        }
                        FilledTonalButton({ pickFolder.launch(null) }) { Text("Choose folder") }
                    }
                    state.folder?.let { Text(it.path, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.databaseCandidates.forEach { name ->
                            FilledTonalButton({ viewModel.chooseDatabase(name) }) { Text(name) }
                        }
                    }
                }
            }
            state.database?.let { database ->
                item {
                    Section("Database") {
                        Text(database.path, style = MaterialTheme.typography.bodySmall)
                        val version = state.version
                        Text(
                            if (version == null) {
                                "no file"
                            } else {
                                "size ${version.size}\nmodified ${version.modified}\nid ${version.identity}\nsha ${state.lastRead}"
                            },
                            fontFamily = FontFamily.Monospace,
                        )
                        if (state.conflictCopies.isNotEmpty()) {
                            Text("Conflict copies:", style = MaterialTheme.typography.titleSmall)
                            state.conflictCopies.forEach { Text("${it.createdAt} ${it.deviceId}") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(viewModel::read) { Text("Read") }
                            FilledTonalButton(viewModel::rewriteDatabase) { Text("Rewrite") }
                            FilledTonalButton(viewModel::writeTestFile) { Text("Write test file") }
                        }
                    }
                }
            }
            item { Text("Log", style = MaterialTheme.typography.titleMedium) }
            items(state.log) { line ->
                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
