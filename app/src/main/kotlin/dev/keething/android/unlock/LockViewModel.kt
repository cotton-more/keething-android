package dev.keething.android.unlock

import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.session.Visit
import dev.keething.domain.secret.Secret
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.FingerprintOutcome
import dev.keething.vault.databases.CurrentDatabase
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /** A password was typed here since its fingerprint was last tried: what the database said is of the typing. */
    var typedHere = false
        private set

    private val mutablePasswordWanted = MutableStateFlow(0)

    /** Counts the times the password is wanted: the fingerprint prompt closed without a fingerprint. The field takes the focus. */
    val passwordWanted: StateFlow<Int> = mutablePasswordWanted.asStateFlow()

    /** On every return to the screen: a fingerprint may have been added or removed. */
    fun refresh() {
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
            if (database.biometric.refresh().canUnlock && fingerprintCanOpen()) fingerprint()
        }
    }

    private fun fingerprintCanOpen(): Boolean {
        val record = list.record(database.id) ?: return false
        return FileLocation.isReachable(record.file) && record.keyFile?.let(FileLocation::isReachable) != false
    }

    /** The fingerprint's button beside the field, when it can open the database. */
    fun fingerprintTapped() {
        if (database.biometric.current().canUnlock) viewModelScope.launch { fingerprint() }
    }

    private suspend fun fingerprint() {
        val before = lastAttemptByFingerprint
        lastAttemptByFingerprint = true
        typedHere = false
        val outcome = database.biometric.unlockWithFingerprint()
        Log.i(TAG, "fingerprint: $outcome")
        if (outcome == FingerprintOutcome.InProgress) lastAttemptByFingerprint = before
        mutableFingerprintNotice.value = outcome.notice()
        if (outcome == FingerprintOutcome.Cancelled) mutablePasswordWanted.value++
    }

    /**
     * The typed password, stored for the fingerprint when that is on (SPEC §5). Nothing typed opens a database with a
     * key file (its key file alone, then with the empty password); without one it does nothing. [through]: the chooser's
     * run takes it (plan AD13), and wipes it after its try. [askFingerprint]: a database whose fingerprint was never chosen
     * is asked whether to use it over the list once it opened (keething-spec unlock.biometric AC4).
     */
    fun unlock(through: ((Secret) -> Unit)? = null, askFingerprint: Boolean = false) {
        if (password.text.isEmpty() && list.record(database.id)?.keyFile == null) return
        val secret = Secret.of(password.text.toString())
        lastAttemptByFingerprint = false
        typedHere = true
        mutableFingerprintNotice.value = null
        if (through != null) {
            through(secret)
        } else {
            databases.unlock(database, secret, askFingerprint)
            secret.wipe()
        }
        password.clearText()
    }

    override fun onCleared() {
        password.clearText()
    }

    private companion object {
        const val TAG = "Lock"
    }
}
