package dev.keething.vault.biometric

import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.domain.biometric.StoredPasswordOpening
import dev.keething.domain.secret.Secret
import dev.keething.vault.creating.DerivedKey
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.UnlockOutcome
import dev.keething.vault.session.VaultSession
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Why a database's stored password went: its line says "Fingerprint to set up again" and why (the Mac's outcomes). */
public enum class SetUpAgain { FINGERPRINTS_CHANGED, PASSWORD_REJECTED }

/** What the fingerprint unlock can do now for one database: its lock screen's button and switch, its page in Settings. */
public data class BiometricState(
    /** The app's switch (Settings); off until it is turned on (SPEC §5). */
    val appEnabled: Boolean,
    /** The database's own Use fingerprint (its page, "Use fingerprint next time"). */
    val databaseEnabled: Boolean,
    /** It opens with its key file alone: the fingerprint opens it with nothing stored, and it is never due. */
    val passwordless: Boolean = false,
    /** Null until the first [BiometricUnlock.refresh]. */
    val availability: BiometricAvailability? = null,
    val stored: Boolean = false,
    /** Why the last typed unlock could not store the password; null once one did. */
    val setupFailure: String? = null,
    /** A fingerprint unlock runs: the prompt is up, or the password it opened is opening the vault. */
    val prompting: Boolean = false,
    /**
     * The fingerprint was accepted: the stored password is being opened, then the vault. The lock screen says
     * "Opening…" from here, not only once the vault's own unlock starts.
     */
    val opening: Boolean = false,
    /** Ask for the typed password after this many days without one; null: never (SPEC §5). The app's choice. */
    val reminderDays: Int? = DEFAULT_REMINDER_DAYS,
    /** [reminderDays] have passed since this database's last typed unlock: the fingerprint waits until it is typed. */
    val passwordDue: Boolean = false,
    /** Why its stored password went, while nothing is stored again (keething-spec databases.not-open AC1). */
    val setUpAgain: SetUpAgain? = null,
) {
    /** The fingerprint opens this database: the app's switch and its own. */
    public val enabled: Boolean get() = appEnabled && databaseEnabled

    /** It is set up and works on this phone, whether or not the password is due now: stored, or nothing to store. */
    public val isSetUp: Boolean get() = enabled && availability == BiometricAvailability.Available && (stored || passwordless)

    /** The lock screen offers the fingerprint. */
    public val canUnlock: Boolean get() = isSetUp && !passwordDue

    public companion object {
        public const val DEFAULT_REMINDER_DAYS: Int = 14

        /** The choices in Settings; null is "Never". */
        public val REMINDER_CHOICES: List<Int?> = listOf(7, 14, 30, null)
    }
}

/** What came of an unlock with the fingerprint. */
public sealed interface FingerprintOutcome {
    public data object Unlocked : FingerprintOutcome

    /** The user cancelled, a lock came first, or another database was chosen meanwhile: nothing to say. */
    public data object Cancelled : FingerprintOutcome

    /** Another unlock runs, or the vault is open: nothing was asked. */
    public data object InProgress : FingerprintOutcome

    public data class NotAvailable(val reason: String) : FingerprintOutcome

    public data object NothingStored : FingerprintOutcome

    /** The fingerprints changed and the key with them, or this database's stored copy no longer opens: it is deleted. */
    public data object KeyInvalidated : FingerprintOutcome

    /** [BiometricState.reminderDays] have passed: the password is to be typed this time. */
    public data object PasswordDue : FingerprintOutcome

    /** The database no longer opens with the stored password (changed elsewhere): it is deleted. */
    public data object StoredPasswordRejected : FingerprintOutcome

    public data class AuthenticationFailed(val reason: String) : FingerprintOutcome

    /**
     * The stored password (or the key file alone) was tried and the file did not open for another reason, or the file or
     * its key file is not there and nothing was asked; the stored password is kept.
     */
    public data class UnlockFailed(val failure: OpenFailure) : FingerprintOutcome

    public data class StorageError(val reason: String) : FingerprintOutcome
}

/**
 * One database's fingerprint unlock (SPEC §5; Mac `TouchIDUnlock`, Mac SPEC §3.8). Its master password is kept by
 * [setup]'s stores under its [id] and handed to the normal [VaultSession.unlock] after a fingerprint, so the database
 * is opened exactly as if the password had been typed; it is only ever opened with its own.
 *
 * - The fingerprint opens it while the app's switch and its own Use fingerprint are on.
 * - The password is stored after a typed unlock that opened the vault, so a database password changed elsewhere heals
 *   the next time it is typed. Nothing typed stores nothing.
 * - The stored password is opened only after a fingerprint, never to find out whether it is there.
 * - A stored password the database rejects deletes its own (kept with a key file, which may be the wrong part, and
 *   while a key file changed since the last unlock that worked); new fingerprints delete every database's; a copy that
 *   no longer opens deletes its own. A cancelled or failed fingerprint keeps it.
 * - After [BiometricState.reminderDays] without a typed unlock of this database its password is due: the fingerprint
 *   is not offered until it is typed again. The stored copy stays.
 * - A database that opens with its key file alone opens after the fingerprint with nothing stored, and is never due.
 * - No fingerprint is asked while its file or its key file is not there.
 *
 * Call it from the main thread. [scope] is the app's (storing a typed password outlives the screen); [states] ends with
 * the session (it follows the switches and the list for [state]); [appLocks] is the app's lock count
 * ([dev.keething.vault.databases.VaultSessions.lockCount]): a Lock that found this database locked already does not change
 * its own.
 */
public class BiometricUnlock(
    private val id: DatabaseId,
    private val session: VaultSession,
    private val setup: BiometricSetup,
    private val list: DatabaseList,
    private val scope: CoroutineScope,
    states: CoroutineScope,
    private val fileExists: (String) -> Boolean = { File(it).isFile },
    private val appLocks: () -> Int = { 0 },
) {
    private var prompting = false
    private var opening = false

    private val mutableState = MutableStateFlow(current())
    public val state: StateFlow<BiometricState> = mutableState.asStateFlow()

    init {
        // The switches changed in Settings, the record (its own switch, a key file, opening without a password).
        states.launch { combine(setup.state, list.state) { _, _ -> }.collect { publish() } }
    }

    /** What [state] says once it has caught up with the switches and the list; for a decision made at once. */
    public fun current(): BiometricState {
        val setupState = setup.state.value
        val record = list.record(id)
        return BiometricState(
            appEnabled = setupState.enabled,
            databaseEnabled = record?.useBiometric == true,
            passwordless = record?.isPasswordless == true,
            availability = setupState.availability,
            stored = id in setupState.stored,
            setupFailure = setupState.setupFailures[id],
            prompting = prompting,
            opening = opening,
            reminderDays = setupState.reminderDays,
            passwordDue = record != null && setup.isDue(record),
            setUpAgain = setupState.setUpAgain[id],
        )
    }

    /** Asks the phone again whether it can, the store whether a password is there, and the clock whether it is due. */
    public suspend fun refresh(): BiometricState {
        setup.refresh()
        publish()
        return state.value
    }

    /**
     * The lock screen's "Use fingerprint next time": this database's own switch ([BiometricSetup.setUseBiometric]),
     * offered while the app's is on. Returns why a stored password could not be deleted, or null.
     */
    public suspend fun useNextTime(on: Boolean): String? = setup.setUseBiometric(id, on).also { publish() }

    /**
     * The typed password: unlocks, and stores the password when the fingerprint is on for this database and this unlock
     * opened the vault. Nothing typed (a database with a key file) stores nothing: there is no password. The caller may
     * wipe its own copy as soon as this returns. Null when the vault was not locked.
     */
    public fun unlock(password: Secret): Deferred<UnlockOutcome>? {
        val outcome = session.unlock(password) ?: return null
        afterTyped(outcome, password)
        return outcome
    }

    /**
     * A database just made (keething-spec databases.new-database AC8): opened with [key], the very key it was made with
     * (nothing derived again; the session takes it over), as a typed unlock of [password]: its days start, and the
     * password is stored when the fingerprint is on for it. The caller may wipe [password] as soon as this returns.
     * Null when the vault was not locked ([key] is wiped then).
     */
    public fun unlockCreated(key: DerivedKey, password: Secret): Deferred<UnlockOutcome>? {
        val outcome = session.unlockAdopting(key.key) ?: return null
        afterTyped(outcome, password)
        return outcome
    }

    private fun afterTyped(outcome: Deferred<UnlockOutcome>, password: Secret) {
        val kept = password.copy()
        scope.launch {
            try {
                if (outcome.await() == UnlockOutcome.Opened) {
                    setup.opened(id)
                    setup.typedNow(id)
                    publish()
                    if (current().enabled && !kept.isEmpty()) storeTyped(kept)
                }
            } finally {
                kept.wipe()
            }
        }
    }

    /**
     * Asks for the fingerprint and unlocks with this database's stored password (or its key file alone), this database
     * alone (keething-spec databases.several-open AC8). One at a time for the app.
     */
    public suspend fun unlockWithFingerprint(): FingerprintOutcome {
        if (session.isClosed) return FingerprintOutcome.Cancelled
        if (prompting || setup.asking || session.state.value !is SessionState.Locked) return FingerprintOutcome.InProgress
        val record = list.record(id) ?: return FingerprintOutcome.Cancelled
        if (!setup.state.value.enabled) return FingerprintOutcome.NotAvailable("it is turned off in Settings")
        if (!record.useBiometric) return FingerprintOutcome.NotAvailable("it is turned off for this database")
        // It could not open: asking for a finger only to fail is noise (keething-spec unlock.key-file AC3).
        if (!fileExists(record.path)) return FingerprintOutcome.UnlockFailed(OpenFailure.Missing)
        record.keyFilePath?.takeUnless(fileExists)?.let { return FingerprintOutcome.UnlockFailed(OpenFailure.KeyFileMissing(File(it).name)) }
        if (setup.isDue(record)) {
            publish()
            return FingerprintOutcome.PasswordDue
        }
        prompting = true
        setup.asking = true
        publish()
        // A lock from here on (the button, the timeout, sleep) opens nothing (keething-spec unlock.biometric-invalidation AC6):
        // its own, or the app's, which leaves a database locked already as it was (found by AD8's review).
        val locks = session.locks
        val appLocked = appLocks()
        try {
            val availability = setup.availability()
            if (availability is BiometricAvailability.Unavailable) return FingerprintOutcome.NotAvailable(availability.reason)
            if (!record.isPasswordless && !setup.hasPassword(id)) return nothingStored()
            val recognised = {
                opening = true
                publish()
            }
            val opened = try {
                if (record.isPasswordless) setup.confirm(recognised) else setup.open(id, recognised)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return FingerprintOutcome.StorageError(failure.message ?: failure.javaClass.simpleName)
            }
            // Locked while the stored password was read (StrongBox takes a moment): nothing opens, nothing is deleted.
            if (session.locks != locks || appLocks() != appLocked) {
                (opened as? StoredPasswordOpening.Opened)?.password?.wipe()
                return FingerprintOutcome.Cancelled
            }
            return when (opened) {
                is StoredPasswordOpening.Opened -> openStored(opened.password, record)
                StoredPasswordOpening.Cancelled -> FingerprintOutcome.Cancelled
                is StoredPasswordOpening.Failed -> FingerprintOutcome.AuthenticationFailed(opened.reason)
                StoredPasswordOpening.KeyInvalidated -> {
                    // Every stored password depended on the fingerprints that were enrolled.
                    val had = setup.state.value.stored + id
                    setup.deleteAll()
                    setup.setUpAgain(had, SetUpAgain.FINGERPRINTS_CHANGED)
                    FingerprintOutcome.KeyInvalidated
                }
                StoredPasswordOpening.CopyInvalidated -> {
                    setup.delete(id)
                    setup.setUpAgain(setOf(id), SetUpAgain.FINGERPRINTS_CHANGED)
                    FingerprintOutcome.KeyInvalidated
                }
                StoredPasswordOpening.NothingStored -> nothingStored()
            }
        } finally {
            prompting = false
            opening = false
            setup.asking = false
            publish()
        }
    }

    /**
     * [password] (empty: the key file alone), opened after a fingerprint, opens the vault, and the outcome is judged for
     * this database alone as soon as its attempt ends (keething-spec unlock.biometric-several AC2): a lock meanwhile is
     * no verdict. The password is wiped.
     */
    internal suspend fun openStored(password: Secret, record: DatabaseRecord): FingerprintOutcome {
        val outcome = try {
            session.unlock(password)
        } finally {
            password.wipe()
        } ?: return if (session.isClosed) FingerprintOutcome.Cancelled else FingerprintOutcome.InProgress
        return when (val ended = outcome.await()) {
            UnlockOutcome.Opened -> FingerprintOutcome.Unlocked.also { setup.opened(id) }
            UnlockOutcome.Interrupted -> FingerprintOutcome.Cancelled
            is UnlockOutcome.Failed -> when {
                record.isPasswordless -> {
                    // Nothing is stored: a key file it no longer opens with alone brings the field back (a password was added).
                    if (ended.failure.isWrongKey && list.record(id)?.keyFilePath == record.keyFilePath) {
                        list.update(id) { it.copy(opensWithoutPassword = false) }
                    }
                    FingerprintOutcome.UnlockFailed(ended.failure)
                }
                // With a key file the database cannot say which part it rejected (WrongPasswordOrKeyFile): kept.
                ended.failure == OpenFailure.WrongPassword && !setup.keyFileChangedSinceOpened(id) -> {
                    setup.delete(id)
                    setup.setUpAgain(setOf(id), SetUpAgain.PASSWORD_REJECTED)
                    FingerprintOutcome.StoredPasswordRejected
                }
                else -> FingerprintOutcome.UnlockFailed(ended.failure)
            }
        }
    }

    private suspend fun storeTyped(password: Secret) {
        if (setup.availability() != BiometricAvailability.Available) return
        // Once more after the wait: turned off or removed meanwhile, nothing is stored (its deletion went first).
        if (!current().enabled) return
        setup.save(id, password)
        publish()
    }

    private fun nothingStored(): FingerprintOutcome {
        publish()
        return FingerprintOutcome.NothingStored
    }

    private fun publish() {
        mutableState.value = current()
    }
}
