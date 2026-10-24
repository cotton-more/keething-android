package dev.keething.android.unlock

import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.session.DatabaseSession
import dev.keething.android.session.Visit
import dev.keething.android.storage.AllFilesAccess
import dev.keething.domain.secret.Secret
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.FingerprintOutcome
import dev.keething.vault.session.SessionState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The database file as the lock screen describes it. */
internal data class FileInfo(val exists: Boolean, val modified: Instant?)

internal class LockViewModel(private val database: DatabaseSession) : ViewModel() {
    /**
     * The typed master password. Kept here, not in the screen's saved state: a saved state would put it
     * into a Bundle. The `String`s the field makes cannot be wiped (accepted, SPEC §4); the field is
     * cleared as soon as the unlock starts.
     */
    val password = TextFieldState()

    val session: StateFlow<SessionState> = database.session.state

    val biometric: StateFlow<BiometricState> = database.biometric.state

    private val mutableFingerprintNotice = MutableStateFlow<LockNotice?>(null)

    /** What the last fingerprint attempt has to say; shown in place of the reason chip until the next attempt. */
    val fingerprintNotice: StateFlow<LockNotice?> = mutableFingerprintNotice.asStateFlow()

    /** The last attempt was the fingerprint: a stored password the database rejected is no typo to shake for. */
    var lastAttemptByFingerprint = false
        private set

    private val mutablePasswordWanted = MutableStateFlow(0)

    /** Counts the times the fingerprint prompt was closed without a fingerprint: the password field takes the focus. */
    val passwordWanted: StateFlow<Int> = mutablePasswordWanted.asStateFlow()

    private var offeredInVisit = -1

    private val mutableFileInfo = MutableStateFlow<FileInfo?>(null)
    val fileInfo: StateFlow<FileInfo?> = mutableFileInfo.asStateFlow()

    private val mutableAccessGranted = MutableStateFlow(AllFilesAccess.isGranted())
    val accessGranted: StateFlow<Boolean> = mutableAccessGranted.asStateFlow()

    /**
     * On every return to the screen: the access may have been granted, the file may have changed, a
     * fingerprint may have been added or removed.
     */
    fun refresh() {
        mutableAccessGranted.value = AllFilesAccess.isGranted()
        viewModelScope.launch {
            mutableFileInfo.value = withContext(Dispatchers.IO) {
                val version = runCatching { database.store.version() }.getOrNull()
                FileInfo(exists = version != null, modified = version?.modified)
            }
        }
        viewModelScope.launch { database.biometric.refresh() }
    }

    /** Asks for the fingerprint by itself once in a [visit] that began locked: not after the lock button (SPEC §5). */
    fun offerFingerprint(visit: Visit) {
        if (!visit.beganLocked || visit.number == offeredInVisit) return
        offeredInVisit = visit.number
        viewModelScope.launch {
            database.biometric.refresh()
            if (database.biometric.state.value.canUnlock && AllFilesAccess.isGranted()) fingerprint()
        }
    }

    /** The lock screen's "Use fingerprint next time": the Settings switch, so the password typed next is stored. */
    fun useFingerprint(on: Boolean) {
        viewModelScope.launch { database.biometric.setEnabled(on) }
    }

    fun unlockWithFingerprint() {
        viewModelScope.launch { fingerprint() }
    }

    private suspend fun fingerprint() {
        val before = lastAttemptByFingerprint
        lastAttemptByFingerprint = true
        val outcome = database.biometric.unlockWithFingerprint()
        Log.i(TAG, "fingerprint: $outcome")
        if (outcome == FingerprintOutcome.InProgress) lastAttemptByFingerprint = before
        mutableFingerprintNotice.value = outcome.notice()
        if (outcome == FingerprintOutcome.Cancelled) mutablePasswordWanted.value++
    }

    /** The typed password; stored for the fingerprint when that is on (SPEC §5). */
    fun unlock() {
        if (password.text.isEmpty()) return
        val secret = Secret.of(password.text.toString())
        lastAttemptByFingerprint = false
        mutableFingerprintNotice.value = null
        database.biometric.unlock(secret)
        secret.wipe()
        password.clearText()
    }

    override fun onCleared() {
        password.clearText()
    }

    private companion object {
        const val TAG = "Lock"
    }
}
