package dev.keething.android.session

import android.os.SystemClock
import android.util.Log
import dev.keething.android.databases.CreatedDatabase
import dev.keething.android.databases.DatabaseLabel
import dev.keething.android.databases.DatabaseMark
import dev.keething.android.databases.marks
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.keething.android.settings.AppSettings
import dev.keething.android.storage.FileDatabaseBackups
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.domain.biometric.BiometricPasswordStores
import dev.keething.domain.settings.SettingsStore
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.KeyFileSource
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.databases.CurrentDatabase
import dev.keething.domain.vault.Entry
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import dev.keething.vault.databases.OpenEntry
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.SessionsState
import dev.keething.vault.databases.VaultSessions
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.LockReason
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
import kotlinx.coroutines.launch

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
    private val storeAt: (File) -> DatabaseStore,
    /** Each database's backups go into a folder of its own here, named by its id (SPEC §3.3 step 7). */
    private val backupsFolder: File,
    private val keyFileAt: (File) -> KeyFileSource,
    biometricStores: BiometricPasswordStores,
    private val scope: CoroutineScope,
    /** One task at a time: the fingerprint's saves and deletes stay in order. */
    biometricIo: CoroutineDispatcher,
    private val lockAlarm: LockAlarm,
    /** The shared storage's root: a database's label says its folder from there. */
    private val storageRoot: String,
) : DefaultLifecycleObserver {
    /** The fingerprint's switch and days for the app, and each database's stored password (SPEC §5). */
    val biometric = BiometricSetup(biometricStores, list, settingsStore, AndroidClock, biometricIo)

    /** The app's locks, counted: a new database made across one stays locked, and New database's form goes. */
    val locks = AppLocks(SystemClock::elapsedRealtime)

    /** A session per database of the window; Lock and the background timeout lock them all. */
    private val vaults = VaultSessions(list, scope, ::start, AndroidClock, lockTimeout = { settings.lockTimeout.value })

    /** Another database chosen, a file opened or located, a key file, Remove from List. */
    val databases = CurrentDatabase(list, scope, vaults, biometric)

    /** The current database's session: the one the lock screen shows. */
    val current: StateFlow<DatabaseSession?> = databases.current

    /** What the screens show: nothing open (and why), a database opening, or the open vaults (SPEC §4). */
    val state: StateFlow<SessionsState> = vaults.state

    /** A database's session, while it is in the window. */
    fun session(id: DatabaseId): DatabaseSession? = vaults.session(id)

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
                (state as? SessionState.Unlocked)?.let { OpenDatabase(database, it, marks[database.id] ?: DatabaseMark(database.id, DatabaseLabel(database.file.name), 0)) }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Lock and the timeout, by any way it came: counted once each.
        vaults.observeLocks { reason ->
            Log.i(TAG, "lock all, ${SessionState.Locked(reason).describe()}")
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
                    is SessionsState.NothingOpen -> "nothing open, " + SessionState.Locked(it.reason).describe()
                    SessionsState.Opening -> "opening"
                    is SessionsState.Open -> "${it.vaults.size} open"
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

    /** How many times the app locked so far ([locks]). */
    val lockCount: Int get() = locks.count

    /**
     * A new database made on the New database screen joins the list, becomes current and opens with the key it was made
     * with, unless the app locked since Create was tapped ([made]'s lock count). Made while the app was in the
     * background, its timeout starts with its opening, and the alarm with it. Why an old record's stored password could
     * not be deleted, or null.
     */
    suspend fun created(made: CreatedDatabase): String? =
        databases.created(made.path, made.keyFilePath, made.useFingerprint, made.key, made.password) {
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

    override fun onStop(owner: LifecycleOwner) {
        locks.movedToBackground()
        // A database open or opening: the timer starts, and the alarm with it (above).
        vaults.movedToBackground()
    }

    private fun start(record: DatabaseRecord, number: Int, keyFilePath: () -> String?, watching: CoroutineScope): DatabaseSession {
        val file = File(record.path)
        val store = storeAt(file)
        val session = VaultSession(
            store = store,
            opener = VaultOpener(),
            backups = FileDatabaseBackups(File(backupsFolder, record.id.fileName)),
            clock = AndroidClock,
            scope = scope,
            io = Dispatchers.IO,
            keyFile = { keyFilePath()?.let { keyFileAt(File(it)) } },
        )
        // Which states the session went through (names only, never data): how a lock came about.
        watching.launch { session.state.collect { Log.i(TAG, "${record.id.fileName.take(8)} ${it.describe()}") } }
        val fingerprint = BiometricUnlock(record.id, session, biometric, list, scope, watching)
        watching.launch { fingerprint.refresh() }
        watching.launch {
            fingerprint.state.map { it.copy(prompting = false, opening = false) }.distinctUntilChanged().collect { Log.i(TAG, it.describe()) }
        }
        return DatabaseSession(number, record.id, file, store, session, fingerprint)
    }

    private fun BiometricState.describe(): String = "fingerprint: " + listOfNotNull(
        if (appEnabled) "on" else "off",
        if (databaseEnabled) "on for it" else "off for it",
        "opens with its key file alone".takeIf { passwordless },
        when (val availability = availability) {
            null -> "not checked"
            BiometricAvailability.Available -> "available"
            is BiometricAvailability.Unavailable -> "unavailable (${availability.reason})"
        },
        if (stored) "stored" else "nothing stored",
        setupFailure?.let { "setup failed ($it)" },
    ).joinToString(", ")

    private fun SessionState.describe(): String = when (this) {
        is SessionState.Locked -> "locked: " + when (val reason = reason) {
            LockReason.Initial -> "initial"
            LockReason.Manual -> "manual"
            LockReason.Timeout -> "timeout"
            LockReason.PasswordChanged -> "password changed"
            is LockReason.UnlockFailed -> "unlock failed, " + when (val failure = reason.failure) {
                OpenFailure.WrongPassword -> "wrong password"
                OpenFailure.WrongPasswordOrKeyFile -> "wrong password or key file"
                OpenFailure.WrongKeyFile -> "wrong key file"
                is OpenFailure.KeyFileMissing -> "key file missing"
                is OpenFailure.KeyFileUnusable -> "key file unusable: ${failure.why}"
                OpenFailure.Missing -> "missing"
                OpenFailure.AccessDenied -> "access denied"
                is OpenFailure.Unsupported -> "unsupported"
                is OpenFailure.Damaged -> "damaged: ${failure.detail}"
                OpenFailure.OutOfMemory -> "out of memory"
            }
        }
        SessionState.Unlocking -> "unlocking"
        is SessionState.Unlocked -> "unlocked"
    }

    private companion object {
        const val TAG = "Session"
    }
}
