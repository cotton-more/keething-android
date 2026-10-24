package dev.keething.android.settings

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
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
import androidx.compose.runtime.LaunchedEffect
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
import dev.keething.android.biometric.KeystorePasswordStore.KeyLocation
import dev.keething.android.list.seconds
import dev.keething.android.session.DatabaseSession
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.FitText
import dev.keething.android.ui.readableInset
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Few settings, fixed sensible defaults otherwise (SPEC §6.1, §6.2). */
@Composable
internal fun SettingsScreen(
    container: AppContainer,
    database: DatabaseSession,
    onBack: () -> Unit,
    onChooseDatabase: () -> Unit,
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
        Group("Database") {
            Text(database.file.name, style = MaterialTheme.typography.bodyLarge)
            Text(database.file.parent.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onChooseDatabase) { Text("Choose another database") }
        }
        Group("Fingerprint") {
            FingerprintSetting(container, database)
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
            Text("Keething for Android. Passwords and codes are set in Google Sans Code (SIL Open Font License 1.1).", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val MIB = 1024 * 1024

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

/** The switch of SPEC §5 and what it does now. */
@Composable
private fun FingerprintSetting(container: AppContainer, database: DatabaseSession) {
    val state by database.biometric.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var deleteFailure by remember { mutableStateOf<String?>(null) }
    var location by remember { mutableStateOf<KeyLocation?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { database.biometric.refresh() } }
    LaunchedEffect(state.stored) {
        location = if (state.stored) withContext(Dispatchers.IO) { runCatching { container.biometricStore.keyLocation() }.getOrNull() } else null
    }
    val unavailable = state.availability as? BiometricAvailability.Unavailable
    val change = { on: Boolean -> scope.launch { deleteFailure = database.biometric.setEnabled(on) } }
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
    val problem = deleteFailure?.let { "The stored password could not be deleted: $it." }
        ?: state.setupFailure?.let { "It could not be set up: $it. It tries again at the next unlock with the password." }
    Text(
        problem ?: state.describe(unavailable, location),
        style = MaterialTheme.typography.bodyMedium,
        color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.enabled) PasswordReminderSetting(database, state)
}

/** How often the fingerprint steps aside for the typed password (SPEC §5). */
@Composable
private fun PasswordReminderSetting(database: DatabaseSession, state: BiometricState) {
    Spacer(Modifier.height(4.dp))
    Text("Ask for the master password every", style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        BiometricState.REMINDER_CHOICES.forEachIndexed { index, days ->
            SegmentedButton(
                selected = days == state.reminderDays,
                onClick = { database.biometric.setReminderDays(days) },
                shape = SegmentedButtonDefaults.itemShape(index, BiometricState.REMINDER_CHOICES.size),
            ) {
                FitText(if (days == null) "Never" else "$days days")
            }
        }
    }
    Text(
        if (state.reminderDays == null) {
            "The fingerprint always opens it. If you forget the master password, the database cannot be opened " +
                "by anyone, not even with Keething."
        } else {
            "After this long without typing it, the next unlock asks for the password, so it is not forgotten. " +
                "Then the fingerprint again."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.reminderDays == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun BiometricState.describe(unavailable: BiometricAvailability.Unavailable?, location: KeyLocation?): String = when {
    unavailable != null -> "Not available: ${unavailable.reason}."
    !enabled -> "Off: the password is typed every time."
    !stored -> "It is set up the next time you unlock with the password."
    else -> "The password is sealed by a key in ${location.describe()} that opens it only after your fingerprint. " +
        "A fingerprint added on this phone undoes it."
}

private fun KeyLocation?.describe(): String = when (this) {
    KeyLocation.StrongBox -> "the phone's security chip (StrongBox)"
    KeyLocation.TrustedEnvironment -> "the processor's secure area (TEE)"
    KeyLocation.SecureHardware -> "the phone's secure hardware"
    KeyLocation.Software -> "software (no secure hardware here)"
    null -> "the Android Keystore"
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row).heightIn(min = 56.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
