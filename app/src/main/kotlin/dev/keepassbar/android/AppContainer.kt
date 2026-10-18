package dev.keepassbar.android

import android.content.Context
import dev.keepassbar.android.clipboard.AndroidSystemClipboard
import dev.keepassbar.android.clipboard.ClipboardClearAlarm
import dev.keepassbar.android.clipboard.SensitiveClipboard
import dev.keepassbar.android.list.RecentEntries
import dev.keepassbar.android.session.LockAlarm
import dev.keepassbar.android.session.Sessions
import dev.keepassbar.android.settings.AppSettings
import dev.keepassbar.android.settings.SharedPreferencesSettingsStore
import dev.keepassbar.android.storage.FileObserverFolderWatcher
import dev.keepassbar.android.storage.PathDatabaseStore
import dev.keepassbar.android.storageprobe.ProbeSettings
import dev.keepassbar.domain.storage.DatabaseStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/** The composition root: the one place that picks the implementations behind the ports. */
internal class AppContainer(context: Context) {
    /** Lives as long as the process; work that must outlast a screen runs here, on the main thread. */
    private val appScope: CoroutineScope = MainScope()

    private val folderWatcher = FileObserverFolderWatcher()

    val databaseStoreAt: (File) -> DatabaseStore = { file -> PathDatabaseStore(file, folderWatcher) }

    private val settingsStore = SharedPreferencesSettingsStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    val settings = AppSettings(settingsStore)

    val recents = RecentEntries(settingsStore)

    val sessions = Sessions(settings, databaseStoreAt, appScope, LockAlarm(context))

    val clipboard = SensitiveClipboard(AndroidSystemClipboard(context), ClipboardClearAlarm(context), appScope)

    val probeSettings = ProbeSettings(context.getSharedPreferences("storage-probe", Context.MODE_PRIVATE))
}
