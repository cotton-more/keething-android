package dev.keething.android

import dev.keething.android.editor.NewEntryPlaces
import android.content.Context
import android.os.Environment
import android.util.Log
import dev.keething.android.attachment.AndroidAttachments
import dev.keething.android.attachment.AttachmentFiles
import dev.keething.android.autofill.AutofillEntries
import dev.keething.android.autofill.FillResponses
import dev.keething.android.autofill.PendingSaves
import dev.keething.android.biometric.FingerprintPrompt
import dev.keething.android.biometric.KeystorePasswordStores
import dev.keething.android.clipboard.AndroidSystemClipboard
import dev.keething.android.clipboard.ClipboardClearAlarm
import dev.keething.android.clipboard.SensitiveClipboard
import dev.keething.android.databases.CreatingService
import dev.keething.android.databases.DatabaseText
import dev.keething.android.databases.FileDatabaseMaking
import dev.keething.android.databases.NewDatabaseModel
import dev.keething.android.databases.NewDatabases
import dev.keething.android.databases.PhoneNewDatabaseFiles
import dev.keething.android.databases.WindowChooser
import dev.keething.android.generator.PasswordGeneratorState
import dev.keething.android.list.RecentEntries
import dev.keething.android.list.ToldUnlocks
import dev.keething.android.recyclebin.RecycleBinMoves
import dev.keething.android.session.AndroidClock
import dev.keething.android.session.LockAlarm
import dev.keething.android.session.Sessions
import dev.keething.android.settings.AppSettings
import dev.keething.android.settings.SharedPreferencesSettingsStore
import dev.keething.android.sharing.CodePictures
import dev.keething.android.sharing.FileSharingStore
import dev.keething.android.sharing.SharingKey
import dev.keething.android.storage.AllFilesAccess
import dev.keething.android.storage.CreationNote
import dev.keething.android.storage.FileObserverFolderWatcher
import dev.keething.android.storage.PathDatabaseStore
import dev.keething.android.storage.PathKeyFileSource
import dev.keething.android.storage.PathNewFile
import dev.keething.android.storage.SharedStorage
import dev.keething.android.storageprobe.FingerprintProbe
import dev.keething.android.storageprobe.ProbeSettings
import dev.keething.android.ui.Notice
import dev.keething.domain.storage.DatabaseStore
import dev.keething.sharing.message.PartCollector
import dev.keething.sharing.requests.ShareRequests
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.EntryRef
import dev.keething.vault.databases.SessionsState
import dev.keething.vault.databases.StoredEntryIds
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    val toldUnlocks = ToldUnlocks()

    /** The fingerprint unlock's file and keystore work, one task at a time. */
    private val biometricIo = Dispatchers.IO.limitedParallelism(1)

    val biometricStores = KeystorePasswordStores(context, FingerprintPrompt(context), biometricIo)

    /**
     * The databases this phone knows (SPEC §3.1). Each one's backups in a folder of its own under `backups`; the flat
     * files the one database of before left there stay as they were.
     */
    val sessions = Sessions(
        settings, settingsStore, DatabaseList(settingsStore), databaseStoreAt, File(context.filesDir, "backups"),
        ::PathKeyFileSource, biometricStores, appScope, biometricIo, LockAlarm(context), Environment.getExternalStorageDirectory().path,
    )

    /** What a new database's Create is making (plan AD7): an ended process's leftovers go at the next start. */
    private val creationNote = CreationNote(File(context.filesDir, "creating.note"))

    /** The New database screen (SPEC §6.2), kept by the app: Create goes on when the screen is gone. */
    val newDatabases: NewDatabases = NewDatabases { newDatabase(context) }

    /** A form for New database: Save in starts at the current database's folder, then the list's, then Documents. */
    private fun newDatabase(context: Context): NewDatabaseModel {
        val root = Environment.getExternalStorageDirectory()
        val list = sessions.list
        val folders = (listOfNotNull(list.current) + list.records)
            .mapNotNull { File(it.path).parentFile?.takeIf(File::isDirectory)?.path }
            .distinct()
        val model = NewDatabaseModel(
            making = FileDatabaseMaking(newFileAt, creationNote),
            folders = folders,
            documents = File(root, Environment.DIRECTORY_DOCUMENTS).path,
            root = root.path,
            files = PhoneNewDatabaseFiles(root, AllFilesAccess::isGranted),
            offersFingerprint = sessions.biometric.state.value.enabled,
            scope = appScope,
            io = Dispatchers.IO,
            lockCount = { sessions.lockCount },
        )
        model.onCreating = { creating -> if (creating) CreatingService.start(context) else CreatingService.stop(context) }
        model.onCreated = { made ->
            try {
                sessions.created(made)?.let { Log.w("Creating", "an old record's stored password stayed: $it") }
            } finally {
                newDatabases.created(model)
            }
        }
        return model
    }

    /** Delete and Restore: saves whose outcome the list shows after the card closed (SPEC §6.2). */
    val recycleBin = RecycleBinMoves(appScope)

    private val saves = Channel<Notice>(Channel.CONFLATED)

    /** "Saved and checked" from the editor, said by the card it goes back to (SPEC §6.2). */
    val savedNotices: Flow<Notice> = saves.receiveAsFlow()

    /** What a new entry's editor needs of the open databases: Save into, and where the last new entry went. */
    fun newEntryPlaces(): NewEntryPlaces =
        NewEntryPlaces(
            open = sessions.open.map(sessions::newEntryDatabases),
            session = sessions::session,
            window = sessions::windowMarks,
            last = { settings.lastNewEntryDatabase },
            created = { settings.lastNewEntryDatabase = it },
        )

    fun entrySaved() {
        saves.trySend(Notice("Saved and checked"))
    }

    /** A new generator: its options are remembered, its passwords are not. */
    fun passwordGenerator(): PasswordGeneratorState = PasswordGeneratorState(settingsStore)

    val clipboard = SensitiveClipboard(AndroidSystemClipboard(context), ClipboardClearAlarm(context), appScope)

    val probeSettings = ProbeSettings(context.getSharedPreferences("storage-probe", Context.MODE_PRIVATE))

    /** The fingerprint spike of the debug probe (plans/android-databases.md AD5); for the whole process, as unlocks are. */
    val fingerprintProbe by lazy { FingerprintProbe(context, FingerprintPrompt(context)) }

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
     * What a database's Close says, with its Undo, what Locate… refused and what the chooser's run did: on the screen
     * showing (the list, Databases).
     */
    val windowNotices = Channel<Notice>(Channel.BUFFERED)

    /** The chooser, its run and the fingerprint for every database that can (plan AD13): kept across screens. */
    val chooser = WindowChooser(
        list = sessions.list,
        startRun = sessions::run,
        fingerprint = sessions.windowFingerprint::unlock,
        canUnlockAny = sessions.windowFingerprint::canUnlockAny,
        offers = sessions.fingerprintOffers::offer,
        label = { id ->
            val records = sessions.list.records
            DatabaseText.labels(records, Environment.getExternalStorageDirectory().path)[id]?.full ?: records.firstOrNull { it.id == id }?.displayName.orEmpty()
        },
        showOpened = sessions::showOpened,
        tell = { windowNotices.trySend(Notice(it)) },
        sessionState = { sessions.session(it)?.session?.state?.value },
        scope = appScope,
        window = sessions.window,
        lockCount = sessions.locks.counted,
    )

    /**
     * The parts of a large answer read so far (SPEC §10), for the whole app: a messenger hands its pictures over one at a
     * time. Pieces of a sealed answer, nothing secret.
     */
    val answerParts = PartCollector()

    init {
        // A small file, almost never there: read at once, before any Create can note anything.
        val listed = sessions.list.records.flatMap { listOfNotNull(it.path, it.keyFilePath) }.map(DatabaseList::standardized).toSet()
        runCatching { creationNote.leftovers { DatabaseList.standardized(it) in listed } }.getOrNull()?.let { newDatabases.notCreated = it }
        // A lock takes New database's form, with what was typed, unless it is being created.
        sessions.locks.onLock = newDatabases::locked
        clearOnLock()
        followTheLockWithTheRequests()
        keepStoredEntries()
    }

    /**
     * Recent and the declined type offers follow the open databases (keething-spec search.recent AC4, AC7): an entry
     * gone from its open database is dropped (from Recent also into its bin, as the Mac's; a declined offer stays while
     * the entry is in the bin), a database not open keeps its own unless it left the list, an id stored before several
     * databases becomes the first open one's ref ([StoredEntryIds]). Only while something is open: an old id needs an
     * open database to become a ref.
     */
    private fun keepStoredEntries() {
        appScope.launch {
            combine(sessions.state, sessions.list.state, ::Pair).collect { (state, databases) ->
                val open = (state as? SessionsState.Open)?.vaults ?: return@collect
                val ids = open.map { it.id }
                val listed = databases.records.mapTo(HashSet()) { it.id }
                recents.keep(StoredEntryIds(ids, open.flatMap { opened -> opened.vault.entries.map { EntryRef(opened.id, it.id) } }, listed))
                val withBins = open.flatMap { opened -> (opened.vault.entries + opened.vault.recycleBin).map { EntryRef(opened.id, it.id) } }
                settings.keepDeclined(StoredEntryIds(ids, withBins, listed))
            }
        }
    }

    /** The requests open with their key when a database opens, and are wiped when none is open. */
    private fun followTheLockWithTheRequests() {
        appScope.launch {
            sessions.state
                .map { it is SessionsState.Open }
                .distinctUntilChanged()
                .collect { open ->
                    if (!open) {
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

    /**
     * What outlives a screen and holds a vault's data goes when no database is open any more, also while another is
     * still opening (keething-spec databases.several-open AC10).
     */
    private fun clearOnLock() {
        appScope.launch {
            // A database that locks alone while others stay open: its attachments opened elsewhere go, and autofill's
            // matcher, which holds its entries' copies.
            var open = emptySet<DatabaseId>()
            sessions.state
                .collect {
                    val now = (it as? SessionsState.Open)?.vaults?.mapTo(HashSet()) { vault -> vault.id }.orEmpty()
                    val locked = open - now
                    locked.forEach(attachmentFiles::clear)
                    if (locked.isNotEmpty()) autofillEntries.clear()
                    open = now
                    if (it !is SessionsState.Open) {
                        attachmentFiles.clear()
                        autofillEntries.clear()
                        // A Close's Undo said to a vault that gave way to a lock screen: not said later, stale.
                        while (windowNotices.tryReceive().isSuccess) Unit
                        recycleBin.forget()
                        answerParts.clear()
                    }
                }
        }
    }
}
