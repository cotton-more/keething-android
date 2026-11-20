package dev.keething.android

import dev.keething.android.about.AboutPage
import dev.keething.android.editor.NewEntryPlaces
import dev.keething.android.editor.savedNotice
import dev.keething.domain.vault.VaultGroup
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.keething.android.attachment.AndroidAttachments
import dev.keething.android.attachment.AttachmentFiles
import dev.keething.android.autofill.AutofillEntries
import dev.keething.android.autofill.FillIntents
import dev.keething.android.autofill.FillResponses
import dev.keething.android.autofill.PendingSaves
import dev.keething.android.biometric.FingerprintPrompt
import dev.keething.android.biometric.KeystorePasswordStores
import dev.keething.android.clipboard.AndroidSystemClipboard
import dev.keething.android.clipboard.ClipboardClearAlarm
import dev.keething.android.clipboard.SensitiveClipboard
import dev.keething.android.databases.ChangeMasterKeyModel
import dev.keething.android.databases.CreatingService
import dev.keething.android.databases.DatabaseText
import dev.keething.android.databases.FileDatabaseMaking
import dev.keething.android.databases.GrantedKeyChangeFiles
import dev.keething.android.databases.GrantedNewDatabaseFiles
import dev.keething.android.databases.NewDatabasePlaces
import dev.keething.android.databases.grantedFolders
import dev.keething.android.databases.NewDatabaseModel
import dev.keething.android.databases.MasterKeyChanges
import dev.keething.android.databases.NewDatabases
import dev.keething.android.databases.marks
import dev.keething.vault.session.SessionState
import dev.keething.android.databases.WindowChooser
import dev.keething.android.generator.PasswordGeneratorState
import dev.keething.android.list.RecentEntries
import dev.keething.android.list.ToldUnlocks
import dev.keething.android.recyclebin.RecycleBinMoves
import dev.keething.android.session.AndroidClock
import dev.keething.android.session.LockAlarm
import dev.keething.android.session.Sessions
import dev.keething.android.settings.AppSettings
import dev.keething.android.settings.SettingsVersion
import dev.keething.android.settings.SharedPreferencesSettingsStore
import dev.keething.android.sharing.CodePictures
import dev.keething.android.sharing.FileSharingStore
import dev.keething.android.sharing.SharingKey
import dev.keething.android.storage.PersistedGrants
import dev.keething.android.storage.ProviderFile
import dev.keething.android.storage.ProviderFolder
import dev.keething.android.storage.UnreachableFolder
import dev.keething.android.storage.GrantedFiles
import dev.keething.android.storage.databaseStoreOf
import dev.keething.android.storage.PausingFolder
import dev.keething.android.databases.DatabaseFiles
import dev.keething.android.storageprobe.GrantProbe
import dev.keething.domain.storage.GrantedFolder
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.databases.FilesThere
import dev.keething.vault.databases.GrantKeeper
import dev.keething.vault.storage.FolderDatabaseStore
import dev.keething.vault.storage.SaveNotes
import dev.keething.android.storageprobe.FingerprintProbe
import dev.keething.android.ui.Notice
import dev.keething.android.ui.UiText
import dev.keething.domain.storage.DatabaseStore
import dev.keething.sharing.message.PartCollector
import dev.keething.sharing.requests.ShareRequests
import dev.keething.vault.creating.CreationNote
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.EntryRef
import dev.keething.vault.databases.SessionsState
import dev.keething.vault.databases.StoredEntryIds
import dev.keething.vault.storage.KeyFiles
import java.io.File
import java.util.concurrent.CountDownLatch
import dev.keething.android.databases.GrantedPlace
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

    private val resolver = context.contentResolver

    /** The grants the app holds, kept by the system (keething-spec databases.saf AC14). */
    private val grants = PersistedGrants(resolver)

    /** Debug builds only: the marker that pauses a folder's change on the phone (PausingFolder). */
    private val pauseMarker: File? = File(context.filesDir, "debug-pause")
        .takeIf { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    /** A granted folder by its grant; one not of the phone's storage cannot be reached (AC12). */
    private val folderOf: (grant: String) -> GrantedFolder? = { grant ->
        ProviderFolder.of(resolver, grant)?.let { if (pauseMarker == null) it else PausingFolder(it, pauseMarker) }
    }

    /** The saves through granted folders, noted before they touch the folder (AC5): one for the whole app. */
    private val saveNotes = SaveNotes(File(context.filesDir, "saves"))

    /** A database of the list through its granted folder ([databaseStoreOf]). */
    private fun databaseStoreOf(file: FileLocation): DatabaseStore = databaseStoreOf(file, folderOf, saveNotes, AndroidClock)

    /** A key file granted alone, by its grant; one not of the phone's storage cannot be reached (AC12). */
    private val fileOf: (grant: String) -> ProviderFile? = { ProviderFile.of(resolver, it) }

    /** The list's files looked at through their grants, never opened (SPEC §6.2). */
    val databaseFiles: DatabaseFiles = GrantedFiles(folderOf, fileOf) { id -> sessions.backups(id).newest() }

    /** How a key file is reached wherever the list has it (AC9). */
    private val keyFiles = KeyFiles(folder = folderOf, file = fileOf)

    private val settingsStore = SharedPreferencesSettingsStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
        .also(SettingsVersion::migrate)

    private val databaseList = DatabaseList(settingsStore)

    /** Gives back each grant the list no longer needs (AC14); before anything can change the list. */
    private val grantKeeper = GrantKeeper(databaseList, grants, saveNotes, appScope, Dispatchers.IO)

    /**
     * A grant the user gave in a picker for the list (a folder, a key file): given back after the list's next change
     * unless the list is reached through it then (AC14). On the main thread.
     */
    fun offerGrant(grant: String) {
        grantKeeper.offered(grant)
    }

    /** A grant given again in a picker, in use by what asked for it until it offers it again (AC14). On the main thread. */
    fun grantTaken(grant: String) {
        grantKeeper.taken(grant)
    }

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
        settings, settingsStore, databaseList, ::databaseStoreOf, File(context.filesDir, "backups"), keyFiles,
        FilesThere(folderOf, fileOf, Dispatchers.IO), biometricStores, appScope, biometricIo, LockAlarm(context), Environment.getExternalStorageDirectory().path, grantKeeper::offered,
        held = grants::held,
    )

    /** What a new database's Create is making (plan AD7): an ended process's leftovers go at the next start. */
    private val creationNote = CreationNote(File(context.filesDir, "creating.note"), folderOf)

    /**
     * The notes of stopped saves this version could not read, found at the start (keething-spec app.upgrade-safety
     * AC3): said until OK, then set aside.
     */
    var unreadableSaves: Int by mutableIntStateOf(0)
        private set

    fun unreadableSavesSaid() {
        unreadableSaves = 0
        appScope.launch(Dispatchers.IO) { runCatching { saveNotes.setUnreadableAside() } }
    }

    /** Opened once the start has dealt with an ended Create's leftovers: a Create's note waits for it. */
    private val leftoversDealtWith = CountDownLatch(1)

    /** The New database screen (SPEC §6.2), kept by the app: Create goes on when the screen is gone. */
    val newDatabases: NewDatabases = NewDatabases { newDatabase(context) }

    /** About over the app, reached with every database locked too (SPEC §6.2). */
    val about: AboutPage = AboutPage()

    /**
     * A form for New database: Save in starts at the current database's folder, then the list's other granted folders
     * (keething-spec databases.saf AC11); with none, a folder is chosen first. What the form granted and did not use is
     * given back (AC14).
     */
    private fun newDatabase(context: Context): NewDatabaseModel {
        val root = Environment.getExternalStorageDirectory().path
        val list = sessions.list
        val held = grants.held()
        val current = (list.current?.file as? FileLocation.InFolder)?.takeIf { it.grant in held }
        val folders = listOfNotNull(current?.let { GrantedPlace(it.grant, it.folder) }) + list.state.value.grantedFolders(held)
        val places = NewDatabasePlaces(root, folders.distinctBy { it.grant })
        // The current database's own folder first, then each granted folder as granted.
        val starts = listOfNotNull(current?.place?.parent?.let { DatabaseText.pathOf(it, root) }) + places.listedPaths
        val model = NewDatabaseModel(
            making = FileDatabaseMaking(places, folderOf, keyFiles, creationNote, ready = leftoversDealtWith::await),
            folders = starts.distinct(),
            root = root,
            files = GrantedNewDatabaseFiles(places, folderOf, appScope, Dispatchers.IO) { file -> fileOf(file.grant)?.isThere() == true },
            offersFingerprint = sessions.biometric.state.value.enabled,
            scope = appScope,
            io = Dispatchers.IO,
            lockCount = { sessions.lockCount },
        )
        val offerTaken = { places.taken.forEach(grantKeeper::offered) }
        model.folderGranted = { folder -> grantKeeper.taken(folder.grant); places.folderGranted(folder) }
        model.keyFileGranted = { file -> grantKeeper.taken(file.grant); places.keyFileGranted(file) }
        model.onCreating = { creating -> if (creating) CreatingService.start(context) else CreatingService.stop(context) }
        model.onClosed = offerTaken
        model.onCreated = { made ->
            try {
                // Offered first: the list's change below keeps those it is reached through, the others go.
                offerTaken()
                val file = checkNotNull(places.inFolder(made.path)) { "made where no grant reaches" } // not user text
                sessions.created(made, file, made.keyFilePath?.let(places::keyFile))?.let { Log.w("Creating", "an old record's stored password stayed: $it") }
            } finally {
                newDatabases.created(model)
            }
        }
        return model
    }

    /** Change master key… (SPEC §6.2), over whatever was shown, kept by the app: a change outlives the screen. */
    val masterKeys: MasterKeyChanges = MasterKeyChanges(::masterKeyForm)

    /**
     * A form for Change master key… of [id], open: its key file kept, made new or chosen through the folders the list is
     * reached through and those granted in the form (keething-spec databases.saf AC9); what the form granted and the list
     * does not take is given back (AC14). Null when it is not open.
     */
    private fun masterKeyForm(id: DatabaseId): ChangeMasterKeyModel? {
        val database = sessions.session(id)?.takeIf { it.session.state.value is SessionState.Unlocked } ?: return null
        val record = sessions.list.record(id) ?: return null
        val root = Environment.getExternalStorageDirectory().path
        val list = sessions.list
        val places = NewDatabasePlaces(root, list.state.value.grantedFolders(grants.held()).distinctBy { it.grant })
        val filePath = DatabaseText.pathOf(record.file, root)
        val label = list.state.value.marks(root)[id]?.label?.full ?: record.displayName
        val model = ChangeMasterKeyModel(
            id = id,
            label = label,
            fileText = DatabaseText.abbreviated(filePath, root),
            fileName = record.file.fileName,
            databaseFolder = File(filePath).parent,
            currentKeyFile = record.keyFile?.let { keyFile ->
                ChangeMasterKeyModel.CurrentKeyFile(keyFile.fileName, DatabaseText.folder(DatabaseText.pathOf(keyFile, root), root), keyFile)
            },
            confirm = database.session::confirmKey,
            change = { password, keyFile, keyFileKey -> sessions.databases.changeKey(id, password, keyFile, keyFileKey) },
            files = GrantedKeyChangeFiles(places, folderOf, keyFiles),
            look = GrantedNewDatabaseFiles(places, folderOf, appScope, Dispatchers.IO) { file -> fileOf(file.grant)?.isThere() == true },
            root = root,
            scope = appScope,
            io = Dispatchers.IO,
        )
        model.folderGranted = { folder -> grantKeeper.taken(folder.grant); places.folderGranted(folder) }
        model.keyFileGranted = { file -> grantKeeper.taken(file.grant); places.keyFileGranted(file) }
        model.onClosed = { places.taken.forEach(grantKeeper::offered) }
        return model
    }

    /** Delete and Restore: saves whose outcome the list shows after the card closed (SPEC §6.2). */
    val recycleBin = RecycleBinMoves(appScope)

    private val saves = Channel<Notice>(Channel.CONFLATED)

    /**
     * "Saved and checked" from the editor, said by the card it goes back to (SPEC §6.2), with the empty groups a move
     * deleted; a version restored from History said so too (keething-spec edit.history-restore).
     */
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

    fun entrySaved(deletedGroups: List<VaultGroup>, mayNotSync: Boolean) {
        saves.trySend(savedNotice(deletedGroups, mayNotSync))
    }

    /** A version restored from History, said by the card it goes back to. */
    fun versionRestored(notice: Notice) {
        saves.trySend(notice)
    }

    /** A new generator: its options are remembered, its passwords are not. */
    fun passwordGenerator(): PasswordGeneratorState = PasswordGeneratorState(settingsStore)

    /** The system's clipboard read as such: New note from clipboard (SPEC §6.4). */
    val systemClipboard = AndroidSystemClipboard(context)
    val clipboard = SensitiveClipboard(systemClipboard, ClipboardClearAlarm(context), appScope)

    /** The fingerprint spike of the debug probe (plans/android-databases.md AD5); for the whole process, as unlocks are. */
    val fingerprintProbe by lazy { FingerprintProbe(context, FingerprintPrompt(context)) }

    /** The debug probe of what the storage provider does with names in a granted folder (databases.saf stage S3). */
    fun grantProbe(log: (String) -> Unit): GrantProbe = GrantProbe(resolver, grants, log)

    /** Attachments opened in another app; what a process that died left behind goes at once (SPEC §4). */
    val attachmentFiles = AttachmentFiles(File(context.cacheDir, "attachments")).also(AttachmentFiles::clear)

    val androidAttachments = AndroidAttachments(context)

    /** Autofill's matching, built once per vault read (SPEC §7). */
    val autofillEntries = AutofillEntries(context)

    /** The fill screen's intents handed out, each answering without a screen once (SPEC §7, Trust). */
    val fillIntents = FillIntents()

    val fillResponses = FillResponses(context, sessions, autofillEntries, fillIntents)

    /** Logins typed elsewhere, on their way to the save screen (SPEC §7). */
    val pendingSaves = PendingSaves(appScope)

    /** The requests for entries, both ways (SPEC §10): read when the vault unlocks, wiped when it locks. */
    val shareRequests = ShareRequests(FileSharingStore(File(context.noBackupFilesDir, "sharing/requests")), AndroidClock)

    private val sharingKey = SharingKey(File(context.noBackupFilesDir, "sharing/key"))

    /** The pictures of requests and answers, made to be sent; what a process that died left goes at once. */
    val codePictures = CodePictures(context).also(CodePictures::clear)

    /** The blocks of Groups & tags folded, by database: kept while the app runs (keething-spec plans/android-groups.md G7). */
    val foldedGroupBlocks = MutableStateFlow<Set<DatabaseId>>(emptySet())

    /** Codes another app handed over (a picture or a text shared to Keething), until the unlocked vault reads them. */
    val incomingCodes = MutableStateFlow<List<String>?>(null)

    /** The quick notes another app handed over so far, counted: a newer one gives up the one waiting (SPEC §6.4). */
    val quickNotes = MutableStateFlow(0)

    /**
     * What a database's Close says, with its Undo, what Locate… refused and what the chooser's run did: on the screen
     * showing (the list, Databases).
     */
    val windowNotices = Channel<Notice>(Channel.BUFFERED).also { notices -> sessions.kdbx3.tell = { notices.trySend(it) } }

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
        markTold = { ids ->
            for (id in ids) sessions.session(id)?.let { toldUnlocks.firstTime(ToldUnlocks.key(it.number, it.session.locks)) }
        },
        markTried = sessions::countTried,
    )

    /**
     * The parts of a large answer read so far (SPEC §10), for the whole app: a messenger hands its pictures over one at a
     * time. Pieces of a sealed answer, nothing secret.
     */
    val answerParts = PartCollector()

    init {
        // The list's records of before databases.saf that a granted folder already holds: nothing to ask for them (AC13).
        sessions.databases.adoptOldRecords()
        dealWithLeftovers()
        // A lock takes New database's form, with what was typed, unless it is being created.
        sessions.locks.onLock = {
            newDatabases.locked()
            masterKeys.locked()
        }
        clearOnLock()
        followTheLockWithTheRequests()
        keepStoredEntries()
    }

    /**
     * At the start: what an ended Create left (a small note, almost never there; its files reached through their grants,
     * off the main thread, and a Create waits for it), then every save through a granted folder that a stopped process
     * left is put right, listed or not (keething-spec databases.saf AC5; a removed database's unsettled save keeps its
     * grant until then), then every grant held that the list does not use goes (AC14).
     */
    private fun dealWithLeftovers() {
        val listed = sessions.list.records.flatMap { listOfNotNull(it.file, it.keyFile) }
        appScope.launch {
            try {
                withContext(Dispatchers.IO) { runCatching { creationNote.leftovers { made -> listed.any(made::isSameFile) } }.getOrNull() }
                    ?.let { newDatabases.notCreated = it }
            } finally {
                leftoversDealtWith.countDown()
            }
            withContext(Dispatchers.IO) { FolderDatabaseStore.putRightStoppedSaves(saveNotes, folderOf, AndroidClock) }
            unreadableSaves = withContext(Dispatchers.IO) { runCatching { saveNotes.unreadable().size }.getOrDefault(0) }
            grantKeeper.sweep()
        }
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
