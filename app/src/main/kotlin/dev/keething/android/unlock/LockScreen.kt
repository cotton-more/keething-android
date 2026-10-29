package dev.keething.android.unlock

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import dev.keething.android.databases.DatabaseLine
import dev.keething.android.databases.DatabaseLinesModel
import dev.keething.android.databases.DatabaseTile
import dev.keething.android.databases.DatabasesSheet
import dev.keething.android.databases.KeyFileLine
import dev.keething.android.databases.RemoveFromListDialog
import dev.keething.android.databases.rememberFilePicker
import dev.keething.android.ui.AppLogo
import dev.keething.android.ui.ButtonLabel
import androidx.compose.foundation.text.input.clearText
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import java.io.File
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.AppContainer
import dev.keething.vault.databases.DatabaseSession
import dev.keething.android.storage.AllFilesAccess
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.ui.SecretTextMenu
import dev.keething.android.ui.dotsAtLineEnd
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import kotlin.math.roundToInt

/**
 * The lock screen (SPEC §6.2): the database (a tap: the list's databases), why it is locked, the fingerprint as the
 * large main button when it is set up (SPEC §5), and the password field at the bottom in thumb reach, with the
 * database's key file under it. A file not found has Locate… in place of the field; a database that opens with its
 * key file alone has Unlock. [onBack]: another database was chosen here, Back shows the one before; [onChose]: this
 * one gave way to another chosen here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LockScreen(container: AppContainer, database: DatabaseSession, onBack: (() -> Unit)?, onChose: (DatabaseId) -> Unit) {
    val databases = container.sessions.databases
    // Per session, not per file: a model of an earlier session of the same file would unlock that one.
    val model = viewModel(key = "lock:${database.number}") {
        LockViewModel(database, databases, container.sessions.list, container.sessions.fingerprintOffers::offer)
    }
    val linesModel = viewModel(key = "database-lines") { DatabaseLinesModel(container.sessions.list) }
    val screens by linesModel.screens.collectAsStateWithLifecycle()
    val lock = screens?.lockScreen?.takeIf { it.line.id == database.id }
    val session by model.session.collectAsStateWithLifecycle()
    val biometric by model.biometric.collectAsStateWithLifecycle()
    val fingerprintNotice by model.fingerprintNotice.collectAsStateWithLifecycle()
    val accessGranted by model.accessGranted.collectAsStateWithLifecycle()
    val timeout by container.settings.lockTimeout.collectAsStateWithLifecycle()
    val visit by container.sessions.visit.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        model.refresh()
        linesModel.refresh()
    }
    LaunchedEffect(visit) { model.offerFingerprint(visit) }
    // A failed unlock may have found the file gone (or back): the line looks again.
    LaunchedEffect(session) { if (session is SessionState.Locked) linesModel.refresh() }
    // Another database shown: what was typed here was meant for this one (its model stays until the activity goes).
    val leave = { model.password.clearText() }
    val back = onBack?.let { { leave(); it() } }
    BackHandler(enabled = back != null) { back?.invoke() }

    val context = LocalContext.current
    // Per session: nothing of this database's sheet, dialog or Locate… stays on the next one's lock screen.
    var sheet by remember(database.number) { mutableStateOf(false) }
    var removing by remember(database.number) { mutableStateOf(false) }
    // What the last Locate… could not do ("That file is already in the list as …").
    var locateProblem by remember(database.number) { mutableStateOf<String?>(null) }
    val folder = database.file.parentFile
    val openOther = rememberFilePicker { file ->
        if (container.sessions.list.record(database.id)?.let { DatabaseList.standardized(file.path) == it.path } != true) {
            leave()
            databases.open(file.path)
            onChose(database.id)
        }
    }
    val locate = rememberFilePicker { file -> locateProblem = databases.locate(database.id, file.path) }
    val chooseKeyFile = rememberFilePicker { file -> databases.setKeyFile(database.id, file.path) }

    if (sheet) {
        DatabasesSheet(
            lines = screens?.lines.orEmpty(),
            onChoose = { id ->
                sheet = false
                if (id != database.id) {
                    leave()
                    databases.choose(id)
                    onChose(database.id)
                }
            },
            onOpenOther = {
                sheet = false
                openOther(folder)
            },
            onRemove = {
                sheet = false
                removing = true
            },
            onDismiss = { sheet = false },
        )
    }
    if (removing) {
        RemoveFromListDialog(
            name = lock?.line?.name ?: database.file.name,
            onRemove = {
                removing = false
                leave()
                container.sessions.remove(database.id) {
                    Toast.makeText(context.applicationContext, "The stored password could not be deleted: $it.", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { removing = false },
        )
    }

    val reason = (session as? SessionState.Locked)?.reason
    // From the accepted fingerprint on: opening the stored password in StrongBox takes a moment before the
    // vault's own unlock starts.
    val unlocking = session is SessionState.Unlocking || biometric.opening

    val short = LocalConfiguration.current.screenHeightDp < 480
    // A wrong typed key shakes the field; a stored password the database rejected is not the user's typo.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(reason) {
        if ((reason as? LockReason.UnlockFailed)?.failure?.isWrongKey == true && !model.lastAttemptByFingerprint) {
            for (target in listOf(-18f, 14f, -8f, 4f, 0f)) shake.animateTo(target, spring(stiffness = 4_000f))
        }
    }

    // The database's identity, then the way in: one above the other; side by side on a screen less than 480 dp high
    // (a phone in landscape), where the keyboard leaves room for the field only. At most 480 dp wide on a tablet.
    val identity = @Composable {
        // With the keyboard up on a low screen one row is left: the database's, beside the field.
        if (!(short && WindowInsets.isImeVisible)) {
            AppLogo(Modifier.size(if (short) 72.dp else 104.dp))
            Spacer(Modifier.height(if (short) 12.dp else 24.dp))
            Text("Keething", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
        }
        DatabaseIdentity(lock?.line, database.file.name, onClick = { sheet = true })
        // The file's problems first (its key file not there; a file not found says it in place of the field); then
        // why the fingerprint is not offered; then why it is locked.
        val reasonNotice = reason?.notice(timeout, database.file)
        val dueNotice = biometric.reminderDays?.takeIf { biometric.isSetUp && biometric.passwordDue }?.let(::passwordDueNotice)
        val notice = locateProblem?.let { LockNotice(it, isProblem = true) }
            ?: when {
                lock?.line?.isMissing == true -> null
                lock?.problem != null -> LockNotice(lock.problem, isProblem = true)
                else -> fingerprintNotice ?: reasonNotice?.takeIf { it.isProblem } ?: dueNotice ?: reasonNotice
            }
        notice?.let {
            Spacer(Modifier.height(12.dp))
            ReasonChip(it)
        }
        if (!accessGranted) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { context.startActivity(AllFilesAccess.settingsIntent(context)) }) {
                Text("Allow all-files access")
            }
        }
    }
    val wayIn = @Composable {
        AnimatedContent(
            targetState = unlocking,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "unlocking",
        ) { busy ->
            when {
                busy -> Column(Modifier.fillMaxWidth().height(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ContainedLoadingIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Opening ${database.file.name}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Not known yet (the files are being looked at): nothing rather than a field that goes again.
                lock == null -> Spacer(Modifier.height(56.dp))
                lock.showsLocate -> NotFound(lock.missingText.orEmpty(), onLocate = {
                    locateProblem = null
                    locate(folder)
                })
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Typing hides it: the keyboard leaves no room, and the user chose the password.
                    AnimatedVisibility(visible = biometric.canUnlock && lock.offersFingerprint && !WindowInsets.isImeVisible) {
                        FingerprintButton(
                            enabled = accessGranted && !biometric.prompting,
                            // A database that opens with its key file alone has Unlock in place of the field.
                            otherWay = if (lock.showsPasswordField) "or type the password" else "or use its key file",
                            onClick = model::unlockWithFingerprint,
                        )
                    }
                    if (lock.showsPasswordField) {
                        PasswordRow(
                            model = model,
                            enabled = accessGranted,
                            placeholder = lock.passwordPlaceholder,
                            needsPassword = lock.needsPassword,
                            // Not while the fingerprint is offered (the keyboard would cover it), nor before it is known.
                            focusAtOnce = biometric.availability != null && !(biometric.canUnlock && lock.offersFingerprint),
                            modifier = Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
                        )
                    } else {
                        KeyFileAlone(enabled = accessGranted, onUnlock = model::unlock)
                    }
                    val keyFile = lock.line.keyFile
                    if (keyFile != null) {
                        KeyFileRow(keyFile, onChange = { chooseKeyFile(File(keyFile.path).parentFile ?: folder) })
                    } else if (lock.offersKeyFile) {
                        TextButton(onClick = { chooseKeyFile(folder) }, modifier = Modifier.padding(top = 4.dp)) {
                            ButtonLabel("Key file…", Icons.Rounded.Key, iconSize = 18.dp, gap = 8.dp)
                        }
                    }
                    // Offered while the app's switch allows it and this database's own is off (Settings turns the app's
                    // on; it turns none on itself); it stays once turned on, until the password is typed. A database that
                    // opens with its key file alone has it too: the fingerprint then opens it with nothing stored.
                    val available = biometric.availability == BiometricAvailability.Available
                    var offerSwitch by remember(database.number) { mutableStateOf(false) }
                    if (available && biometric.appEnabled && !biometric.databaseEnabled) offerSwitch = true
                    if (offerSwitch && available && biometric.appEnabled && !biometric.stored && lock.problem == null) {
                        UseFingerprintSwitch(checked = biometric.enabled, onChange = model::useFingerprint)
                    }
                }
            }
        }
    }
    val edges = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        .displayCutoutPadding()
        .padding(horizontal = 24.dp, vertical = if (short) 8.dp else 16.dp)
    Box(Modifier.fillMaxSize()) {
        if (short) {
            Row(edges, horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { identity() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { wayIn() }
            }
        } else {
            Column(edges.wrapContentWidth().widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                identity()
                Spacer(Modifier.weight(1f))
                wayIn()
            }
        }
        if (back != null) {
            IconButton(onClick = back, modifier = Modifier.statusBarsPadding().displayCutoutPadding().padding(4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        }
    }
}

/** A database that opens with its key file alone (keething-spec unlock.key-file AC5): nothing to type. */
@Composable
private fun KeyFileAlone(enabled: Boolean, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "It opens with its key file alone: nothing to type.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onUnlock, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            ButtonLabel("Unlock", Icons.Rounded.LockOpen, iconSize = 20.dp)
        }
    }
}

/** The database's key file under the field: its name and folder, or that it is not there, and Change… or Choose…. */
@Composable
private fun KeyFileRow(keyFile: KeyFileLine, onChange: () -> Unit) {
    val tint = if (keyFile.isMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Key, contentDescription = "Key file", Modifier.size(20.dp), tint = tint)
        Column(Modifier.weight(1f)) {
            Text(keyFile.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (keyFile.isMissing) "not there · ${keyFile.folder}" else keyFile.folder,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        TextButton(onClick = onChange) { Text(if (keyFile.isMissing) "Choose…" else "Change…") }
    }
}

/** A file not found (keething-spec databases.list AC5): said in place of the field, with Locate…. */
@Composable
private fun NotFound(text: String, onLocate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.FindInPage, contentDescription = null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onLocate) { ButtonLabel("Locate…", Icons.Rounded.Search, iconSize = 20.dp) }
        Text("or choose another database above", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UseFingerprintSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Fingerprint, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Use fingerprint next time", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun FingerprintButton(enabled: Boolean, otherWay: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(96.dp),
            shape = RoundedCornerShape(32.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Rounded.Fingerprint, contentDescription = "Unlock with fingerprint", Modifier.size(48.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Touch to unlock", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 32.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            Text(otherWay, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** The database's line (SPEC §6.2): its tile, name, folder and when it changed; a tap opens the list's databases. */
@Composable
private fun DatabaseIdentity(line: DatabaseLine?, fileName: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DatabaseTile(line?.initial ?: fileName.take(1).uppercase())
            // Weighted, so that with a large font the icon keeps its place beside the lines.
            Column(Modifier.weight(1f, fill = false)) {
                Text(line?.name ?: fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    line?.detail.orEmpty().dotsAtLineEnd(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (line?.isMissing == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Icon(Icons.Rounded.UnfoldMore, contentDescription = "Choose another database", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReasonChip(notice: LockNotice) {
    val container = if (notice.isProblem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (notice.isProblem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(container).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (notice.isProblem) Icons.Rounded.ErrorOutline else Icons.Rounded.Schedule, contentDescription = null, Modifier.size(16.dp), tint = content)
        Text(notice.text, style = MaterialTheme.typography.bodyMedium, color = content, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PasswordRow(
    model: LockViewModel,
    enabled: Boolean,
    placeholder: String,
    needsPassword: Boolean,
    focusAtOnce: Boolean,
    modifier: Modifier = Modifier,
) {
    var revealed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val passwordWanted by model.passwordWanted.collectAsStateWithLifecycle()
    val wantedBefore = remember { model.passwordWanted.value }
    LaunchedEffect(enabled, focusAtOnce) { if (enabled && focusAtOnce) focus.requestFocus() }
    // "Use password" in the fingerprint prompt, since this screen appeared.
    LaunchedEffect(passwordWanted) { if (enabled && passwordWanted > wantedBefore) focus.requestFocus() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Copying is off in a secure field; other apps' items in its menu ("Ask Gemini") go too.
        SecretTextMenu(copyable = false, modifier = Modifier.weight(1f)) {
            SecureTextField(
                state = model.password,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                enabled = enabled,
                placeholder = { Text(placeholder) },
                textStyle = if (revealed) MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily) else MaterialTheme.typography.bodyLarge,
                textObfuscationMode = if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                trailingIcon = {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (revealed) "Hide password" else "Show password",
                        )
                    }
                },
                // All of it, not only the action: a password field keeps the keyboard from capitalising, correcting
                // and learning what is typed.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                onKeyboardAction = { model.unlock() },
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
        }
        FilledIconButton(
            onClick = model::unlock,
            // Without a key file an empty field opens nothing (keething-spec unlock.lock-screen AC2).
            enabled = enabled && (!needsPassword || model.password.text.isNotEmpty()),
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(20.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Unlock")
        }
    }
}
