package dev.keething.android.databases

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.AppContainer
import dev.keething.android.biometric.KeystorePasswordStores.KeyLocation
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.segmentShape
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.databases.DatabaseId
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Databases (SPEC §6.2; keething-spec databases.list AC7): every database with its name, its folder or
 * "not found", a key for one with a key file; a tap its page. Then Open a database….
 */
@Composable
internal fun DatabasesSetting(container: AppContainer, onDatabase: (DatabaseId) -> Unit) {
    val linesModel = viewModel(key = "database-lines") { DatabaseLinesModel(container.sessions.list) }
    val screens by linesModel.screens.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { linesModel.refresh() }
    val current by container.sessions.current.collectAsStateWithLifecycle()
    val open = rememberFilePicker { file -> container.sessions.databases.open(file.path) }
    val lines = screens?.lines.orEmpty()
    val fingerprint by container.sessions.biometric.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Databases", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Column {
            lines.forEachIndexed { index, line ->
                Row(
                    Modifier
                        .padding(bottom = SegmentGap)
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clip(segmentShape(index, lines.size, outer = 24.dp))
                        .background(MaterialTheme.extraColors.row)
                        .clickable { onDatabase(line.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DatabaseTile(line.initial, line.colorIndex)
                    Column(Modifier.weight(1f)) {
                        DatabaseLabelText(line.label, MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            line.settingsDetail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (line.isMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                    if (line.keyFile != null) {
                        Icon(Icons.Rounded.Key, contentDescription = "Key file", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (fingerprint.enabled && line.usesFingerprint) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = "Fingerprint", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // New database… comes with the New database screen (plan AD7).
        TextButton(onClick = { open(current?.file?.parentFile) }) {
            ButtonLabel("Open a database…", Icons.Rounded.FolderOpen, iconSize = 18.dp, gap = 8.dp)
        }
    }
}

/**
 * A database's page (SPEC §6.2): its path and when it changed (or was last opened, when it is not found, with
 * Locate…), its key file (Choose…, Change…, Forget), its Use fingerprint (SPEC §5), and Remove from list…. Each acts at
 * once.
 */
@Composable
internal fun DatabasePageScreen(container: AppContainer, id: DatabaseId, onBack: () -> Unit) {
    val databases = container.sessions.databases
    val linesModel = viewModel(key = "database-lines") { DatabaseLinesModel(container.sessions.list) }
    val screens by linesModel.screens.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { linesModel.refresh() }
    val line = screens?.lines?.firstOrNull { it.id == id }
    // Removed (here, or meanwhile): the page has nothing to show.
    LaunchedEffect(screens, line) { if (screens != null && line == null) onBack() }
    if (line == null) return

    val context = LocalContext.current
    var removing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val locate = rememberFilePicker { file -> problem = databases.locate(id, file.path) }
    val chooseKeyFile = rememberFilePicker { file -> databases.setKeyFile(id, file.path) }
    val root = remember { Environment.getExternalStorageDirectory().path }
    val folder = File(line.path).parentFile

    if (removing) {
        RemoveFromListDialog(
            name = line.name,
            onRemove = {
                removing = false
                container.sessions.remove(id) {
                    Toast.makeText(context.applicationContext, "The stored password could not be deleted: $it.", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { removing = false },
        )
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = readableInset()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Settings") }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DatabaseTile(line.initial, line.colorIndex, size = 64.dp)
            Spacer(Modifier.height(8.dp))
            DatabaseLabelText(line.label, MaterialTheme.typography.headlineSmall)
            Text(DatabaseText.abbreviated(line.path, root), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.MiddleEllipsis, maxLines = 2)
            changedText(line)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (line.isMissing) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.FindInPage, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Not found at ${DatabaseText.abbreviated(line.path, root)}", color = MaterialTheme.colorScheme.onErrorContainer)
                }
                FilledTonalButton(onClick = {
                    problem = null
                    locate(folder)
                }) { ButtonLabel("Locate…", Icons.Rounded.Search, iconSize = 18.dp, gap = 8.dp) }
            }
        }
        Section("Key file") {
            val key = line.keyFile
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = if (key?.isMissing == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(key?.name ?: "None", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            key == null -> "It opens with its password alone"
                            key.isMissing -> "not there · ${key.folder}"
                            else -> key.folder
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (key?.isMissing == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { chooseKeyFile(key?.path?.let { File(it).parentFile } ?: folder) }) {
                    Text(if (key == null) "Choose…" else "Change…")
                }
                if (key != null) TextButton(onClick = { databases.setKeyFile(id, null) }) { Text("Forget") }
            }
            Text(
                "Remembered here by its path, not its contents. It must be there at every unlock, the fingerprint too.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Section("Fingerprint") {
            FingerprintOfDatabase(container, id, line)
        }
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { removing = true }) {
                Icon(Icons.Rounded.PlaylistRemove, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(8.dp))
                Text("Remove from list…", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Forgets it on this phone, with its stored password. The file stays.",
                Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The database's own Use fingerprint (SPEC §5; keething-spec unlock.biometric AC10): on, the fingerprint opens it; off,
 * its stored password and its days go. Only while the app's switch, which allows it, is on.
 */
@Composable
private fun FingerprintOfDatabase(container: AppContainer, id: DatabaseId, line: DatabaseLine) {
    val setup = container.sessions.biometric
    val state by setup.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var deleteFailure by remember { mutableStateOf<String?>(null) }
    var location by remember { mutableStateOf<KeyLocation?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { setup.refresh() } }
    val stored = id in state.stored
    LaunchedEffect(stored) {
        location = if (stored) withContext(Dispatchers.IO) { runCatching { container.biometricStores.keyLocation() }.getOrNull() } else null
    }
    val on = state.enabled && line.usesFingerprint
    val unavailable = state.availability as? BiometricAvailability.Unavailable
    val changeable = on || (state.enabled && unavailable == null)
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = on,
            enabled = changeable,
            role = Role.Switch,
            onValueChange = { scope.launch { deleteFailure = setup.setUseBiometric(id, it) } },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Use fingerprint", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = on, onCheckedChange = null, enabled = changeable)
    }
    val record = container.sessions.list.record(id)
    val problem = deleteFailure?.let { "Its stored password could not be deleted: $it." }
        ?: state.setupFailures[id]?.takeIf { on }?.let { "It could not be set up: $it. It tries again at the next unlock with the password." }
    Text(
        problem ?: when {
            unavailable != null -> "Not available: ${unavailable.reason}."
            !state.enabled -> "Off: Unlock with the fingerprint is off in Settings. Once it is on, the fingerprint can be turned on here."
            !on -> "Off: its password is typed every time."
            record?.isPasswordless == true -> "The fingerprint opens it, and nothing is stored."
            setup.isDue(id) -> "Paused: it has been ${state.reminderDays} days, so the next unlock asks for its password."
            stored -> "Its password is stored, sealed by a key in ${location.describe()} that opens only after your fingerprint. " +
                "A fingerprint added on this phone undoes it."
            else -> "Its password is stored the next time it is unlocked with the password."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun KeyLocation?.describe(): String = when (this) {
    KeyLocation.StrongBox -> "the phone's security chip (StrongBox)"
    KeyLocation.TrustedEnvironment -> "the processor's secure area (TEE)"
    KeyLocation.SecureHardware -> "the phone's secure hardware"
    KeyLocation.Software -> "software (no secure hardware here)"
    null -> "the Android Keystore"
}

/** "Changed 2 hours ago"; of a file not found, "Last opened 2 weeks ago" when it was. */
private fun changedText(line: DatabaseLine): String? {
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    return if (line.isMissing) {
        line.lastOpened?.let { "Last opened " + DatabaseText.ago(it, now, zone) }
    } else {
        line.modified?.let { "Changed " + DatabaseText.ago(it, now, zone) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
