package dev.keething.android.unlock

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.material.icons.rounded.FolderOpen
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
import androidx.compose.ui.res.painterResource
import dev.keething.android.R
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
import dev.keething.android.session.DatabaseSession
import dev.keething.android.storage.AllFilesAccess
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.ui.SecretTextMenu
import dev.keething.android.ui.dotsAtLineEnd
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import kotlin.math.roundToInt

/**
 * The lock screen (SPEC §6.2): the database, why it is locked, the fingerprint as the large main button
 * when it is set up (SPEC §5), and the password field at the bottom in thumb reach.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LockScreen(container: AppContainer, database: DatabaseSession, onChooseDatabase: () -> Unit) {
    // Per session, not per file: a model of an earlier session of the same file would unlock that one.
    val model = viewModel(key = "lock:${database.number}") { LockViewModel(database) }
    val session by model.session.collectAsStateWithLifecycle()
    val biometric by model.biometric.collectAsStateWithLifecycle()
    val fingerprintNotice by model.fingerprintNotice.collectAsStateWithLifecycle()
    val fileInfo by model.fileInfo.collectAsStateWithLifecycle()
    val accessGranted by model.accessGranted.collectAsStateWithLifecycle()
    val timeout by container.settings.lockTimeout.collectAsStateWithLifecycle()
    val visit by container.sessions.visit.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
    LaunchedEffect(visit) { model.offerFingerprint(visit) }

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
        DatabaseLine(database, fileInfo, onChooseDatabase)
        // A problem first; then why the fingerprint is not offered; then why it is locked.
        val reasonNotice = reason?.notice(timeout, database.file)
        val dueNotice = biometric.reminderDays?.takeIf { biometric.isSetUp && biometric.passwordDue }?.let(::passwordDueNotice)
        (fingerprintNotice ?: reasonNotice?.takeIf { it.isProblem } ?: dueNotice ?: reasonNotice)?.let { notice ->
            Spacer(Modifier.height(12.dp))
            ReasonChip(notice)
        }
        if (!accessGranted) {
            Spacer(Modifier.height(12.dp))
            val context = LocalContext.current
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
            if (busy) {
                Column(Modifier.fillMaxWidth().height(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ContainedLoadingIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Opening ${database.file.name}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Typing hides it: the keyboard leaves no room, and the user chose the password.
                    AnimatedVisibility(visible = biometric.canUnlock && !WindowInsets.isImeVisible) {
                        FingerprintButton(enabled = accessGranted && !biometric.prompting, onClick = model::unlockWithFingerprint)
                    }
                    PasswordRow(
                        model = model,
                        enabled = accessGranted,
                        // Not while the fingerprint is offered (the keyboard would cover it), nor before it is known.
                        focusAtOnce = biometric.availability != null && !biometric.canUnlock,
                        modifier = Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
                    )
                    // Offered while the fingerprint is off; it stays once turned on, until the password is typed.
                    val available = biometric.availability == BiometricAvailability.Available
                    var offerSwitch by remember { mutableStateOf(false) }
                    if (available && !biometric.enabled) offerSwitch = true
                    if (offerSwitch && available && !biometric.stored) {
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
private fun FingerprintButton(enabled: Boolean, onClick: () -> Unit) {
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
            Text("or type the password", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun DatabaseLine(database: DatabaseSession, fileInfo: FileInfo?, onChooseDatabase: () -> Unit) {
    Surface(onClick = onChooseDatabase, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Weighted, so that with a large font the folder icon keeps its place beside the lines.
            Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(database.file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val folder = database.file.parentFile?.name.orEmpty()
                val detail = when {
                    fileInfo == null -> folder
                    !fileInfo.exists -> "$folder · not there"
                    else -> "$folder · changed " + changedAgo(fileInfo.modified!!.toEpochMilli())
                }
                Text(detail.dotsAtLineEnd(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.size(8.dp))
            Icon(
                Icons.Rounded.FolderOpen,
                contentDescription = "Choose another database",
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun changedAgo(millis: Long): String {
    val now = System.currentTimeMillis()
    if (now - millis < DateUtils.MINUTE_IN_MILLIS) return "just now"
    return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString().replaceFirstChar(Char::lowercase)
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
private fun PasswordRow(model: LockViewModel, enabled: Boolean, focusAtOnce: Boolean, modifier: Modifier = Modifier) {
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
                placeholder = { Text("Master password") },
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
            enabled = enabled,
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

/**
 * The launcher icon's two layers, cut as the launcher cuts them: the middle 72 of their 108 dp, in a rounded square.
 */
@Composable
private fun AppLogo(modifier: Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(30)), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(maxWidth * 108 / 72)
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, layer)
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, layer)
    }
}
