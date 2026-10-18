package dev.keepassbar.android

import android.content.Context
import dev.keepassbar.android.storage.FileObserverFolderWatcher
import dev.keepassbar.android.storage.PathDatabaseStore
import dev.keepassbar.android.storageprobe.ProbeSettings
import dev.keepassbar.domain.storage.DatabaseStore
import java.io.File

/** The composition root: the one place that picks the implementations behind the ports. */
internal class AppContainer(context: Context) {
    private val folderWatcher = FileObserverFolderWatcher()

    val databaseStoreAt: (File) -> DatabaseStore = { file -> PathDatabaseStore(file, folderWatcher) }

    val probeSettings = ProbeSettings(context.getSharedPreferences("storage-probe", Context.MODE_PRIVATE))
}
