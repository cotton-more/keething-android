package dev.keepassbar.android

import android.content.Context
import dev.keepassbar.android.attachment.AndroidAttachments
import dev.keepassbar.android.attachment.AttachmentFiles
import dev.keepassbar.android.autofill.AutofillEntries
import dev.keepassbar.android.autofill.FillResponses
import dev.keepassbar.android.autofill.PendingSaves
import dev.keepassbar.android.biometric.FingerprintPrompt
import dev.keepassbar.android.biometric.KeystorePasswordStore
import dev.keepassbar.android.clipboard.AndroidSystemClipboard
import dev.keepassbar.android.clipboard.ClipboardClearAlarm
import dev.keepassbar.android.clipboard.SensitiveClipboard
import dev.keepassbar.android.generator.PasswordGeneratorState
import dev.keepassbar.android.list.RecentEntries
import dev.keepassbar.android.recyclebin.RecycleBinMoves
import dev.keepassbar.android.session.AndroidClock
import dev.keepassbar.android.session.LockAlarm
import dev.keepassbar.android.session.Sessions
import dev.keepassbar.android.settings.AppSettings
import dev.keepassbar.android.settings.SharedPreferencesSettingsStore
import dev.keepassbar.android.storage.FileDatabaseBackups
import dev.keepassbar.android.storage.FileObserverFolderWatcher
import dev.keepassbar.android.storage.PathDatabaseStore
import dev.keepassbar.android.storageprobe.ProbeSettings
import dev.keepassbar.domain.storage.DatabaseStore
import dev.keepassbar.vault.session.SessionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The composition root: the one place that picks the implementations behind the ports. */
internal class AppContainer(context: Context) {
    /** Lives as long as the process; work that must outlast a screen runs here, on the main thread. */
    private val appScope: CoroutineScope = MainScope()

    private val folderWatcher = FileObserverFolderWatcher()

    val databaseStoreAt: (File) -> DatabaseStore = { file -> PathDatabaseStore(file, folderWatcher, AndroidClock) }

    private val settingsStore = SharedPreferencesSettingsStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    val settings = AppSettings(settingsStore)

    val recents = RecentEntries(settingsStore)

    /** The fingerprint unlock's file and keystore work, one task at a time. */
    private val biometricIo = Dispatchers.IO.limitedParallelism(1)

    val biometricStore = KeystorePasswordStore(context, FingerprintPrompt(context), biometricIo)

    val sessions = Sessions(
        settings, settingsStore, databaseStoreAt, FileDatabaseBackups(File(context.filesDir, "backups")),
        biometricStore, appScope, biometricIo, LockAlarm(context),
    )

    /** Delete and Restore: saves whose outcome the list shows after the card closed (SPEC §6.2). */
    val recycleBin = RecycleBinMoves(appScope)

    /** A new generator: its options are remembered, its passwords are not. */
    fun passwordGenerator(): PasswordGeneratorState = PasswordGeneratorState(settingsStore)

    val clipboard = SensitiveClipboard(AndroidSystemClipboard(context), ClipboardClearAlarm(context), appScope)

    val probeSettings = ProbeSettings(context.getSharedPreferences("storage-probe", Context.MODE_PRIVATE))

    /** Attachments opened in another app; what a process that died left behind goes at once (SPEC §4). */
    val attachmentFiles = AttachmentFiles(File(context.cacheDir, "attachments")).also(AttachmentFiles::clear)

    val androidAttachments = AndroidAttachments(context)

    /** Autofill's matching, built once per vault read (SPEC §7). */
    val autofillEntries = AutofillEntries(context)

    val fillResponses = FillResponses(context, sessions, autofillEntries)

    /** Logins typed elsewhere, on their way to the save screen (SPEC §7). */
    val pendingSaves = PendingSaves()

    init {
        clearOnLock()
    }

    /** What outlives a screen and holds the vault's data goes with the lock. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun clearOnLock() {
        appScope.launch {
            sessions.current
                .flatMapLatest { it?.session?.state ?: flowOf(null) }
                .collect {
                    if (it == null || it is SessionState.Locked) {
                        attachmentFiles.clear()
                        autofillEntries.clear()
                        recycleBin.forget()
                    }
                }
        }
    }
}
