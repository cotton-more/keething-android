package dev.keepassbar.android.settings

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.list.seconds
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.vault.session.SessionState

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
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
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
        Group("Lock after this long in the background") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppSettings.LOCK_TIMEOUTS.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == timeout,
                        onClick = { container.settings.chooseLockTimeout(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, AppSettings.LOCK_TIMEOUTS.size),
                    ) {
                        Text(if (option.inWholeMinutes < 60) "${option.inWholeMinutes} min" else "1 h")
                    }
                }
            }
            Text(
                "Turning the screen off counts as the background. The clipboard is cleared 30 s after a copy.",
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
                "Memory: ${usedMemory.longValue / MIB} MiB used of ${runtime.maxMemory() / MIB} MiB.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Group("Debug build") {
                FilledTonalButton(onClick = onStorageProbe) { Text("Storage probe") }
            }
        }
        Group("About") {
            Text("KeePassBar for Android. Passwords and codes are set in Google Sans Code (SIL Open Font License 1.1).", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val MIB = 1024 * 1024

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
