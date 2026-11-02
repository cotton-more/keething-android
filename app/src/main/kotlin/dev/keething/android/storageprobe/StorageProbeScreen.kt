package dev.keething.android.storageprobe

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.keething.android.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageProbeScreen(container: AppContainer) {
    val viewModel: StorageProbeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                StorageProbeViewModel(container.fingerprintProbe, container::grantProbe)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val grantFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::grantFolder)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Storage probe") }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding(), 16.dp, padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("The provider in a granted folder (S3)") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ grantFolder.launch(null) }) { Text("Grant a folder") }
                        if (state.grantedFolder != null) FilledTonalButton(viewModel::caseTest) { Text("Case test") }
                        if (state.grantedFolder != null) FilledTonalButton(viewModel::missingTest) { Text("Missing test") }
                    }
                    state.grantedFolder?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                Section("Fingerprint (AD5 spike)") {
                    Text(
                        "Its own keys and fictitious passwords only, never the app's stored password.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val busy = state.fingerprintBusy
                        FilledTonalButton({ viewModel.fingerprint { setUp() } }, enabled = !busy) { Text("Set up") }
                        FilledTonalButton({ viewModel.fingerprint { promptEach() } }, enabled = !busy) { Text("(a) A prompt each") }
                        FilledTonalButton({ viewModel.fingerprint { reuseOperation() } }, enabled = !busy) { Text("(c1) Reuse") }
                        FilledTonalButton({ viewModel.fingerprint { timed(strongBox = true) } }, enabled = !busy) { Text("(b) StrongBox") }
                        FilledTonalButton({ viewModel.fingerprint { timed(strongBox = false) } }, enabled = !busy) { Text("(b) TEE") }
                        FilledTonalButton({ viewModel.fingerprint { chained() } }, enabled = !busy) { Text("(c5) Chained") }
                        FilledTonalButton({ viewModel.fingerprint { watchUnlocks(!state.watchingUnlocks) } }, enabled = !busy) {
                            Text(if (state.watchingUnlocks) "(b) Unlocks: stop" else "(b) Unlocks: watch")
                        }
                        FilledTonalButton({ viewModel.fingerprint { remove() } }, enabled = !busy) { Text("Remove") }
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
