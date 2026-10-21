package dev.keepassbar.android.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.storage.AllFilesAccess
import dev.keepassbar.android.storage.FolderPaths
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.segmentShape
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SetupState(
    val accessGranted: Boolean = false,
    val folder: File? = null,
    /** The `.kdbx` files in [folder], Syncthing's conflict copies left out. */
    val databases: List<String> = emptyList(),
    /** The folder could not be read as a path (not on the phone's shared storage). */
    val folderProblem: Boolean = false,
)

internal class SetupViewModel(current: File?) : ViewModel() {
    private val mutableState = MutableStateFlow(SetupState(folder = current?.parentFile))
    val state: StateFlow<SetupState> = mutableState.asStateFlow()

    init {
        current?.parentFile?.let(::chooseFolder)
    }

    fun refresh() {
        mutableState.update { it.copy(accessGranted = AllFilesAccess.isGranted()) }
        state.value.folder?.let(::chooseFolder)
    }

    private var listing: Job? = null

    fun chooseFolder(folder: File?) {
        if (folder == null) return mutableState.update { it.copy(folderProblem = true) }
        // The folder is the chosen one at once, and only the latest listing counts: coming back from the
        // picker refreshes right after the choice, and the refresh must list the new folder, not the old one.
        val changed = folder != state.value.folder
        mutableState.update { it.copy(folder = folder, databases = if (changed) emptyList() else it.databases, folderProblem = false) }
        listing?.cancel()
        listing = viewModelScope.launch {
            val names = withContext(Dispatchers.IO) {
                folder.list().orEmpty().filter { it.endsWith(".kdbx", ignoreCase = true) && ".sync-conflict-" !in it }.sorted()
            }
            mutableState.update { it.copy(databases = names) }
        }
    }
}

/**
 * Choosing the database (SPEC §3.1): all-files access first, then the Syncthing folder in the system
 * picker, then the file. The app remembers the path, not the picker's grant.
 */
@Composable
internal fun SetupScreen(container: AppContainer, onChosen: () -> Unit, onBack: (() -> Unit)?) {
    val current = container.sessions.current.value?.file
    val model = viewModel(key = "setup") { SetupViewModel(current) }
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.chooseFolder(FolderPaths.of(uri))
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        }
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose the database", style = MaterialTheme.typography.headlineMedium)
            Text(
                "KeePassBar opens the database in the folder Syncthing keeps in sync, by its path: a new version from " +
                    "Syncthing is simply the file at that path.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Step(number = 1, title = "Allow all-files access", done = state.accessGranted) {
            Text(
                "Android asks for it to let an app read a file in a shared folder by its path. KeePassBar has no internet access.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.accessGranted) {
                Button(onClick = { context.startActivity(AllFilesAccess.settingsIntent(context)) }) { Text("Allow") }
            }
        }
        Step(number = 2, title = "Choose the Syncthing folder", done = state.folder != null && !state.folderProblem) {
            state.folder?.let { Text(it.path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.folderProblem) {
                Text("Choose a folder on the phone's storage.", color = MaterialTheme.colorScheme.error)
            }
            FilledTonalButton(onClick = { pickFolder.launch(null) }, enabled = state.accessGranted) {
                Text(if (state.folder == null) "Choose folder" else "Choose another folder")
            }
        }
        if (state.folder != null) {
            Step(number = 3, title = "Choose the file", done = false) {
                if (state.databases.isEmpty()) {
                    Text("No .kdbx file in this folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.databases.forEachIndexed { index, name ->
                        val file = File(state.folder, name)
                        Surface(
                            onClick = {
                                container.sessions.chooseDatabase(file)
                                onChosen()
                            },
                            shape = segmentShape(index, state.databases.size, outer = 16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(16.dp))
                                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                if (file == current) Icon(Icons.Rounded.Check, contentDescription = "Open now")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Step(number: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(32.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (done) Icon(Icons.Rounded.Check, contentDescription = "Done", Modifier.size(18.dp)) else Text("$number")
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
