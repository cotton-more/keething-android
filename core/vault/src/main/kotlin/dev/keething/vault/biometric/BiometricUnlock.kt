package dev.keething.vault.biometric

import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.domain.biometric.StoredPasswordOpening
import dev.keething.domain.secret.Secret
import dev.keething.domain.text.UserText
import dev.keething.vault.VaultText
import dev.keething.vault.creating.DerivedKey
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.session.KeyConfirmation
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.UnlockOutcome
import dev.keething.vault.session.VaultSession
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
    /** The database's own Use fingerprint (its page, the lock screen's question). */
    val databaseEnabled: Boolean,
    /** Its fingerprint was chosen, or not: the lock screen does not ask it again (keething-spec unlock.biometric AC4). */
    val asked: Boolean = false,
    /** It opens with its key file alone: the fingerprint opens it with nothing stored, and the reminder asks about its key file. */
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
    /** Ask about the master key after this many days without the password typed; null: never (SPEC §5). The app's choice. */
    val reminderDays: Int? = DEFAULT_REMINDER_DAYS,
    /**
     * [reminderDays] had passed when it opened, and the question was not put off within a day: whether the master
     * password is still remembered (or the key file kept safe) is to be asked over its list now (keething-spec
     * unlock.reminder-not-a-lock AC1, AC4). Gone once answered, put off, or locked.
     */
    val reminder: Boolean = false,
    /** Why its stored password went, while nothing is stored again (keething-spec databases.not-open AC1). */
    val setUpAgain: SetUpAgain? = null,
    /**
     * A typed unlock opened it while it [canAsk]: whether to use the fingerprint is to be asked over its list now
     * (keething-spec unlock.biometric AC4), the password it opened with kept until then.
     */
    val question: Boolean = false,
    /** The [question]'s unlock had nothing typed: it opened with its key file alone, and there is no password to store. */
    val questionNothingTyped: Boolean = false,
) {
    /** The fingerprint opens this database: the app's switch and its own. */
    public val enabled: Boolean get() = appEnabled && databaseEnabled

    /** It is set up and works on this phone: stored, or nothing to store. */
    public val isSetUp: Boolean get() = enabled && availability == BiometricAvailability.Available && (stored || passwordless)

    /** The lock screen offers the fingerprint: the reminder never withholds it (keething-spec unlock.reminder-not-a-lock AC1). */
    public val canUnlock: Boolean get() = isSetUp

    /**
     * A database whose fingerprint was never chosen is asked once whether to use it, after a typed unlock that opened it
     * (keething-spec unlock.biometric AC4). The app's switch allows it, the phone has a strong biometric.
     */
    public val canAsk: Boolean get() = appEnabled && !databaseEnabled && !asked && availability == BiometricAvailability.Available

    public companion object {
        public const val DEFAULT_REMINDER_DAYS: Int = 14

        /** The choices in Settings; null is "Never". */
        public val REMINDER_CHOICES: List<Int?> = listOf(7, 14, 30, null)
    }
}

/** What came of the answer to [BiometricState.question]. */
public sealed interface FingerprintAnswer {
    /** Use fingerprint: on, and the password stored (nothing to store for one that opens with its key file alone). */
    public data object TurnedOn : FingerprintAnswer

    /** No, kept. */
    public data object Declined : FingerprintAnswer

    /** Put away, or no question stood any more (answered already, locked, its switch set meanwhile): nothing changed. */
    public data object Nothing : FingerprintAnswer

    /** Use fingerprint, and the password could not be stored, or one left could not be deleted first: [reason]. */
    public data class Failed(val reason: String) : FingerprintAnswer
}

/** What came of an unlock with the fingerprint. */
public sealed interface FingerprintOutcome {
    public data object Unlocked : FingerprintOutcome

    /** The user cancelled, a lock came first, or another database was chosen meanwhile: nothing to say. */
    public data object Cancelled : FingerprintOutcome

    /** Another unlock runs, or the vault is open: nothing was asked. */
    public data object InProgress : FingerprintOutcome

    public data class NotAvailable(val reason: UserText) : FingerprintOutcome

    public data object NothingStored : FingerprintOutcome

    /** The fingerprints changed and the key with them, or this database's stored copy no longer opens: it is deleted. */
    public data object KeyInvalidated : FingerprintOutcome

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
 * - A database whose fingerprint was never chosen is asked after a typed unlock that opened it, when the unlock asks
 *   for it (the app's lock screen and the chooser's run; not autofill's, nor a quick note's, which show no list to ask
 *   over) ([answer]): the typed password is kept in memory for the answer, and wiped when it comes, when the database locks or closes, or when the
 *   question no longer stands (its switch set on its page, the app's turned off).
 * - The stored password is opened only after a fingerprint, never to find out whether it is there.
 * - A stored password the database rejects deletes its own (kept with a key file, which may be the wrong part, and
 *   while a key file changed since the last unlock that worked); new fingerprints delete every database's; a copy that
 *   no longer opens deletes its own. A cancelled or failed fingerprint keeps it.
 * - After [BiometricState.reminderDays] without a typed unlock of this database the reminder is due: it opens as usual,
 *   the fingerprint included, and once open the question stands ([BiometricState.reminder]): [remembered], [check] or
 *   [putOff] (keething-spec unlock.reminder-not-a-lock). It is decided when it opens, so a typed unlock, which starts
 *   the days again, asks nothing. The stored copy stays.
 * - A database that opens with its key file alone opens after the fingerprint with nothing stored; its reminder asks
 *   about its key file, and only [remembered] starts its days again.
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
    private val fileExists: (FileLocation) -> Boolean = FileLocation::isReachable,
    private val appLocks: () -> Int = { 0 },
) {
    private var prompting = false
    private var opening = false

    /** The password a typed unlock opened it with, kept while [BiometricState.question] waits for its answer. */
    private var forQuestion: Secret? = null

    /** A typed password is opening it: its days start again, so the reminder does not ask. */
    private var typing = false

    /** [BiometricState.reminder]; [wasOpen]: it was open when last looked at, so an opening is told from a change. */
    private var reminder = false
    private var wasOpen = session.state.value is SessionState.Unlocked

    private val mutableState = MutableStateFlow(current())
    public val state: StateFlow<BiometricState> = mutableState.asStateFlow()

    init {
        // The switches changed in Settings, the record (its own switch, a key file, opening without a password), the lock.
        states.launch {
            try {
                combine(setup.state, list.state, session.state) { _, _, _ -> }.collect {
                    if (forQuestion != null && (session.state.value !is SessionState.Unlocked || !current().canAsk)) dropQuestion()
                    followReminder()
                    publish()
                }
            } finally {
                dropQuestion()
            }
        }
    }

    /** What [state] says once it has caught up with the switches and the list; for a decision made at once. */
    public fun current(): BiometricState {
        val setupState = setup.state.value
        val record = list.record(id)
        return BiometricState(
            appEnabled = setupState.enabled,
            databaseEnabled = record?.useBiometric == true,
            asked = record?.fingerprintAsked == true,
            passwordless = record?.isPasswordless == true,
            availability = setupState.availability,
            stored = id in setupState.stored,
            setupFailure = setupState.setupFailures[id],
            prompting = prompting,
            opening = opening,
            reminderDays = setupState.reminderDays,
            reminder = reminder,
            setUpAgain = setupState.setUpAgain[id],
            question = forQuestion != null,
            questionNothingTyped = forQuestion?.isEmpty() == true,
        )
    }

    /** Asks the phone again whether it can, the store whether a password is there, and the clock whether it is due. */
    public suspend fun refresh(): BiometricState {
        setup.refresh()
        publish()
        return state.value
    }

    /**
     * This database's own switch ([BiometricSetup.setUseBiometric]), offered while the app's is on, so the password typed
     * next is stored. Returns why a stored password could not be deleted, or null.
     */
    public suspend fun useNextTime(on: Boolean): String? = setup.setUseBiometric(id, on).also { publish() }

    /**
     * The answer to [BiometricState.question] (keething-spec unlock.biometric AC4). Use fingerprint turns its own switch on
     * and stores the password it was just opened with (nothing for one opened with its key file alone); No is kept, and
     * it is not asked again; null (put away) answers nothing, and the next typed unlock asks again. The kept password is
     * wiped either way, and the question is gone at once (a second answer finds none).
     */
    public suspend fun answer(use: Boolean?): FingerprintAnswer {
        val password = forQuestion ?: return FingerprintAnswer.Nothing
        forQuestion = null
        publish()
        try {
            if (use == null || !current().canAsk) return FingerprintAnswer.Nothing
            if (!use) {
                setup.decline(id)
                return FingerprintAnswer.Declined
            }
            setup.setUseBiometric(id, true)?.let { return FingerprintAnswer.Failed(it) }
            val failure = if (password.isEmpty()) null else storeTyped(password)
            return failure?.let(FingerprintAnswer::Failed) ?: FingerprintAnswer.TurnedOn
        } finally {
            password.wipe()
            publish()
        }
    }

    private fun dropQuestion() {
        forQuestion?.wipe()
        forQuestion = null
    }

    /**
     * The reminder's question is decided as it opens, however it opened, and goes when it locks, or when the reminder no
     * longer counts for it (Never chosen, a switch off).
     */
    private fun followReminder() {
        val open = session.state.value is SessionState.Unlocked
        val record = list.record(id)
        reminder = when {
            !open || record == null -> false
            !wasOpen -> !typing && setup.asksNow(record)
            else -> reminder && setup.isDue(record)
        }
        wasOpen = open
    }

    /**
     * "Yes" to the reminder (keething-spec unlock.reminder-not-a-lock AC2, AC7): believed, the days start again. Nothing
     * when no question stands.
     */
    public fun remembered() {
        if (!reminder) return
        restartDays()
        publish()
    }

    /**
     * "Check it" (keething-spec unlock.reminder-not-a-lock AC2, AC3): [typed] with its key file is compared with the key
     * it is open with, the key derivation run once. Confirmed starts the days again; Wrong changes nothing and grants
     * nothing (it is open already). The caller wipes [typed] once this returns.
     */
    public suspend fun check(typed: Secret): KeyConfirmation {
        val result = session.confirmKey(typed)
        if (result is KeyConfirmation.Confirmed && reminder) {
            restartDays()
            publish()
        }
        return result
    }

    /**
     * The question left without an answer (put away, Not now) or after three tries that did not match: asked again at the
     * first opening a day later at the earliest (keething-spec unlock.reminder-not-a-lock AC4).
     */
    public fun putOff() {
        if (!reminder) return
        setup.putOff(id)
        reminder = false
        publish()
    }

    /** A typed password opened it, or the reminder was answered: the days start again, and no question stands. */
    private fun restartDays() {
        setup.typedNow(id)
        reminder = false
    }

    /**
     * The typed password: unlocks, and stores the password when the fingerprint is on for this database and this unlock
     * opened the vault. Nothing typed (a database with a key file) stores nothing: there is no password. [ask]: a
     * database whose fingerprint was never chosen is asked whether to use it once this opened it ([BiometricState.question]).
     * The caller may wipe its own copy as soon as this returns. Null when the vault was not locked.
     */
    public fun unlock(password: Secret, ask: Boolean = false): Deferred<UnlockOutcome>? {
        val outcome = session.unlock(password) ?: return null
        typing = !password.isEmpty()
        afterTyped(outcome, password, ask)
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
        typing = !password.isEmpty()
        afterTyped(outcome, password)
        return outcome
    }

    private fun afterTyped(outcome: Deferred<UnlockOutcome>, password: Secret, ask: Boolean = false) {
        var kept: Secret? = password.copy()
        scope.launch {
            try {
                if (outcome.await() == UnlockOutcome.Opened) {
                    setup.opened(id)
                    // Nothing typed (its key file alone): only a Yes about its key file starts its days (AC7).
                    if (!password.isEmpty()) restartDays()
                    val now = current()
                    when {
                        now.enabled -> kept?.takeUnless(Secret::isEmpty)?.let { storeTyped(it) }
                        // Still open: a lock since leaves nothing to ask over its list.
                        ask && now.canAsk && session.state.value is SessionState.Unlocked -> {
                            dropQuestion()
                            forQuestion = kept
                            kept = null
                        }
                    }
                    publish()
                }
            } finally {
                typing = false
                kept?.wipe()
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
        if (!setup.state.value.enabled) return FingerprintOutcome.NotAvailable(UserText.of(VaultText.FINGERPRINT_OFF_IN_SETTINGS))
        if (!record.useBiometric) return FingerprintOutcome.NotAvailable(UserText.of(VaultText.FINGERPRINT_OFF_FOR_DATABASE))
        // It could not open: asking for a finger only to fail is noise (keething-spec unlock.key-file AC3).
        if (!fileExists(record.file)) return FingerprintOutcome.UnlockFailed(OpenFailure.Missing)
        record.keyFile?.takeUnless(fileExists)?.let { return FingerprintOutcome.UnlockFailed(OpenFailure.KeyFileMissing(it.fileName)) }
        prompting = true
        setup.asking = true
        publish()
        // A lock from here on (the button, the timeout, sleep) opens nothing (keething-spec unlock.biometric-invalidation AC6):
        // its own, or the app's, which leaves a database locked already as it was (found by AD8's review).
        val locks = session.locks
        val appLocked = appLocks()
        try {
            val availability = setup.availability()
            if (availability is BiometricAvailability.Unavailable) return FingerprintOutcome.NotAvailable(UserText.plain(availability.reason))
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
                    if (ended.failure.isWrongKey && list.record(id)?.keyFile == record.keyFile) {
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

    /**
     * Its master key was changed on this phone (keething-spec databases.change-master-key AC6), as a typed unlock of
     * [password]: its days start, and while the fingerprint is on for it the new password is stored in place of the old;
     * with its key file alone (null) a stored password goes, as there is none, and its days go on (only a Yes about its
     * key file starts them). Why that failed, or null. The caller may wipe [password] once this returns.
     */
    internal suspend fun keyChanged(password: Secret?): String? {
        setup.opened(id)
        if (password != null) restartDays()
        val failure = when {
            !current().enabled -> null
            password == null -> setup.delete(id)
            else -> storeTyped(password)
        }
        publish()
        return failure
    }

    /** Stores [password] while the fingerprint is on for it; why that failed, or null. */
    private suspend fun storeTyped(password: Secret): String? {
        val availability = setup.availability()
        if (availability is BiometricAvailability.Unavailable) return availability.reason
        // Once more after the wait: turned off or removed meanwhile, nothing is stored (its deletion went first).
        if (!current().enabled) return null
        return setup.save(id, password).also { publish() }
    }

    private fun nothingStored(): FingerprintOutcome {
        publish()
        return FingerprintOutcome.NothingStored
    }

    private fun publish() {
        mutableState.value = current()
    }
}
