package dev.keething.android

import android.content.Context
import android.util.Log
import dev.keething.android.attachment.AndroidAttachments
import dev.keething.android.attachment.AttachmentFiles
import dev.keething.android.autofill.AutofillEntries
import dev.keething.android.autofill.FillResponses
import dev.keething.android.autofill.PendingSaves
import dev.keething.android.biometric.FingerprintPrompt
import dev.keething.android.biometric.KeystorePasswordStore
import dev.keething.android.clipboard.AndroidSystemClipboard
import dev.keething.android.clipboard.ClipboardClearAlarm
import dev.keething.android.clipboard.SensitiveClipboard
import dev.keething.android.generator.PasswordGeneratorState
import dev.keething.android.list.RecentEntries
import dev.keething.android.recyclebin.RecycleBinMoves
import dev.keething.android.session.AndroidClock
import dev.keething.android.session.LockAlarm
import dev.keething.android.session.Sessions
import dev.keething.android.settings.AppSettings
import dev.keething.android.settings.SharedPreferencesSettingsStore
import dev.keething.android.sharing.CodePictures
import dev.keething.android.sharing.FileSharingStore
import dev.keething.android.sharing.SharingKey
import dev.keething.android.storage.FileObserverFolderWatcher
import dev.keething.android.storage.PathDatabaseStore
import dev.keething.android.storage.PathKeyFileSource
import dev.keething.android.storage.PathNewFile
import dev.keething.android.storage.SharedStorage
import dev.keething.android.storageprobe.ProbeSettings
import dev.keething.android.ui.Notice
import dev.keething.domain.storage.DatabaseStore
import dev.keething.sharing.message.PartCollector
import dev.keething.sharing.requests.ShareRequests
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.session.SessionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The composition root: the one place that picks the implementations behind the ports. */
internal class AppContainer(context: Context) {
    /** Lives as long as the process; work that must outlast a screen runs here, on the main thread. */
    private val appScope: CoroutineScope = MainScope()

    private val folderWatcher = FileObserverFolderWatcher()

    val databaseStoreAt: (File) -> DatabaseStore = { file -> PathDatabaseStore(file, folderWatcher, AndroidClock) }

    /** A new database or key file at a path; owner-only in the app's own storage, shared storage has no permissions. */
    val newFileAt: (File) -> PathNewFile = { file -> PathNewFile(file, ownerOnly = !SharedStorage.contains(file)) }

    private val settingsStore = SharedPreferencesSettingsStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    val settings = AppSettings(settingsStore)

    val recents = RecentEntries(settingsStore)

    /** The fingerprint unlock's file and keystore work, one task at a time. */
    private val biometricIo = Dispatchers.IO.limitedParallelism(1)

    val biometricStore = KeystorePasswordStore(context, FingerprintPrompt(context), biometricIo)

    /**
     * The databases this phone knows (SPEC §3.1). Each one's backups in a folder of its own under `backups`; the flat
     * files the one database of before left there stay as they were.
     */
    val sessions = Sessions(
        settings, settingsStore, DatabaseList(settingsStore), databaseStoreAt, File(context.filesDir, "backups"),
        ::PathKeyFileSource, biometricStore, appScope, biometricIo, LockAlarm(context),
    )

    /** Delete and Restore: saves whose outcome the list shows after the card closed (SPEC §6.2). */
    val recycleBin = RecycleBinMoves(appScope)

    private val saves = Channel<Notice>(Channel.CONFLATED)

    /** "Saved and checked" from the editor, said by the card it goes back to (SPEC §6.2). */
    val savedNotices: Flow<Notice> = saves.receiveAsFlow()

    fun entrySaved() {
        saves.trySend(Notice("Saved and checked"))
    }

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

    /** The requests for entries, both ways (SPEC §10): read when the vault unlocks, wiped when it locks. */
    val shareRequests = ShareRequests(FileSharingStore(File(context.noBackupFilesDir, "sharing/requests")), AndroidClock)

    private val sharingKey = SharingKey(File(context.noBackupFilesDir, "sharing/key"))

    /** The pictures of requests and answers, made to be sent; what a process that died left goes at once. */
    val codePictures = CodePictures(context).also(CodePictures::clear)

    /** Codes another app handed over (a picture or a text shared to Keething), until the unlocked vault reads them. */
    val incomingCodes = MutableStateFlow<List<String>?>(null)

    /**
     * The parts of a large answer read so far (SPEC §10), for the whole app: a messenger hands its pictures over one at a
     * time. Pieces of a sealed answer, nothing secret.
     */
    val answerParts = PartCollector()

    init {
        clearOnLock()
        followTheLockWithTheRequests()
    }

    /** The requests open with their key when the vault unlocks, and are wiped when it locks. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun followTheLockWithTheRequests() {
        appScope.launch {
            sessions.current
                .flatMapLatest { database -> database?.session?.state?.map { database.takeIf { _ -> it is SessionState.Unlocked } } ?: flowOf(null) }
                .distinctUntilChanged()
                .collect { unlocked ->
                    if (unlocked == null) {
                        shareRequests.close()
                    } else {
                        withContext(Dispatchers.IO) {
                            runCatching { sharingKey.load() }
                                .onSuccess(shareRequests::open)
                                .onFailure { Log.w("Sharing", "the requests' key: ${it.javaClass.simpleName}") }
                        }
                    }
                }
        }
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
                        answerParts.clear()
                    }
                }
        }
    }
}
