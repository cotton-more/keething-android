package dev.keething.android.unlock

import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.session.Visit
import dev.keething.android.storage.AllFilesAccess
import dev.keething.domain.secret.Secret
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.FingerprintOutcome
import dev.keething.vault.databases.CurrentDatabase
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.session.SessionState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LockViewModel(
    private val database: DatabaseSession,
    private val databases: CurrentDatabase,
    private val list: DatabaseList,
    /** Once in a visit that began locked, for the app ([dev.keething.android.session.FingerprintOffers]). */
    private val offersFingerprint: (Visit) -> Boolean,
) : ViewModel() {
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

    private val mutableAccessGranted = MutableStateFlow(AllFilesAccess.isGranted())
    val accessGranted: StateFlow<Boolean> = mutableAccessGranted.asStateFlow()

    /** On every return to the screen: the access may have been granted, a fingerprint added or removed. */
    fun refresh() {
        mutableAccessGranted.value = AllFilesAccess.isGranted()
        viewModelScope.launch { database.biometric.refresh() }
    }

    /**
     * Asks for the fingerprint by itself once in a [visit] that began locked: not after the lock button (SPEC §5), nor
     * for another database chosen meanwhile, nor while the file or its key file is not there (it could not open;
     * keething-spec unlock.key-file AC3). A database that opens with its key file alone opens after it with nothing
     * stored.
     */
    fun offerFingerprint(visit: Visit) {
        if (!offersFingerprint(visit)) return
        viewModelScope.launch {
            if (database.biometric.refresh().canUnlock && AllFilesAccess.isGranted() && fingerprintCanOpen()) fingerprint()
        }
    }

    private suspend fun fingerprintCanOpen(): Boolean = withContext(Dispatchers.IO) {
        val record = list.record(database.id) ?: return@withContext false
        File(record.path).isFile && record.keyFilePath?.let { File(it).isFile } != false
    }

    /** The lock screen's "Use fingerprint next time": this database's own switch, so the password typed next is stored. */
    fun useFingerprint(on: Boolean) {
        viewModelScope.launch { database.biometric.useNextTime(on)?.let { mutableFingerprintNotice.value = LockNotice("The stored password could not be deleted: $it.", isProblem = true) } }
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

    /**
     * The typed password, stored for the fingerprint when that is on (SPEC §5). Nothing typed opens a database with a
     * key file (its key file alone, then with the empty password); without one it does nothing.
     */
    fun unlock() {
        if (password.text.isEmpty() && list.record(database.id)?.keyFilePath == null) return
        val secret = Secret.of(password.text.toString())
        lastAttemptByFingerprint = false
        mutableFingerprintNotice.value = null
        databases.unlock(database, secret)
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
