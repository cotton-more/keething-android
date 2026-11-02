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

    /**
     * Counts the times the password is wanted: the fingerprint prompt closed without a fingerprint, or the fingerprint just
     * turned on to be set up by typing. The password field takes the focus.
     */
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

    private val mutableAsking = MutableStateFlow(false)

    /** The fingerprint's button asks whether to use it (keething-spec unlock.biometric AC4). */
    val asking: StateFlow<Boolean> = mutableAsking.asStateFlow()

    /**
     * The fingerprint's button beside the field: asks for the fingerprint when it can open the database, else asks once
     * whether to use it for a database whose fingerprint was never chosen.
     */
    fun fingerprintTapped() {
        if (answering) return
        val state = database.biometric.current()
        when {
            state.canUnlock -> viewModelScope.launch { fingerprint() }
            state.canAsk -> mutableAsking.value = true
        }
    }

    /** A Use fingerprint is being applied: its switch is not on yet, and the question is not put again meanwhile. */
    private var answering = false

    /**
     * The question's answer: Use fingerprint turns this database's own switch on, so the password typed next is stored
     * (one that opens with its key file alone has nothing to store: the fingerprint is asked at once); No is kept, and it
     * is not asked again. Null: the question was put away unanswered, and is asked at the next tap.
     */
    fun answer(use: Boolean?) {
        mutableAsking.value = false
        // Asked while the app's switch was on and this database's never chosen: an answer that comes after either changed
        // (the switch turned off meanwhile, a second answer) changes nothing.
        if (use == null || answering || !database.biometric.current().canAsk) return
        if (!use) {
            database.biometric.decline()
            return
        }
        answering = true
        viewModelScope.launch {
            try {
                database.biometric.useNextTime(true)?.let {
                    mutableFingerprintNotice.value = LockNotice("The stored password could not be deleted: $it.", isProblem = true)
                    return@launch
                }
            } finally {
                answering = false
            }
            if (database.biometric.current().canUnlock) fingerprint() else mutablePasswordWanted.value++
        }
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
     * run takes it (plan AD13), and wipes it after its try.
     */
    fun unlock(through: ((Secret) -> Unit)? = null) {
        if (password.text.isEmpty() && list.record(database.id)?.keyFile == null) return
        val secret = Secret.of(password.text.toString())
        lastAttemptByFingerprint = false
        typedHere = true
        mutableFingerprintNotice.value = null
        if (through != null) {
            through(secret)
        } else {
            databases.unlock(database, secret)
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
