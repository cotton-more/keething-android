package dev.keething.android.databases

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.TextField
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.keething.android.editor.problem
import dev.keething.android.session.file
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.session.SessionState
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
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import dev.keething.android.ui.resolve
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import dev.keething.android.ui.NoticeHost
import dev.keething.android.ui.NoticeEffect
import dev.keething.android.ui.Notice
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.segmentShape
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.databases.DatabaseId
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiText

/**
 * Settings › Databases (SPEC §6.2; keething-spec databases.list AC7): every database with its name, its folder or
 * "not found", a key for one with a key file; a tap its page. Then Open a database….
 */
@Composable
internal fun DatabasesSetting(container: AppContainer, onDatabase: (DatabaseId) -> Unit) {
    val linesModel = viewModel(key = "database-lines") { DatabaseLinesModel(container.sessions.list, container.databaseFiles) }
    val screens by linesModel.screens.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { linesModel.refresh() }
    val files = rememberFileActions(container)
    val lines = screens?.lines.orEmpty()
    val fingerprint by container.sessions.biometric.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.databases_title), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
                            line.settingsDetail.resolve(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (line.isMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                    if (line.keyFile != null) {
                        Icon(Icons.Rounded.Key, contentDescription = stringResource(R.string.databases_change_key_key_file), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (fingerprint.enabled && line.usesFingerprint) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = stringResource(R.string.databases_chooser_fingerprint), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row {
            TextButton(onClick = files.open) {
                ButtonLabel(stringResource(R.string.databases_welcome_open), Icons.Rounded.FolderOpen, iconSize = 18.dp, gap = 8.dp)
            }
            TextButton(onClick = container.newDatabases::open) {
                ButtonLabel(stringResource(R.string.databases_new_database_button), Icons.Rounded.Add, iconSize = 18.dp, gap = 8.dp)
            }
        }
    }
}

/**
 * A database's page (SPEC §6.2): its path and when it changed (or was last opened, when it is not found, with
 * Locate…), its name and colour and its master key while it is open, its key file (Choose…, Change…, Forget), its Use
 * fingerprint (SPEC §5), and Remove from list…. Each acts at once.
 */
@Composable
internal fun DatabasePageScreen(container: AppContainer, id: DatabaseId, onBack: () -> Unit) {
    val databases = container.sessions.databases
    val linesModel = viewModel(key = "database-lines") { DatabaseLinesModel(container.sessions.list, container.databaseFiles) }
    val screens by linesModel.screens.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { linesModel.refresh() }
    val line = screens?.lines?.firstOrNull { it.id == id }
    // Removed (here, or meanwhile): the page has nothing to show.
    LaunchedEffect(screens, line) { if (screens != null && line == null) onBack() }
    if (line == null) return

    val context = LocalContext.current

    val resources = LocalResources.current
    var removing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val files = rememberFileActions(container, onProblem = { problem = it })
    val root = remember { Environment.getExternalStorageDirectory().path }
    val snackbar = remember { SnackbarHostState() }
    val notices = remember { Channel<Notice>(Channel.BUFFERED) }
    NoticeEffect(remember { notices.receiveAsFlow() }, snackbar)

    if (removing) {
        RemoveFromListDialog(
            name = line.name,
            onRemove = {
                removing = false
                container.sessions.remove(id) {
                    Toast.makeText(context.applicationContext, resources.getString(R.string.databases_page_stored_not_deleted, it), Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { removing = false },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = readableInset()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.databases_page_back)) }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DatabaseTile(line.initial, line.colorIndex, size = 64.dp)
                Spacer(Modifier.height(8.dp))
                DatabaseLabelText(line.label, MaterialTheme.typography.headlineSmall)
                Text(DatabaseText.abbreviated(line.path, root), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.MiddleEllipsis, maxLines = 2)
                changedText(line)?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (line.isMissing) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.FindInPage, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            (container.sessions.list.record(id)?.lockScreen(line)?.missingText?.resolve() ?: stringResource(R.string.databases_page_not_found_at, DatabaseText.abbreviated(line.path, root))),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    FilledTonalButton(onClick = { files.locate(id, line.reach == Reach.NO_ACCESS) }) {
                        ButtonLabel(stringResource(if (line.reach == Reach.NEEDS_FOLDER) R.string.databases_lock_choose_folder else R.string.databases_lock_locate), Icons.Rounded.Search, iconSize = 18.dp, gap = 8.dp)
                    }
                }
            }
            DatabaseChanges(container, id, line)
            Section(stringResource(R.string.databases_change_key_key_file)) {
                val key = line.keyFile
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.Key, contentDescription = null, tint = if (key?.isMissing == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(key?.name ?: stringResource(R.string.databases_page_key_file_none), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                key == null -> stringResource(R.string.databases_page_key_file_none_detail)
                                else -> key.detail.resolve()
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (key?.isMissing == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { files.chooseKeyFile(id) }) {
                        Text(stringResource(if (key == null) R.string.databases_page_choose else R.string.databases_new_change))
                    }
                    if (key != null) TextButton(onClick = { databases.setKeyFile(id, null) }) { Text(stringResource(R.string.databases_page_forget)) }
                }
                Text(
                    stringResource(R.string.databases_page_key_file_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section(stringResource(R.string.databases_chooser_fingerprint)) {
                FingerprintOfDatabase(container, id, line)
            }
            Section(stringResource(R.string.databases_page_backups)) {
                BackupsOfDatabase(container, id, line.name, onNotice = { notices.trySend(it) }, onRestored = linesModel::refresh)
            }
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { removing = true }) {
                    Icon(Icons.Rounded.PlaylistRemove, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.databases_remove_from_list), color = MaterialTheme.colorScheme.error)
                }
                Text(
                    stringResource(R.string.databases_page_remove_note),
                    Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        NoticeHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
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
        Text(stringResource(R.string.databases_page_use_fingerprint), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = on, onCheckedChange = null, enabled = changeable)
    }
    val record = container.sessions.list.record(id)
    val problem = deleteFailure?.let { stringResource(R.string.databases_page_fingerprint_not_deleted, it) }
        ?: state.setupFailures[id]?.takeIf { on }?.let { stringResource(R.string.databases_page_fingerprint_not_set_up, it) }
    Text(
        problem ?: when {
            unavailable != null -> stringResource(R.string.databases_page_fingerprint_unavailable, unavailable.reason)
            !state.enabled -> stringResource(R.string.databases_page_fingerprint_app_off)
            !on -> stringResource(R.string.databases_page_fingerprint_off)
            record?.isPasswordless == true -> stringResource(R.string.databases_page_fingerprint_passwordless)
            stored -> stringResource(R.string.databases_page_fingerprint_stored, stringResource(location.describe()))
            else -> stringResource(R.string.databases_page_fingerprint_next_unlock)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@StringRes
private fun KeyLocation?.describe(): Int = when (this) {
    KeyLocation.StrongBox -> R.string.databases_key_location_strongbox
    KeyLocation.TrustedEnvironment -> R.string.databases_key_location_tee
    KeyLocation.SecureHardware -> R.string.databases_key_location_secure_hardware
    KeyLocation.Software -> R.string.databases_key_location_software
    null -> R.string.databases_key_location_keystore
}

/** "Changed 2 hours ago"; of a file not found, "Last opened 2 weeks ago" when it was. */
private fun changedText(line: DatabaseLine): UiText? {
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    return if (line.isMissing) {
        line.lastOpened?.let { uiText(R.string.databases_page_last_opened, DatabaseText.ago(it, now, zone)) }
    } else {
        line.modified?.let { uiText(R.string.databases_page_changed, DatabaseText.ago(it, now, zone)) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

/**
 * Name and colour, and Master key (keething-spec databases.rename-recolour, databases.change-master-key; agreed on the
 * canvas "Database name, colour and master key"): both are written into the file, so only while it is open and can be
 * changed; locked or read only, why.
 */
@Composable
private fun DatabaseChanges(container: AppContainer, id: DatabaseId, line: DatabaseLine) {
    // A session comes and goes with the window: the page follows the app's state.
    container.sessions.state.collectAsStateWithLifecycle().value
    val database = container.sessions.session(id)
    val state = database?.session?.state?.let { flow -> key(flow) { flow.collectAsStateWithLifecycle().value } }
    val unlocked = state as? SessionState.Unlocked
    val readOnly = ReadOnlyReason.of(state)
    val record = container.sessions.list.record(id)
    Section(stringResource(R.string.databases_page_name_and_colour)) {
        when {
            unlocked == null || database == null -> LockedNote(stringResource(R.string.databases_page_unlock_to_change))
            readOnly != null -> LockedNote(stringResource(R.string.databases_page_read_only, readOnly.say.resolve()))
            else -> NameAndColor(database, unlocked, record?.file?.fileName ?: line.name)
        }
    }
    Section(stringResource(R.string.databases_page_master_key)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when {
                            record?.isPasswordless == true -> R.string.databases_page_key_file_alone
                            line.keyFile != null -> R.string.databases_page_password_and_key_file
                            else -> R.string.databases_chooser_password
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                unlocked?.vault?.masterKeyChanged?.let {
                    Text(
                        stringResource(R.string.databases_page_changed, DatabaseText.ago(it, Instant.now(), ZoneId.systemDefault()).resolve()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (unlocked != null && readOnly == null) {
            FilledTonalButton(onClick = { container.masterKeys.open(id) }) { Text(stringResource(R.string.databases_page_change_master_key)) }
        }
        Text(
            stringResource(R.string.databases_page_master_key_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LockedNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The name in a field, the nine colours and None, Save (keething-spec databases.rename-recolour AC1–AC5). */
@Composable
private fun NameAndColor(database: DatabaseSession, unlocked: SessionState.Unlocked, databaseFile: String) {
    val vault = unlocked.vault
    // What the file has; read again once a save (or a reload) brings it.
    val form = remember(vault.name, vault.color, databaseFile) { NameAndColorForm(vault.name, vault.color, databaseFile) }
    var name by remember(form) { mutableStateOf(form.fileName) }
    var color by remember(form) { mutableStateOf(form.fileColorIndex) }
    var saving by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val edit = form.edit(name, color)

    TextField(
        value = name,
        onValueChange = {
            name = it
            said = null
        },
        modifier = Modifier.keepOutOfAutofill().fillMaxWidth(),
        label = { Text(stringResource(R.string.databases_new_name)) },
        placeholder = { Text(databaseFile.substringBeforeLast('.')) },
        singleLine = true,
        enabled = !saving,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(20.dp),
        colors = fieldColors(),
    )
    Text(form.nameNote(name).resolve(), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val choices = DatabasePalette.shades.indices.map<Int, Int?> { it } + null
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        choices.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { choice -> ColorChoice(choice, selected = color == choice, enabled = !saving) { color = choice; said = null } }
            }
        }
    }
    Text(form.colorNote(color).resolve(), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                val asked = edit ?: return@Button
                val pending = database.session.save(asked)
                if (pending == null) {
                    said = resources.getString(R.string.databases_page_locked_not_saved)
                    failed = true
                    return@Button
                }
                saving = true
                scope.launch {
                    val problem = pending.await().problem(database.file)
                    saving = false
                    failed = problem != null
                    said = problem?.resolve(resources) ?: resources.getString(R.string.databases_page_saved_into, database.file.name)
                }
            },
            enabled = edit != null && !saving,
        ) { Text(stringResource(if (saving) R.string.databases_page_saving else R.string.databases_page_save)) }
        said?.let {
            Text(
                it,
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        stringResource(R.string.databases_page_written_into_file),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One of the nine colours (its dot), or None (a dashed ring): a ring around the chosen one, a check in it. */
@Composable
private fun ColorChoice(index: Int?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    val description = index?.let { NameAndColorForm.NAMES[it].resolve() } ?: stringResource(R.string.databases_page_color_none)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(2.dp, ring, CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (index != null) {
            val dot = databaseColors(index).dot
            Box(Modifier.size(34.dp).clip(CircleShape).background(dot), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(18.dp), tint = Color.White)
            }
        } else {
            Box(
                Modifier.size(34.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (selected) Icons.Rounded.Check else Icons.Rounded.Block,
                    contentDescription = null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
