package dev.keething.vault.biometric

import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.domain.biometric.SeveralOpening
import dev.keething.domain.biometric.StoredPasswordOpening
import dev.keething.domain.secret.Secret
import dev.keething.domain.text.UserText
import dev.keething.vault.VaultText
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.databases.VaultSessions
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What one fingerprint for the window did: what is said ([outcome]), and each database's it looked at ([each]). */
public class WindowFingerprintResult(public val outcome: FingerprintOutcome, public val each: Map<DatabaseId, FingerprintOutcome>)

/**
 * One fingerprint for the window's databases (plan AD13; keething-spec unlock.biometric-several, databases.several-open
 * AC7; the Mac's `TouchIDUnlock.unlockWithTouchID()`), as AD5 chose: one prompt opens the keyring, the keyring each
 * database's stored password.
 *
 * - It opens every database of the window that can: locked, its fingerprint on, its file and key file there, and its
 *   password stored — or one that opens with its key file alone (nothing stored: the fingerprint is the check); a
 *   reminder due holds none back (keething-spec unlock.reminder-not-a-lock AC1). Each with its own stored password,
 *   never another's; the others are not read and say why.
 * - The stored passwords are read one after another under the one prompt, and what opened them is let go before any
 *   key is derived; then the databases open side by side, each judged by its own attempt as soon as it ends (a rejected
 *   stored password deleted then, that database's only; [BiometricUnlock]'s rules).
 * - A lock after the prompt (the app's, [VaultSessions.lockCount], or the database's own) opens none of them, also while
 *   the stored passwords are read; a database locked or closed alone meanwhile is not opened and gets no verdict.
 * - What is said: unlocked when any opened; else the prompt's own answer (cancelled, failed, the fingerprints changed);
 *   else the current database's outcome; else the first's.
 * - One fingerprint at a time for the app, this or a database's own lock screen's ([BiometricSetup.asking]).
 *
 * Call it on the main thread.
 */
public class WindowFingerprint(
    private val sessions: VaultSessions,
    private val setup: BiometricSetup,
    private val list: DatabaseList,
    /** Whether a file of a record is there ([dev.keething.vault.databases.FilesThere]): no prompt for one that is not. */
    private val fileExists: suspend (FileLocation) -> Boolean,
) {
    private val mutableRunning = MutableStateFlow(false)

    /** It runs: from before the prompt until what it opened has opened. */
    public val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    /**
     * Whether a fingerprint would open one of the window's locked databases now, of [among] only when given: asked, never
     * read (keething-spec databases.chooser AC1: the prompt shows only then; AC5: the run's checked ones).
     */
    public suspend fun canUnlockAny(among: Collection<DatabaseId>? = null): Boolean {
        if (!setup.state.value.enabled) return false
        val candidates = locked(among).filter { whyNot(it) == null }
        if (candidates.isEmpty() || setup.availability() != BiometricAvailability.Available) return false
        return candidates.any { it.isPasswordless || setup.hasPassword(it.id) }
    }

    /**
     * Asks for the fingerprint once and opens every database of the window that can, of [among] only when given (the
     * chooser's run: the checked ones; the others are not looked at).
     */
    public suspend fun unlock(among: Collection<DatabaseId>? = null): WindowFingerprintResult {
        if (setup.asking) return WindowFingerprintResult(FingerprintOutcome.InProgress, emptyMap())
        if (!setup.state.value.enabled) return WindowFingerprintResult(FingerprintOutcome.NotAvailable(UserText.of(VaultText.FINGERPRINT_OFF_IN_SETTINGS)), emptyMap())
        if (list.window.isEmpty()) return WindowFingerprintResult(FingerprintOutcome.NotAvailable(UserText.of(VaultText.FINGERPRINT_NO_DATABASE)), emptyMap())
        val looked = locked(among)
        // Nothing left to open.
        if (looked.isEmpty()) return WindowFingerprintResult(FingerprintOutcome.Unlocked, emptyMap())
        val each = linkedMapOf<DatabaseId, FingerprintOutcome>()
        setup.asking = true
        mutableRunning.value = true
        val answer = try {
            unlock(looked, each)
        } finally {
            setup.asking = false
            mutableRunning.value = false
        }
        val outcome = when {
            each.values.any { it == FingerprintOutcome.Unlocked } -> FingerprintOutcome.Unlocked
            answer != null -> answer
            else -> list.currentId?.let(each::get) ?: looked.firstNotNullOfOrNull { each[it.id] } ?: FingerprintOutcome.Cancelled
        }
        return WindowFingerprintResult(outcome, each)
    }

    /** Each database's outcome into [each]; the prompt's own answer when it let nothing through, else null. */
    private suspend fun unlock(looked: List<DatabaseRecord>, each: MutableMap<DatabaseId, FingerprintOutcome>): FingerprintOutcome? {
        // Who can: its fingerprint on, its files there; then a sensor; then a stored password.
        val candidates = looked.filter { record -> whyNot(record)?.also { each[record.id] = it } == null }
        if (candidates.isEmpty()) return null
        val availability = setup.availability()
        if (availability is BiometricAvailability.Unavailable) {
            candidates.forEach { each[it.id] = FingerprintOutcome.NotAvailable(UserText.plain(availability.reason)) }
            return null
        }
        val asked = candidates.filter { record ->
            (record.isPasswordless || setup.hasPassword(record.id)).also { if (!it) each[record.id] = FingerprintOutcome.NothingStored }
        }
        if (asked.isEmpty()) return null

        // A lock from here on opens nothing (of it): the app's, or the database's own; one closed meanwhile is not opened.
        val appLocked = sessions.lockCount
        val began = asked.mapNotNull { record -> sessions.session(record.id)?.let { record.id to Began(it, it.session.locks) } }.toMap()
        val unchanged = { id: DatabaseId -> sessions.lockCount == appLocked && began[id]?.isUnchanged(sessions.session(id)) == true }

        val passwords = mutableMapOf<DatabaseId, Secret>()
        try {
            val stored = asked.filterNot { it.isPasswordless }
            if (stored.isEmpty()) {
                // Only databases that open with their key file alone: the fingerprint is the check, nothing is opened.
                when (val confirmed = setup.confirm {}) {
                    is StoredPasswordOpening.Opened -> confirmed.password.wipe()
                    is StoredPasswordOpening.Failed -> return FingerprintOutcome.AuthenticationFailed(confirmed.reason).also { all(asked, each, it) }
                    else -> return FingerprintOutcome.Cancelled.also { all(asked, each, it) }
                }
            } else {
                val opened = try {
                    setup.openSeveral(stored.map { it.id }, onRecognised = {}, stillWanted = unchanged)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    return FingerprintOutcome.StorageError(failure.message ?: failure.javaClass.simpleName).also { all(asked, each, it) }
                }
                when (opened) {
                    SeveralOpening.Cancelled -> return FingerprintOutcome.Cancelled.also { all(asked, each, it) }
                    is SeveralOpening.Failed -> return FingerprintOutcome.AuthenticationFailed(opened.reason).also { all(asked, each, it) }
                    SeveralOpening.KeyInvalidated -> {
                        // Every stored password depended on the fingerprints that were enrolled: nothing opens.
                        val had = setup.state.value.stored + stored.map { it.id }
                        setup.deleteAll()
                        setup.setUpAgain(had, SetUpAgain.FINGERPRINTS_CHANGED)
                        return FingerprintOutcome.KeyInvalidated.also { all(asked, each, it) }
                    }
                    is SeveralOpening.Opened -> for (record in stored) {
                        val name = record.id.fileName
                        opened.unreadable[name]?.let { each[record.id] = FingerprintOutcome.StorageError(it) }
                            ?: read(record.id, opened.each[name], passwords)?.let { each[record.id] = it }
                    }
                }
            }
            // Side by side, each judged as soon as its own attempt ends. A password stays in [passwords] until its own
            // attempt takes it: one whose attempt never starts is wiped below.
            val opening = asked.filter { it.isPasswordless || it.id in passwords }
            each += coroutineScope {
                opening.map { record ->
                    async { record.id to open(record, passwords.remove(record.id) ?: Secret.empty(), unchanged) }
                }.awaitAll()
            }
        } finally {
            passwords.values.forEach(Secret::wipe)
        }
        return null
    }

    /** What [id]'s stored password read under the prompt says; null when it opened (then in [passwords]). */
    private suspend fun read(id: DatabaseId, opened: StoredPasswordOpening?, passwords: MutableMap<DatabaseId, Secret>): FingerprintOutcome? =
        when (opened) {
            is StoredPasswordOpening.Opened -> {
                passwords[id] = opened.password
                null
            }
            // Not read: a lock, or it closed, before its turn.
            null, StoredPasswordOpening.Cancelled, is StoredPasswordOpening.Failed -> FingerprintOutcome.Cancelled
            StoredPasswordOpening.NothingStored -> FingerprintOutcome.NothingStored
            // Its own sealed copy no longer opens: it alone goes, said as the fingerprints changing.
            StoredPasswordOpening.CopyInvalidated, StoredPasswordOpening.KeyInvalidated -> {
                setup.delete(id)
                setup.setUpAgain(setOf(id), SetUpAgain.FINGERPRINTS_CHANGED)
                FingerprintOutcome.KeyInvalidated
            }
        }

    /** [password] opens [record]'s database unless a lock or its closing came first (wiped then). */
    private suspend fun open(record: DatabaseRecord, password: Secret, unchanged: (DatabaseId) -> Boolean): FingerprintOutcome {
        val database = sessions.session(record.id)
        if (database == null || !unchanged(record.id)) {
            password.wipe()
            return FingerprintOutcome.Cancelled
        }
        return database.biometric.openStored(password, record)
    }

    private fun all(asked: List<DatabaseRecord>, each: MutableMap<DatabaseId, FingerprintOutcome>, outcome: FingerprintOutcome) {
        asked.forEach { each[it.id] = outcome }
    }

    /** The window's databases that are locked, with a session that can open; of [among] only when given. */
    private fun locked(among: Collection<DatabaseId>? = null): List<DatabaseRecord> = list.window.filter { record ->
        if (among != null && record.id !in among) return@filter false
        val session = sessions.session(record.id)?.session
        session != null && !session.isClosed && session.state.value is SessionState.Locked
    }

    /** Why the fingerprint is not asked for [record]; null: it may be (with a sensor and its stored password). */
    private suspend fun whyNot(record: DatabaseRecord): FingerprintOutcome? {
        if (!record.useBiometric) return FingerprintOutcome.NotAvailable(UserText.of(VaultText.FINGERPRINT_OFF_FOR_DATABASE))
        // It could not open: asking for a finger only to fail is noise (keething-spec unlock.key-file AC3).
        if (!fileExists(record.file)) return FingerprintOutcome.UnlockFailed(OpenFailure.Missing)
        record.keyFile?.let { if (!fileExists(it)) return FingerprintOutcome.UnlockFailed(OpenFailure.KeyFileMissing(it.fileName)) }
        return null
    }

    /** A database's session and its lock count when the finger was asked for. */
    private class Began(private val database: DatabaseSession, private val locks: Int) {
        /** Still that session ([now]), not locked since, and locked still (not opened meanwhile). */
        fun isUnchanged(now: DatabaseSession?): Boolean =
            now === database && !database.session.isClosed && database.session.locks == locks && database.session.state.value is SessionState.Locked
    }
}
