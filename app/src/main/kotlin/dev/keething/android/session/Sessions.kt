package dev.keething.android.session

import android.util.Log
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
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.VaultSession
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One stay of the app in the foreground: [number] counts them; [beganLocked]: the vault was locked when it began. */
internal data class Visit(val number: Int, val beganLocked: Boolean)

/**
 * Owns the current database's session for the whole app (screens, autofill, the code notification): the list of
 * databases and the one of them open ([CurrentDatabase], one at a time until several, plan AD8). Tells the session
 * when the app goes to the background and comes back (SPEC §4). Call it on the main thread.
 */
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
) : DefaultLifecycleObserver {
    /** The fingerprint's switch and days for the app, and each database's stored password (SPEC §5). */
    val biometric = BiometricSetup(biometricStores, list, settingsStore, AndroidClock, biometricIo)

    /** Another database chosen, a file opened or located, a key file, Remove from List. */
    val databases = CurrentDatabase(list, scope, ::start, biometric)

    val current: StateFlow<DatabaseSession?> = databases.current

    private val mutableVisit = MutableStateFlow(Visit(0, beganLocked = true))

    /** The lock screen offers the fingerprint once in a visit that began locked (SPEC §5), not after the lock button. */
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

    fun lock() {
        current.value?.session?.lock()
    }

    /** The lock alarm went off. */
    fun checkBackgroundTime() {
        current.value?.session?.checkBackgroundTime()
    }

    override fun onStart(owner: LifecycleOwner) {
        Log.i(TAG, "foreground")
        lockAlarm.cancel()
        val session = current.value?.session
        session?.movedToForeground()
        mutableVisit.value = Visit(visit.value.number + 1, beganLocked = session?.state?.value !is SessionState.Unlocked)
    }

    override fun onStop(owner: LifecycleOwner) {
        val session = current.value?.session ?: return
        Log.i(TAG, "background, lock after ${settings.lockTimeout.value}")
        session.movedToBackground()
        if (session.state.value !is SessionState.Locked) lockAlarm.schedule(settings.lockTimeout.value)
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
            lockTimeout = { settings.lockTimeout.value },
            keyFile = { keyFilePath()?.let { keyFileAt(File(it)) } },
        )
        // Which states the session went through (names only, never data): how a lock came about.
        watching.launch { session.state.collect { Log.i(TAG, it.describe()) } }
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
