package dev.keepassbar.android.session

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.keepassbar.android.settings.AppSettings
import dev.keepassbar.domain.biometric.BiometricAvailability
import dev.keepassbar.domain.biometric.BiometricPasswordStore
import dev.keepassbar.domain.settings.SettingsStore
import dev.keepassbar.domain.storage.DatabaseStore
import dev.keepassbar.vault.biometric.BiometricState
import dev.keepassbar.vault.biometric.BiometricUnlock
import dev.keepassbar.vault.opening.OpenFailure
import dev.keepassbar.vault.opening.VaultOpener
import dev.keepassbar.vault.session.LockReason
import dev.keepassbar.vault.session.SessionState
import dev.keepassbar.vault.session.VaultSession
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

/**
 * The chosen database, the session that opens it and its fingerprint unlock. [number] tells the sessions of
 * this process apart: choosing the same file again starts a new one, and a screen must not keep the old.
 */
internal class DatabaseSession(
    val number: Int,
    val file: File,
    val store: DatabaseStore,
    val session: VaultSession,
    val biometric: BiometricUnlock,
)

/** One stay of the app in the foreground: [number] counts them; [beganLocked]: the vault was locked when it began. */
internal data class Visit(val number: Int, val beganLocked: Boolean)

/**
 * Owns the session of the chosen database for the whole app (screens, later autofill and the code
 * notification), and tells it when the app goes to the background and comes back (SPEC §4). Choosing
 * another database locks the old session and starts a new one. Call it on the main thread.
 */
internal class Sessions(
    private val settings: AppSettings,
    private val settingsStore: SettingsStore,
    private val storeAt: (File) -> DatabaseStore,
    private val biometricStore: BiometricPasswordStore,
    private val scope: CoroutineScope,
    /** One task at a time: the fingerprint's saves and deletes stay in order. */
    private val biometricIo: CoroutineDispatcher,
    private val lockAlarm: LockAlarm,
) : DefaultLifecycleObserver {
    private var started = 0
    private val mutableCurrent = MutableStateFlow(settings.database.value?.let(::start))
    val current: StateFlow<DatabaseSession?> = mutableCurrent.asStateFlow()

    private val mutableVisit = MutableStateFlow(Visit(0, beganLocked = true))

    /** The lock screen offers the fingerprint once in a visit that began locked (SPEC §5), not after the lock button. */
    val visit: StateFlow<Visit> = mutableVisit.asStateFlow()

    fun chooseDatabase(file: File) {
        val previous = current.value
        previous?.session?.lock(LockReason.Initial)
        // The stored password opens the old database, not this one (SPEC §5).
        if (previous != null && previous.file != file) scope.launch { previous.biometric.forget() }
        settings.chooseDatabase(file)
        mutableCurrent.value = start(file)
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

    private fun start(file: File): DatabaseSession {
        val store = storeAt(file)
        val session = VaultSession(
            store = store,
            opener = VaultOpener(),
            clock = AndroidClock,
            scope = scope,
            io = Dispatchers.IO,
            lockTimeout = { settings.lockTimeout.value },
        )
        // Which states the session went through (names only, never data): how a lock came about.
        scope.launch { session.state.collect { Log.i(TAG, it.describe()) } }
        val biometric = BiometricUnlock(session, biometricStore, settingsStore, scope, biometricIo)
        scope.launch { biometric.refresh() }
        scope.launch {
            biometric.state.map { it.copy(prompting = false) }.distinctUntilChanged().collect { Log.i(TAG, it.describe()) }
        }
        return DatabaseSession(++started, file, store, session, biometric)
    }

    private fun BiometricState.describe(): String = "fingerprint: " + listOfNotNull(
        if (enabled) "on" else "off",
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
