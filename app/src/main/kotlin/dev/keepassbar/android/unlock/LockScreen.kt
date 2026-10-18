package dev.keepassbar.android.unlock

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
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
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.storage.AllFilesAccess
import dev.keepassbar.android.theme.MonospaceFamily
import dev.keepassbar.vault.opening.OpenFailure
import dev.keepassbar.vault.session.LockReason
import dev.keepassbar.vault.session.SessionState
import kotlin.math.roundToInt

/**
 * The lock screen (SPEC §6.2): the database, why it is locked, and the password field at the bottom in
 * thumb reach. The fingerprint, the large main button of the agreed design, comes with step 3.
 */
@Composable
internal fun LockScreen(container: AppContainer, database: DatabaseSession, onChooseDatabase: () -> Unit) {
    val model = viewModel(key = "lock:${database.file.path}") { LockViewModel(database) }
    val session by model.session.collectAsStateWithLifecycle()
    val fileInfo by model.fileInfo.collectAsStateWithLifecycle()
    val accessGranted by model.accessGranted.collectAsStateWithLifecycle()
    val timeout by container.settings.lockTimeout.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }

    val reason = (session as? SessionState.Locked)?.reason
    val unlocking = session is SessionState.Unlocking

    // A wrong password shakes the field.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(reason) {
        if ((reason as? LockReason.UnlockFailed)?.failure == OpenFailure.WrongPassword) {
            for (target in listOf(-18f, 14f, -8f, 4f, 0f)) shake.animateTo(target, spring(stiffness = 4_000f))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(104.dp).rotate(-8f).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Key,
                contentDescription = null,
                Modifier.size(52.dp).rotate(8f),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("KeePassBar", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        DatabaseLine(database, fileInfo, onChooseDatabase)
        reason?.notice(timeout, database.file)?.let { notice ->
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
        Spacer(Modifier.weight(1f))
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
                PasswordRow(
                    model = model,
                    enabled = accessGranted,
                    modifier = Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
                )
            }
        }
    }
}

@Composable
private fun DatabaseLine(database: DatabaseSession, fileInfo: FileInfo?, onChooseDatabase: () -> Unit) {
    Surface(onClick = onChooseDatabase, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(database.file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val folder = database.file.parentFile?.name.orEmpty()
                val detail = when {
                    fileInfo == null -> folder
                    !fileInfo.exists -> "$folder · not there"
                    else -> "$folder · changed " + changedAgo(fileInfo.modified!!.toEpochMilli())
                }
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun PasswordRow(model: LockViewModel, enabled: Boolean, modifier: Modifier = Modifier) {
    var revealed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(enabled) { if (enabled) focus.requestFocus() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecureTextField(
            state = model.password,
            modifier = Modifier.weight(1f).focusRequester(focus),
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
