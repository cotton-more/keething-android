package dev.keething.android.session

import android.os.SystemClock
import android.util.Log
import dev.keething.android.databases.CreatedDatabase
import dev.keething.android.databases.DatabaseLabel
import dev.keething.android.databases.DatabaseMark
import dev.keething.android.databases.DatabaseText
import dev.keething.android.databases.WindowDatabase
import dev.keething.android.editor.NewEntryDatabase
import dev.keething.android.databases.marks
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.keething.android.settings.AppSettings
import dev.keething.android.storage.FileDatabaseBackups
import dev.keething.android.ui.UiText
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.domain.biometric.BiometricPasswordStores
import dev.keething.domain.settings.SettingsStore
import dev.keething.domain.storage.DatabaseStore
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.biometric.FingerprintAnswer
import dev.keething.vault.biometric.QuickTasks
import dev.keething.vault.biometric.WindowFingerprint
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.databases.FilesThere
import dev.keething.vault.storage.KeyFiles
import dev.keething.vault.databases.CurrentDatabase
import dev.keething.domain.vault.Entry
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import dev.keething.vault.databases.OpenEntry
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.SessionsState
import dev.keething.vault.databases.UnlockRun
import dev.keething.vault.databases.VaultSessions
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.saving.BackupRestorer
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.RestoreOutcome
import dev.keething.vault.session.SaveRoom
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.VaultSession
import java.io.File
import kotlin.time.DurationUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One stay of the app in the foreground: [number] counts them; [beganLocked]: its lock screen showed when it began. */
internal data class Visit(val number: Int, val beganLocked: Boolean)

/**
 * An open database as the vault's screens show it (plan AD9): its session, what it read last, and how it is called and
 * coloured over the whole list (shown while two or more are open).
 */
internal class OpenDatabase(val database: DatabaseSession, val unlocked: SessionState.Unlocked, val mark: DatabaseMark)

/** The entries of [refs] as the open databases have them now, each a copy whose id is its ref; those not open are left out. */
internal fun List<OpenDatabase>.entries(refs: List<String>): List<Entry> = refs.mapNotNull { text ->
    val ref = EntryRef.of(text)
    firstOrNull { it.database.id == ref.database }?.unlocked?.vault?.entries?.firstOrNull { it.id == ref.entry }?.copy(id = text)
}

/**
 * Owns the databases' sessions for the whole app (screens, autofill, the code notification): the list of databases, a
 * session per database of the window and the app's state over them ([VaultSessions], plan AD8), the current one (whose
 * lock screen shows; [CurrentDatabase]) and the open ones the vault's screens list as one ([open], plan AD9). Tells the
 * sessions when the app goes to the background and comes back: one lock timeout for all (SPEC §4). Call it on the main
 * thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Sessions(
    private val settings: AppSettings,
    private val settingsStore: SettingsStore,
    val list: DatabaseList,
    /** How a database of the list is reached: by its path, or through a granted folder (keething-spec databases.saf AC2). */
    private val storeAt: (FileLocation) -> DatabaseStore,
    /** Each database's backups go into a folder of its own here, named by its id (SPEC §3.3 step 7). */
    private val backupsFolder: File,
    /** How a key file is reached wherever the list has it (keething-spec databases.saf AC9). */
    private val keyFiles: KeyFiles,
    /**
     * Whether a database's file and key file are there, through their grants: no fingerprint asked, nor a database asked
     * in the chooser's run, while one is not (keething-spec unlock.key-file AC3, databases.chooser AC6).
     */
    val filesThere: FilesThere,
    biometricStores: BiometricPasswordStores,
    private val scope: CoroutineScope,
    /** One task at a time: the fingerprint's saves and deletes stay in order. */
    biometricIo: CoroutineDispatcher,
    private val lockAlarm: LockAlarm,
    /** The shared storage's root: a database's label says its folder from there. */
    private val storageRoot: String,
    /** A grant the user gave for the list, given back unless the list takes it (keething-spec databases.saf AC14). */
    offered: (grant: String) -> Unit,
) : DefaultLifecycleObserver {
    /** The fingerprint's switch and days for the app, and each database's stored password (SPEC §5). */
    /** Each database's backups object ([backups]); before the sessions, which take theirs as they start. */
    private val backupsOf = mutableMapOf<DatabaseId, FileDatabaseBackups>()

    val biometric = BiometricSetup(biometricStores, list, settingsStore, AndroidClock, biometricIo)

    /** The app's locks, counted: a new database made across one stays locked, and New database's form goes. */
    val locks = AppLocks(SystemClock::elapsedRealtime)

    /** The question a KDBX 3.1 database asks, and its upgrade (keething-spec kdbx.kdbx3 AC5–AC8). */
    val kdbx3 = Kdbx3Question(scope)

    private val mutableReadOnlySaid = MutableStateFlow<UiText?>(null)

    /** Why a change was refused before it started, shown over every screen until OK (keething-spec app.large-database AC3). */
    val readOnlySaid: StateFlow<UiText?> = mutableReadOnlySaid.asStateFlow()

    /**
     * A change tried on [database] open read-only, before anything is typed or chosen (Edit, New, Move to group…, a
     * group's changes): a KDBX 3.1 one asks its question again (keething-spec kdbx.kdbx3 AC6), one too large to save
     * says so ([readOnlySaid]; app.large-database AC3).
     */
    fun readOnlyRefusal(database: DatabaseSession) {
        val unlocked = database.session.state.value as? SessionState.Unlocked ?: return
        val room = unlocked.room
        when {
            unlocked.viewOnly -> kdbx3.ask(database)
            room is SaveRoom.TooLarge -> {
                val name = open.value.firstOrNull { it.database === database }?.mark?.label?.full ?: File(database.location.fileName).name
                mutableReadOnlySaid.value = SizeTexts.refused(name, room)
            }
        }
    }

    /**
     * Locks every open database but [id], read-only because of them (keething-spec databases.read-only-mark AC4): nothing
     * asked; [id] becomes changeable once a save fits (app.large-database AC3).
     */
    fun lockOthers(id: DatabaseId) {
        vaults.openIds.filter { it != id }.forEach(databases::lock)
    }

    /** OK on [readOnlySaid]. */
    fun readOnlySaidOk() {
        mutableReadOnlySaid.value = null
    }

    /** A session per database of the window; Lock and the background timeout lock them all. */
    private val vaults = VaultSessions(list, scope, ::start, AndroidClock, lockTimeout = { settings.lockTimeout.value })

    /** Another database chosen, a file opened or located, a key file, Remove from List. */
    val databases = CurrentDatabase(list, scope, vaults, biometric, offered)

    /** One fingerprint for every database of the window that can (plan AD13): the chooser's. */
    val windowFingerprint = WindowFingerprint(vaults, biometric, list, filesThere::isThere)

    /** The current database's session: the one the lock screen shows. */
    val current: StateFlow<DatabaseSession?> = databases.current

    /** What the screens show: nothing open (and why), a database opening, or the open vaults (SPEC §4). */
    val state: StateFlow<SessionsState> = vaults.state

    /** A database's session, while it is in the window. */
    fun session(id: DatabaseId): DatabaseSession? = vaults.session(id)

    /**
     * The database's backups, one object for its sessions and its page, with its switch and number kept as its record
     * has them at each save (keething-spec databases.backups-window AC2).
     */
    fun backups(id: DatabaseId): FileDatabaseBackups = synchronized(backupsOf) {
        backupsOf.getOrPut(id) {
            FileDatabaseBackups(
                File(backupsFolder, id.fileName),
                count = { list.record(id)?.backupsKept ?: DatabaseRecord.DEFAULT_BACKUPS_KEPT },
                isOn = { list.record(id)?.backupsOn ?: true },
            )
        }
    }

    /**
     * Restores [id]'s backup named [name] (keething-spec databases.backups-window AC6-AC8), on the app's scope: the page
     * may go meanwhile. Through its session while it is in the window, else the file alone. Restored over an open
     * database whose key it does not open with, the fingerprint's stored password goes (AC7). [done] hears how it ended.
     */
    fun restoreBackup(id: DatabaseId, name: String, done: (RestoreOutcome) -> Unit) {
        scope.launch {
            val session = vaults.session(id)?.session
            val outcome = when {
                session != null -> session.restoreBackup(name).await()
                else -> {
                    val record = list.record(id)
                    if (record == null) {
                        RestoreOutcome.Failed(SaveFailure.CannotOpen(OpenFailure.Missing))
                    } else {
                        withContext(Dispatchers.IO) { BackupRestorer(storeAt(record.file), backups(id), AndroidClock).restore(name) }
                    }
                }
            }
            if (outcome is RestoreOutcome.Restored && outcome.olderKey) {
                biometric.restoredOlderKey(id)?.let { Log.w(TAG, "stored password not deleted after a restore: $it") }
            }
            done(outcome)
        }
    }

    /** The entry of a ref in its own open database, with that database's session (keething-spec databases.one-list AC2). */
    fun entry(ref: EntryRef): OpenEntry? = vaults.entry(ref)

    /**
     * The open databases in the window's order, each as it read its file last (its conflict copies, a failed reload) and
     * with its mark: one list over them (keething-spec databases.one-list).
     */
    val open: StateFlow<List<OpenDatabase>> = state
        .map { state -> (state as? SessionsState.Open)?.vaults?.mapNotNull { vaults.session(it.id) }.orEmpty() }
        .distinctUntilChanged { a, b -> a.size == b.size && a.indices.all { a[it] === b[it] } }
        .flatMapLatest { databases ->
            if (databases.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(databases.map { database -> database.session.state.map { database to it } }) { it.toList() }
        }
        .combine(list.state.map { it.marks(storageRoot) }.distinctUntilChanged()) { states, marks ->
            states.mapNotNull { (database, state) ->
                (state as? SessionState.Unlocked)?.let { OpenDatabase(database, it, marks[database.id] ?: DatabaseMark(database.id, DatabaseLabel(database.location.fileName), 0)) }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The first open database to be asked whether to use the fingerprint, after a typed unlock opened it (keething-spec
     * unlock.biometric AC4): its list asks it.
     */
    val fingerprintQuestion: StateFlow<DatabaseSession?> = open
        .map { open -> open.map { it.database } }
        .distinctUntilChanged { a, b -> a.size == b.size && a.indices.all { a[it] === b[it] } }
        .flatMapLatest { databases ->
            if (databases.isEmpty()) return@flatMapLatest flowOf(null)
            combine(databases.map { database -> database.biometric.state.map { database.takeIf { _ -> it.question } } }) { asked ->
                asked.firstOrNull { it != null }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * [database]'s answer to [fingerprintQuestion] ([dev.keething.vault.biometric.BiometricUnlock.answer]), on the app's
     * scope: storing the password outlives the list. [done] is told what came of it.
     */
    fun answerFingerprint(database: DatabaseSession, use: Boolean?, done: (FingerprintAnswer) -> Unit) {
        scope.launch { done(database.biometric.answer(use)) }
    }

    /**
     * Autofill, the codes shortcut and a quick note: what they open asks nothing, and what still has the reminder's
     * question standing when the app leaves the foreground locks again (keething-spec unlock.reminder-not-a-lock AC5).
     */
    val quickTasks = QuickTasks(vaults)

    /**
     * The first open database whose reminder asks over its list (keething-spec unlock.reminder-not-a-lock AC1): several
     * opened at once are asked one after another; one a quick task opened is not asked (AC5).
     */
    val reminderQuestion: StateFlow<DatabaseSession?> = open
        .map { open -> open.map { it.database } }
        .distinctUntilChanged { a, b -> a.size == b.size && a.indices.all { a[it] === b[it] } }
        .flatMapLatest { databases ->
            if (databases.isEmpty()) return@flatMapLatest flowOf(null)
            combine(databases.map { database -> database.biometric.state.map { database.takeIf { _ -> it.reminder } } }) { it.toList() }
                .combine(quickTasks.found) { asked, found -> asked.firstOrNull { it != null && it.id !in found } }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The open databases as they are this moment, in the window's order ([open] follows them a moment later): autofill
     * answers the system at once.
     */
    fun openNow(): List<OpenDatabase> {
        val ids = (state.value as? SessionsState.Open)?.vaults?.map { it.id } ?: return emptyList()
        val marks = list.state.value.marks(storageRoot)
        return ids.mapNotNull { id ->
            val database = vaults.session(id) ?: return@mapNotNull null
            val unlocked = database.session.state.value as? SessionState.Unlocked ?: return@mapNotNull null
            OpenDatabase(database, unlocked, marks[id] ?: DatabaseMark(id, DatabaseLabel(database.location.fileName), 0))
        }
    }

    /**
     * Each database of the window, in its order, as its session and its fingerprint have it now (plan AD12): those not
     * open say their state (keething-spec databases.not-open AC1, AC7: the fingerprint's changes followed at once).
     */
    val window: StateFlow<List<WindowDatabase>> = list.state
        .map { databases -> databases.window.map { it.id } }
        .combine(state) { ids, _ -> ids.mapNotNull(vaults::session) }
        .distinctUntilChanged { a, b -> a.size == b.size && a.indices.all { a[it] === b[it] } }
        .flatMapLatest { databases ->
            if (databases.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(databases.map { database -> combine(database.session.state, database.biometric.state) { session, fingerprint -> WindowDatabase(database.id, session, fingerprint) } }) { it.toList() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Each database of the window asks again whether the fingerprint can open it and whether its password is due: the
     * line of those not open says so after a stay away (fingerprints removed in the phone's settings, the days passed).
     */
    suspend fun refreshWindowFingerprints() {
        biometric.refresh()
        list.window.mapNotNull { vaults.session(it.id) }.forEach { it.biometric.refresh() }
    }

    /**
     * The [open] databases as a new entry sees them: where it can go (keething-spec databases.one-list AC4, AC5). One
     * read-only (KDBX 3.1, keething-spec kdbx.kdbx3 AC6; too large to save, app.large-database AC3) takes nothing:
     * neither a new entry nor a moved one, nor a group.
     */
    fun newEntryDatabases(open: List<OpenDatabase>): List<NewEntryDatabase> = open.filterNot { it.unlocked.readOnly }.map {
        NewEntryDatabase(it.database.id, it.database.number, it.unlocked.vault, it.mark, DatabaseText.folder(DatabaseText.pathOf(it.database.location, storageRoot), storageRoot))
    }

    /** The window's databases as the screens name them, in the window's order: Save into names those not open. */
    fun windowMarks(): List<DatabaseMark> {
        val databases = list.state.value
        val marks = databases.marks(storageRoot)
        return databases.window.mapNotNull { marks[it.id] }
    }

    private val mutableTried = MutableStateFlow<Set<DatabaseId>>(emptySet())

    /**
     * The databases an unlock was tried on since the last Lock or timeout, however it ended (keething-spec
     * databases.not-open AC1): those of them not open are the ones that need attention.
     */
    val tried: StateFlow<Set<DatabaseId>> = mutableTried.asStateFlow()

    init {
        // Lock and the timeout, by any way it came: counted once each.
        vaults.observeLocks { reason ->
            Log.i(TAG, "lock all, ${SessionState.Locked(reason).describe()}")
            mutableTried.value = emptySet()
            locks.locked()
        }
        // In the background with a database open or opening (also one that opens there: a Create, a late fingerprint).
        vaults.observeTimer { left ->
            Log.i(TAG, "background, lock after ${left.toString(DurationUnit.SECONDS, 0)}")
            lockAlarm.schedule(left)
        }
        // What the screens show (names and counts only, never data); a session dropped says nothing of itself.
        scope.launch {
            state.collect {
                Log.i(TAG, "app: " + when (it) {
                    is SessionsState.NothingOpen -> "nothing open, " + SessionState.Locked(it.reason).describe() // not user text
                    SessionsState.Opening -> "opening"
                    is SessionsState.Open -> "${it.vaults.size} open" // not user text
                })
            }
        }
    }

    private val mutableVisit = MutableStateFlow(Visit(0, beganLocked = true))

    /**
     * The lock screen offers the fingerprint once in a visit that began on it (SPEC §5; the current database not open,
     * whatever else is), not after the lock button.
     */
    val visit: StateFlow<Visit> = mutableVisit.asStateFlow()

    /** Whether the lock screen shown now asks for the fingerprint by itself ([FingerprintOffers]). */
    val fingerprintOffers = FingerprintOffers()

    /**
     * Remove from List; [onProblem] hears why the stored password could not be deleted. In the app's scope: the screen
     * that asked goes at once (the next database's lock screen, or the welcome).
     */
    fun remove(id: DatabaseId, onProblem: (String) -> Unit) {
        scope.launch { databases.remove(id)?.let(onProblem) }
    }

    /** The Lock button: every database. */
    fun lock() = vaults.lockAll(LockReason.Manual)

    /**
     * The chooser's run over [ids] (plan AD13): the app's locks end it ([locks]: a stay away past the timeout with nothing
     * open counts too).
     */
    fun run(ids: List<DatabaseId>): UnlockRun = databases.run(ids, locks = { locks.count }, fileExists = filesThere::isThere)

    /** The vault on the window's first open database, unless the current one is open: after the chooser or its run. */
    fun showOpened() {
        val open = (state.value as? SessionsState.Open)?.vaults?.map { it.id }.orEmpty()
        if (list.currentId !in open) open.firstOrNull()?.let(databases::choose)
    }

    /**
     * How many times the app locked so far ([locks]): every Lock and timeout, and a stay away past the timeout with
     * nothing open, which [VaultSessions.lockCount] does not count (it guards the fingerprint, whose prompt the system
     * closes in the background anyway); a new database and the chooser's run go by this one.
     */
    val lockCount: Int get() = locks.count

    /**
     * A new database made on the New database screen joins the list, becomes current and opens with the key it was made
     * with, unless the app locked since Create was tapped ([made]'s lock count); [file] and [keyFile]: where it and its
     * key file are, through their grants. Made while the app was in the background, its timeout starts with its opening,
     * and the alarm with it. Why an old record's stored password could not be deleted, or null.
     */
    suspend fun created(made: CreatedDatabase, file: FileLocation, keyFile: FileLocation?): String? =
        databases.created(file, keyFile, made.useFingerprint, made.key, made.password, made.fingerprintChosen) {
            locks.lockedSince(made.lockCount, settings.lockTimeout.value)
        }.problem

    /** The lock alarm went off. */
    fun checkBackgroundTime() {
        // Autofill asks too, at every fill request: only a real stay past the timeout is a lock.
        if (locks.awayPast(settings.lockTimeout.value)) locks.locked()
        vaults.checkBackgroundTime()
    }

    override fun onStart(owner: LifecycleOwner) {
        Log.i(TAG, "foreground")
        // Away past the timeout, with or without a vault open: a lock (an unopened new database stays locked).
        locks.movedToForeground(settings.lockTimeout.value)
        lockAlarm.cancel()
        vaults.movedToForeground()
        // Its lock screen showing: the current database not open, whatever else is (plan AD9).
        val shown = current.value?.id
        mutableVisit.value = Visit(visit.value.number + 1, beganLocked = (state.value as? SessionsState.Open)?.vaults?.none { it.id == shown } ?: true)
    }

    /**
     * The quick notes waiting behind a screen they opened (SPEC §6.4): while there is one, the lock alarm wakes the app
     * for it at the timeout, nothing open to lock too, and counts the stay away ([checkBackgroundTime]), which gives
     * the quick note up. Call it on the main thread.
     */
    var quickNoteAwaited: Int = 0

    override fun onStop(owner: LifecycleOwner) {
        locks.movedToBackground()
        quickTasks.end()
        // Before the databases' own timer, which sets the same alarm to the time they have left.
        if (quickNoteAwaited > 0) lockAlarm.schedule(settings.lockTimeout.value)
        // A database open or opening: the timer starts, and the alarm with it (above).
        vaults.movedToBackground()
    }

    private fun start(record: DatabaseRecord, number: Int, keyFile: () -> FileLocation?, watching: CoroutineScope): DatabaseSession {
        val store = storeAt(record.file)
        val session = VaultSession(
            store = store,
            opener = VaultOpener(),
            backups = backups(record.id),
            clock = AndroidClock,
            scope = scope,
            io = Dispatchers.IO,
            keyFile = { keyFile()?.let(keyFiles::at) },
        )
        session.onUnlockStarted = { mutableTried.update { it + record.id } }
        // Which states the session went through (names only, never data): how a lock came about.
        watching.launch { session.state.collect { Log.i(TAG, "${record.id.fileName.take(8)} ${it.describe()}") } }
        session.onSaveTimed = { took, saved -> Log.i(TAG, "${record.id.fileName.take(8)} save ${if (saved) "done" else "failed"} in ${took.inWholeMilliseconds} ms") }
        // The app's lock count: a Lock that finds this database locked already leaves its own as it was.
        val fingerprint = BiometricUnlock(record.id, session, biometric, list, scope, watching, filesThere::isThere, appLocks = { vaults.lockCount })
        watching.launch { fingerprint.refresh() }
        watching.launch {
            fingerprint.state.map { it.copy(prompting = false, opening = false) }.distinctUntilChanged().collect { Log.i(TAG, it.describe()) }
        }
        return DatabaseSession(number, record.id, record.file, store, session, fingerprint).also { kdbx3.follow(it, watching) }
    }

    private fun BiometricState.describe(): String = "fingerprint: " + listOfNotNull(
        if (appEnabled) "on" else "off",
        if (databaseEnabled) "on for it" else "off for it", // not user text
        "opens with its key file alone".takeIf { passwordless }, // not user text
        when (val availability = availability) {
            null -> "not checked" // not user text
            BiometricAvailability.Available -> "available"
            is BiometricAvailability.Unavailable -> "unavailable (${availability.reason})"
        },
        if (stored) "stored" else "nothing stored", // not user text
        setupFailure?.let { "setup failed ($it)" }, // not user text
    ).joinToString(", ")

    private fun SessionState.describe(): String = when (this) {
        is SessionState.Locked -> "locked: " + when (val reason = reason) {
            LockReason.Initial -> "initial"
            LockReason.Manual -> "manual"
            LockReason.Timeout -> "timeout"
            LockReason.PasswordChanged -> "password changed" // not user text
            LockReason.RestoredOlderKey -> "backup restored, older key" // not user text
            LockReason.AfterQuickTask -> "after a quick task, its reminder due" // not user text
            is LockReason.UnlockFailed -> "unlock failed, " + when (val failure = reason.failure) { // not user text
                OpenFailure.WrongPassword -> "wrong password" // not user text
                OpenFailure.WrongPasswordOrKeyFile -> "wrong password or key file" // not user text
                OpenFailure.WrongKeyFile -> "wrong key file" // not user text
                is OpenFailure.KeyFileMissing -> "key file missing" // not user text
                is OpenFailure.KeyFileUnusable -> "key file unusable: ${failure.why}" // not user text
                OpenFailure.Missing -> "missing"
                OpenFailure.AccessDenied -> "access denied" // not user text
                is OpenFailure.Unsupported -> "unsupported"
                is OpenFailure.Damaged -> "damaged: ${failure.detail}"
                is OpenFailure.ReadFailed -> "read failed: ${failure.detail}" // not user text
                OpenFailure.OutOfMemory -> "out of memory" // not user text
                OpenFailure.Locked -> "locked meanwhile" // not user text
            }
        }
        SessionState.Unlocking -> "unlocking"
        // Times and counts only (keething-spec app.large-database).
        is SessionState.Unlocked -> "unlocked: ${vault.entries.size} entries" + (keyDerivation?.let { ", key derived in ${it.inWholeMilliseconds} ms" } ?: "") + // not user text
            ", ${size.fileBytes shr 20} MiB, room $room" // not user text
    }

    private companion object {
        const val TAG = "Session" // not user text
    }
}
