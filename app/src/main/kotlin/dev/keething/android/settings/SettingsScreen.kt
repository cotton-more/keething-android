package dev.keething.android.settings

import dev.keething.android.sharing.announced
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.keething.android.about.BuildInfo
import dev.keething.android.about.ProblemReport
import android.app.LocaleManager
import android.content.Intent
import android.app.UiModeManager
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
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
import dev.keething.android.R
import dev.keething.android.ui.resolve
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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.settings_back)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        }
        DatabasesSetting(container, onDatabase)
        Group(stringResource(R.string.settings_quick_notes)) {
            QuickNotesSetting(container)
        }
        Group(stringResource(R.string.settings_fingerprint)) {
            FingerprintSetting(container)
        }
        Group(stringResource(R.string.settings_autofill)) {
            AutofillSetting()
        }
        Group(stringResource(R.string.settings_lock_timeout)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppSettings.LOCK_TIMEOUTS.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == timeout,
                        onClick = { container.settings.chooseLockTimeout(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, AppSettings.LOCK_TIMEOUTS.size),
                    ) {
                        FitText(
                            if (option.inWholeMinutes < 60) {
                                pluralStringResource(R.plurals.settings_minutes, option.inWholeMinutes.toInt(), option.inWholeMinutes.toInt())
                            } else {
                                pluralStringResource(R.plurals.settings_hours, option.inWholeHours.toInt(), option.inWholeHours.toInt())
                            },
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.settings_lock_timeout_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Group(stringResource(R.string.settings_theme)) {
            ThemeSetting(container)
        }
        Group(stringResource(R.string.settings_language)) {
            LanguageSetting()
        }
        Group(stringResource(R.string.settings_request_validity)) {
            val validity by container.settings.requestValidity.collectAsStateWithLifecycle()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppSettings.REQUEST_VALIDITIES.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == validity,
                        onClick = { container.settings.chooseRequestValidity(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, AppSettings.REQUEST_VALIDITIES.size),
                    ) {
                        FitText(pluralStringResource(R.plurals.settings_days, option.inWholeDays.toInt(), option.inWholeDays.toInt()))
                    }
                }
            }
            Text(
                stringResource(R.string.settings_request_validity_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Group(stringResource(R.string.settings_this_phone)) {
            val keyDerivation = (session as? SessionState.Unlocked)?.keyDerivation
            Text(
                keyDerivation?.let { stringResource(R.string.settings_key_derived_in, it.seconds().resolve()) }
                    ?: stringResource(R.string.settings_key_reused),
                style = MaterialTheme.typography.bodyMedium,
            )
            val runtime = Runtime.getRuntime()
            Text(
                stringResource(R.string.settings_memory, usedMemory.longValue / MIB, runtime.maxMemory() / MIB),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Only the debug build has this group (and the probe it opens).
            Group("Debug build") { // not user text: the debug build's
                FilledTonalButton(onClick = onStorageProbe) { Text("Storage probe") } // not user text: the debug build's
            }
        }
        Group(stringResource(R.string.settings_about)) {
            val info = remember { BuildInfo.read(context.assets) }
            Text(stringResource(R.string.settings_about_version, info.versionText.resolve()), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.settings_about_typeface), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { container.about.shown = true }) { Text(stringResource(R.string.settings_about_keething)) }
                FilledTonalButton(onClick = { ProblemReport.open(context) }) { Text(stringResource(R.string.settings_report_problem)) }
            }
        }
    }
}

private const val MIB = 1024 * 1024

/** "Theme" (keething-spec app.theme-choice): System, Light, Dark; a change rebuilds the screen in the new theme. */
@Composable
private fun ThemeSetting(container: AppContainer) {
    val context = LocalContext.current
    val chosen by container.settings.theme.collectAsStateWithLifecycle()
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ThemeChoice.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == chosen,
                onClick = {
                    container.settings.chooseTheme(option)
                    context.getSystemService(UiModeManager::class.java).setApplicationNightMode(option.nightMode)
                },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeChoice.entries.size),
            ) {
                FitText(option.label.resolve())
            }
        }
    }
}

/** "Language" (keething-spec app.language-setting): the app's language, and the system's page that changes it. */
@Composable
private fun LanguageSetting() {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(LocaleManager::class.java) }
    var chosen by remember { mutableStateOf(manager?.applicationLocales?.get(0)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { chosen = manager?.applicationLocales?.get(0) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(
            onClickLabel = stringResource(R.string.settings_language_change),
        ) {
            val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS).setData(Uri.fromParts("package", context.packageName, null))
            runCatching { context.startActivity(intent) }
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(AppLanguage.label(chosen).resolve(), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_language_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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
                Text(stringResource(R.string.settings_quick_notes_go_to), style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chosen?.colorIndex?.let { DatabaseDot(it) }
                    Text(destination.summary.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(choice.title.resolve())
                            choice.detail?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    trailingIcon = if (choice.id == destination.selected) ({ Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.settings_chosen)) }) else null,
                    onClick = {
                        container.settings.chooseQuickNoteDatabase(choice.id)
                        open = false
                    },
                )
            }
        }
    }
    destination.note?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    Text(
        stringResource(R.string.settings_quick_notes_detail),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Android asks its own question; what it does not show (the tile there already, no question asked) is a toast.
    val context = LocalContext.current
    FilledTonalButton(onClick = { QuickNoteTile.requestAdd(context) { Toast.makeText(context.applicationContext, it, Toast.LENGTH_LONG).show() } }) {
        Icon(Icons.Rounded.DashboardCustomize, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.settings_quick_note_tile_add))
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
        stringResource(if (enabled) R.string.settings_autofill_on else R.string.settings_autofill_off),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!enabled) {
        FilledTonalButton(onClick = {
            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }
        }) { Text(stringResource(R.string.settings_autofill_use)) }
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
        Text(stringResource(R.string.settings_fingerprint_unlock), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = state.enabled, onCheckedChange = null, enabled = state.enabled || unavailable == null)
    }
    val problem = deleteFailure?.let { stringResource(R.string.settings_fingerprint_not_deleted, it) }
    Text(
        problem ?: unavailable?.let { stringResource(R.string.settings_fingerprint_unavailable, it.reason) }
            ?: stringResource(R.string.settings_fingerprint_detail),
        // A deletion that failed is said as it appears (keething-spec app.accessibility AC6).
        Modifier.announced(),
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
    Text(stringResource(R.string.settings_reminder), style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        BiometricState.REMINDER_CHOICES.forEachIndexed { index, days ->
            SegmentedButton(
                selected = days == reminderDays,
                onClick = { setup.setReminderDays(days) },
                shape = SegmentedButtonDefaults.itemShape(index, BiometricState.REMINDER_CHOICES.size),
            ) {
                FitText(if (days == null) stringResource(R.string.settings_reminder_never) else pluralStringResource(R.plurals.settings_days, days, days))
            }
        }
    }
    if (reminderDays != null) {
        Text(
            stringResource(R.string.settings_reminder_detail),
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
            stringResource(R.string.settings_reminder_never_detail),
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
