package dev.keepassbar.android.session

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.keepassbar.android.settings.AppSettings
import dev.keepassbar.domain.storage.DatabaseStore
import dev.keepassbar.vault.opening.OpenFailure
import dev.keepassbar.vault.opening.VaultOpener
import dev.keepassbar.vault.session.LockReason
import dev.keepassbar.vault.session.SessionState
import dev.keepassbar.vault.session.VaultSession
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The chosen database and the session that opens it. */
internal class DatabaseSession(val file: File, val store: DatabaseStore, val session: VaultSession)

/**
 * Owns the session of the chosen database for the whole app (screens, later autofill and the code
 * notification), and tells it when the app goes to the background and comes back (SPEC §4). Choosing
 * another database locks the old session and starts a new one. Call it on the main thread.
 */
internal class Sessions(
    private val settings: AppSettings,
    private val storeAt: (File) -> DatabaseStore,
    private val scope: CoroutineScope,
    private val lockAlarm: LockAlarm,
) : DefaultLifecycleObserver {
    private val mutableCurrent = MutableStateFlow(settings.database.value?.let(::start))
    val current: StateFlow<DatabaseSession?> = mutableCurrent.asStateFlow()

    fun chooseDatabase(file: File) {
        current.value?.session?.lock(LockReason.Initial)
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
        current.value?.session?.movedToForeground()
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
        return DatabaseSession(file, store, session)
    }

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
