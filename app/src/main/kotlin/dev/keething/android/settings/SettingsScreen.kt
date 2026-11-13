package dev.keething.android.settings

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.keething.android.about.BuildInfo
import dev.keething.android.about.ProblemReport
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.databases.DatabaseDot
import dev.keething.android.databases.DatabaseLinesModel
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keething.android.AppContainer
import dev.keething.android.databases.DatabasesSetting
import dev.keething.android.list.seconds
import dev.keething.vault.databases.DatabaseSession
import dev.keething.android.quicknote.QuickNoteTile
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.FitText
import dev.keething.android.ui.readableInset
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.session.SessionState
import kotlinx.coroutines.launch

/** Few settings, fixed sensible defaults otherwise (SPEC §6.1, §6.2). */
@Composable
internal fun SettingsScreen(
    container: AppContainer,
    database: DatabaseSession,
    onBack: () -> Unit,
    onDatabase: (DatabaseId) -> Unit,
    onStorageProbe: () -> Unit,
) {
    val timeout by container.settings.lockTimeout.collectAsStateWithLifecycle()
    val session by database.session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val usedMemory = remember { mutableLongStateOf(0L) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val runtime = Runtime.getRuntime()
        usedMemory.longValue = runtime.totalMemory() - runtime.freeMemory()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = readableInset()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        DatabasesSetting(container, onDatabase)
        Group("Quick notes") {
            QuickNotesSetting(container)
        }
        Group("Fingerprint") {
            FingerprintSetting(container)
        }
        Group("Autofill") {
            AutofillSetting()
        }
        Group("Lock after this long in the background") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppSettings.LOCK_TIMEOUTS.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == timeout,
                        onClick = { container.settings.chooseLockTimeout(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, AppSettings.LOCK_TIMEOUTS.size),
                    ) {
                        FitText(if (option.inWholeMinutes < 60) "${option.inWholeMinutes} min" else "1 h")
                    }
                }
            }
            Text(
                "Turning the screen off counts as the background. The clipboard is cleared 30 s after a copy.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Group("Sharing: requests and pictures last") {
            val validity by container.settings.requestValidity.collectAsStateWithLifecycle()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppSettings.REQUEST_VALIDITIES.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == validity,
                        onClick = { container.settings.chooseRequestValidity(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, AppSettings.REQUEST_VALIDITIES.size),
                    ) {
                        FitText(if (option.inWholeDays == 1L) "1 day" else "${option.inWholeDays} days")
                    }
                }
            }
            Text(
                "Then the phone that asked forgets its key, and an answer can no longer be opened; the phone that sent " +
                    "entries without a request forgets their pictures. New ones only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Group("This phone") {
            val keyDerivation = (session as? SessionState.Unlocked)?.keyDerivation
            Text(
                keyDerivation?.let { "The last unlock derived the key in ${it.seconds()}." }
                    ?: "The last read of the file reused the derived key.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val runtime = Runtime.getRuntime()
            Text(
                "Memory: ${usedMemory.longValue / MIB}\u00A0MiB used of ${runtime.maxMemory() / MIB}\u00A0MiB.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Group("Debug build") {
                FilledTonalButton(onClick = onStorageProbe) { Text("Storage probe") }
            }
        }
        Group("About") {
            val info = remember { BuildInfo.read(context.assets) }
            Text("Keething for Android ${info.versionText}.", style = MaterialTheme.typography.bodyMedium)
            Text("Passwords and codes are set in Google Sans Code (SIL Open Font License 1.1).", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { container.about.shown = true }) { Text("About Keething") }
                FilledTonalButton(onClick = { ProblemReport.open(context) }) { Text("Report a problem") }
            }
        }
    }
}

private const val MIB = 1024 * 1024

/** "Quick notes go to" (SPEC §6.4; keething-spec app.quick-note AC1): Current database or one of the list, with its dot. */
@Composable
private fun QuickNotesSetting(container: AppContainer) {
    val linesModel = viewModel(key = "database-lines") { DatabaseLinesModel(container.sessions.list, container.databaseFiles) }
    val screens by linesModel.screens.collectAsStateWithLifecycle()
    val setting by container.settings.quickNoteDatabase.collectAsStateWithLifecycle()
    val destination = QuickNoteDestination(setting, screens?.lines)
    val chosen = destination.choices.firstOrNull { it.id == destination.selected }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !destination.isLoading) { open = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Quick notes go to", style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chosen?.colorIndex?.let { DatabaseDot(it) }
                    Text(destination.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            destination.choices.forEach { choice ->
                DropdownMenuItem(
                    leadingIcon = {
                        choice.colorIndex?.let { DatabaseDot(it) }
                            ?: Box(Modifier.size(12.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    },
                    text = {
                        Column {
                            Text(choice.title)
                            choice.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    trailingIcon = if (choice.id == destination.selected) ({ Icon(Icons.Rounded.Check, contentDescription = "Chosen") }) else null,
                    onClick = {
                        container.settings.chooseQuickNoteDatabase(choice.id)
                        open = false
                    },
                )
            }
        }
    }
    destination.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    Text(
        "Text shared to Keething note or picked from a text's menu, the tile and the launcher's Note from clipboard " +
            "open a new note in that database's Inbox, made with the note when there is none. Nothing is saved until " +
            "you save it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Android asks its own question; what it does not show (the tile there already, no question asked) is a toast.
    val context = LocalContext.current
    FilledTonalButton(onClick = { QuickNoteTile.requestAdd(context) { Toast.makeText(context.applicationContext, it, Toast.LENGTH_LONG).show() } }) {
        Icon(Icons.Rounded.DashboardCustomize, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Add the Quick note tile")
    }
}

/** Whether Keething is the phone's autofill service (SPEC §7), and how to make it so. */
@Composable
private fun AutofillSetting() {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AutofillManager::class.java) }
    var enabled by remember { mutableStateOf(manager?.hasEnabledAutofillServices() == true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = manager?.hasEnabledAutofillServices() == true }
    Text(
        if (enabled) {
            "On: apps and browsers offer the matching entries. In Chrome, also choose Settings › Autofill services › " +
                "Autofill using another service; Firefox needs nothing."
        } else {
            "Off: another service fills, or none."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!enabled) {
        FilledTonalButton(onClick = {
            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }
        }) { Text("Use Keething for autofill") }
    }
}

/** The app's switch of SPEC §5; each database has its own on its page (Settings › Databases). */
@Composable
private fun FingerprintSetting(container: AppContainer) {
    val setup = container.sessions.biometric
    val state by setup.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var deleteFailure by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { setup.refresh() } }
    val unavailable = state.availability as? BiometricAvailability.Unavailable
    val change = { on: Boolean -> scope.launch { deleteFailure = setup.setEnabled(on) } }
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = state.enabled,
            enabled = state.enabled || unavailable == null,
            role = Role.Switch,
            onValueChange = { change(it) },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Unlock with the fingerprint", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = state.enabled, onCheckedChange = null, enabled = state.enabled || unavailable == null)
    }
    val problem = deleteFailure?.let { "The stored passwords could not be deleted: $it." }
    Text(
        problem ?: unavailable?.let { "Not available: ${it.reason}." }
            ?: "Each database whose Use fingerprint is on opens with it: its lock screen offers it once, by the fingerprint " +
                "beside the password, and its page in Databases turns it on or off. Turning this off deletes all their stored passwords.",
        style = MaterialTheme.typography.bodyMedium,
        color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.enabled) PasswordReminderSetting(setup, state.reminderDays)
}

/**
 * How often each database's master key is asked about once open (SPEC §5; keething-spec unlock.password-reminder,
 * unlock.reminder-not-a-lock): the app's choice, each database's days.
 */
@Composable
private fun PasswordReminderSetting(setup: BiometricSetup, reminderDays: Int?) {
    Spacer(Modifier.height(4.dp))
    Text("Ask about each master key every", style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        BiometricState.REMINDER_CHOICES.forEachIndexed { index, days ->
            SegmentedButton(
                selected = days == reminderDays,
                onClick = { setup.setReminderDays(days) },
                shape = SegmentedButtonDefaults.itemShape(index, BiometricState.REMINDER_CHOICES.size),
            ) {
                FitText(if (days == null) "Never" else "$days days")
            }
        }
    }
    if (reminderDays != null) {
        Text(
            "After this long without its master password typed, Keething asks once, after an unlock, whether you still " +
                "remember it (or keep its key file safe). The fingerprint opens it all the same.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // In the ordinary colour, marked so it is not passed over (keething-spec unlock.reminder-not-a-lock AC6).
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            "Keething never asks. A forgotten master password, or a lost key file, opens its database for nobody, not " +
                "even with Keething.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row).heightIn(min = 56.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
