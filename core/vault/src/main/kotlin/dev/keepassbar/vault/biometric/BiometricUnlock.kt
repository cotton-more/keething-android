package dev.keepassbar.vault.biometric

import dev.keepassbar.domain.biometric.BiometricAvailability
import dev.keepassbar.domain.biometric.BiometricPasswordStore
import dev.keepassbar.domain.biometric.StoredPasswordOpening
import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.settings.SettingsStore
import dev.keepassbar.domain.time.Clock
import dev.keepassbar.vault.opening.OpenFailure
import dev.keepassbar.vault.session.SessionState
import dev.keepassbar.vault.session.UnlockOutcome
import dev.keepassbar.vault.session.VaultSession
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the fingerprint unlock can do now: the lock screen's button, the switch in Settings. */
public data class BiometricState(
    /** The switch; off until it is turned on (SPEC §5). */
    val enabled: Boolean,
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
    /** Ask for the typed password after this many days without one; null: never (SPEC §5). */
    val reminderDays: Int? = DEFAULT_REMINDER_DAYS,
    /** [reminderDays] have passed since the last typed unlock: the fingerprint waits until the password is typed. */
    val passwordDue: Boolean = false,
) {
    /** It is set up and works on this phone, whether or not the password is due now. */
    public val isSetUp: Boolean get() = enabled && availability == BiometricAvailability.Available && stored

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

    /** The user cancelled, or a lock came first: nothing to say. */
    public data object Cancelled : FingerprintOutcome

    /** Another unlock runs, or the vault is open: nothing was asked. */
    public data object InProgress : FingerprintOutcome

    public data class NotAvailable(val reason: String) : FingerprintOutcome

    public data object NothingStored : FingerprintOutcome

    /** The fingerprints changed and the key with them: the stored password is deleted. */
    public data object KeyInvalidated : FingerprintOutcome

    /** [BiometricState.reminderDays] have passed: the password is to be typed this time. */
    public data object PasswordDue : FingerprintOutcome

    /** The database no longer opens with the stored password (changed elsewhere): it is deleted. */
    public data object StoredPasswordRejected : FingerprintOutcome

    public data class AuthenticationFailed(val reason: String) : FingerprintOutcome

    /** The stored password was tried and the file did not open for another reason; the password is kept. */
    public data class UnlockFailed(val failure: OpenFailure) : FingerprintOutcome

    public data class StorageError(val reason: String) : FingerprintOutcome
}

/**
 * The fingerprint unlock (SPEC §5; Mac `TouchIDUnlock`, Mac SPEC §3.8). The master password is kept by a
 * [BiometricPasswordStore] and handed to the normal [VaultSession.unlock] after a fingerprint, so the
 * database is opened exactly as if the password had been typed.
 *
 * - The password is stored after a typed unlock that opened the vault while the switch is on, so a
 *   database password changed elsewhere heals the next time it is typed.
 * - The stored password is opened only after a fingerprint, never to find out whether it is there.
 * - New fingerprints (the key is invalidated) or a stored password the database rejects delete it; a
 *   cancelled or failed fingerprint keeps it.
 * - After [BiometricState.reminderDays] without a typed unlock the password is due: the fingerprint is not
 *   offered until it is typed again, so it is not forgotten. The stored copy stays.
 *
 * Call it from [scope]'s thread (the main thread); the store's file and keystore work runs on [io], which
 * runs one task at a time in the app, so saves and deletes happen in the order they were asked for.
 */
public class BiometricUnlock(
    private val session: VaultSession,
    private val store: BiometricPasswordStore,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(
        BiometricState(enabled = settings.string(ENABLED) == ON, reminderDays = reminderDays()).let { it.copy(passwordDue = isDue(it.reminderDays)) },
    )
    public val state: StateFlow<BiometricState> = mutableState.asStateFlow()

    /** Asks the phone again whether it can, the store whether a password is there, and the clock whether the password is due. */
    public suspend fun refresh() {
        val availability = withContext(io) { store.availability() }
        val stored = withContext(io) { runCatching { store.hasPassword() }.getOrDefault(false) }
        mutableState.update { it.copy(availability = availability, stored = stored, passwordDue = isDue(it.reminderDays)) }
    }

    /**
     * Turning it on only sets the switch: the password is stored at the next typed unlock. Turning it off
     * deletes the stored password; returns why that failed, or null.
     */
    public suspend fun setEnabled(enabled: Boolean): String? {
        settings.setString(ENABLED, if (enabled) ON else null)
        // The days count from here: the password was typed a moment ago, or is typed at the next unlock.
        if (enabled) typedNow()
        mutableState.update { it.copy(enabled = enabled, setupFailure = null, passwordDue = isDue(it.reminderDays)) }
        return if (enabled) null else delete()
    }

    /** Ask for the typed password after [days] without one; null: never. */
    public fun setReminderDays(days: Int?) {
        settings.setString(REMINDER, days?.toString() ?: NEVER)
        mutableState.update { it.copy(reminderDays = days, passwordDue = isDue(days)) }
    }

    /**
     * The typed password: unlocks, and stores the password when the switch is on and this unlock opened the
     * vault. The caller may wipe its own copy as soon as this returns. False when the vault was not locked.
     */
    public fun unlock(password: Secret): Boolean {
        val outcome = session.unlock(password) ?: return false
        val kept = password.copy()
        scope.launch {
            try {
                if (outcome.await() == UnlockOutcome.Opened) {
                    typedNow()
                    mutableState.update { it.copy(passwordDue = false) }
                    if (state.value.enabled) storeTyped(kept)
                }
            } finally {
                kept.wipe()
            }
        }
        return true
    }

    /** Asks for the fingerprint and unlocks with the stored password. One at a time. */
    public suspend fun unlockWithFingerprint(): FingerprintOutcome {
        if (state.value.prompting || session.state.value !is SessionState.Locked) return FingerprintOutcome.InProgress
        if (!state.value.enabled) return FingerprintOutcome.NotAvailable("it is turned off in Settings")
        if (isDue(state.value.reminderDays)) {
            mutableState.update { it.copy(passwordDue = true) }
            return FingerprintOutcome.PasswordDue
        }
        mutableState.update { it.copy(prompting = true) }
        try {
            val availability = withContext(io) { store.availability() }
            mutableState.update { it.copy(availability = availability) }
            if (availability is BiometricAvailability.Unavailable) return FingerprintOutcome.NotAvailable(availability.reason)
            if (!withContext(io) { runCatching { store.hasPassword() }.getOrDefault(false) }) return nothingStored()
            val opening = try {
                store.open(onRecognised = { mutableState.update { it.copy(opening = true) } })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return FingerprintOutcome.StorageError(failure.reason())
            }
            return when (opening) {
                is StoredPasswordOpening.Opened -> unlockWithStored(opening.password)
                StoredPasswordOpening.Cancelled -> FingerprintOutcome.Cancelled
                is StoredPasswordOpening.Failed -> FingerprintOutcome.AuthenticationFailed(opening.reason)
                StoredPasswordOpening.KeyInvalidated -> {
                    delete()
                    FingerprintOutcome.KeyInvalidated
                }
                StoredPasswordOpening.NothingStored -> nothingStored()
            }
        } finally {
            mutableState.update { it.copy(prompting = false, opening = false) }
        }
    }

    private suspend fun unlockWithStored(password: Secret): FingerprintOutcome {
        val outcome = try {
            session.unlock(password)
        } finally {
            password.wipe()
        } ?: return FingerprintOutcome.InProgress
        return when (val ended = outcome.await()) {
            UnlockOutcome.Opened -> FingerprintOutcome.Unlocked
            UnlockOutcome.Interrupted -> FingerprintOutcome.Cancelled
            is UnlockOutcome.Failed -> if (ended.failure == OpenFailure.WrongPassword) {
                delete()
                FingerprintOutcome.StoredPasswordRejected
            } else {
                FingerprintOutcome.UnlockFailed(ended.failure)
            }
        }
    }

    private suspend fun storeTyped(password: Secret) {
        val availability = withContext(io) { store.availability() }
        mutableState.update { it.copy(availability = availability) }
        if (availability != BiometricAvailability.Available) return
        val failure = withContext(io) {
            try {
                store.save(password)
                null
            } catch (failure: Exception) {
                failure.reason()
            }
        }
        val stored = failure == null || withContext(io) { runCatching { store.hasPassword() }.getOrDefault(false) }
        mutableState.update { it.copy(stored = stored, setupFailure = failure) }
    }

    private fun nothingStored(): FingerprintOutcome {
        mutableState.update { it.copy(stored = false) }
        return FingerprintOutcome.NothingStored
    }

    /** Returns why it failed, or null. */
    private suspend fun delete(): String? {
        val failure = withContext(io) {
            try {
                store.delete()
                null
            } catch (failure: Exception) {
                failure.reason()
            }
        }
        val stored = failure != null && withContext(io) { runCatching { store.hasPassword() }.getOrDefault(true) }
        mutableState.update { it.copy(stored = stored) }
        return failure
    }

    private fun reminderDays(): Int? = when (val saved = settings.string(REMINDER)) {
        null -> BiometricState.DEFAULT_REMINDER_DAYS
        NEVER -> null
        else -> saved.toIntOrNull() ?: BiometricState.DEFAULT_REMINDER_DAYS
    }

    private fun typedNow() {
        settings.setString(LAST_TYPED, clock.now().toEpochMilli().toString())
    }

    /**
     * Wall-clock days, as the user counts them. No time saved yet (it was turned on before this rule), or one
     * in the future (the clock was set back): the days count from now.
     */
    private fun isDue(days: Int?): Boolean {
        if (days == null) return false
        val now = clock.now()
        val last = settings.string(LAST_TYPED)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        if (last == null || last.isAfter(now)) {
            typedNow()
            return false
        }
        return Duration.between(last, now) >= Duration.ofDays(days.toLong())
    }

    private fun Exception.reason(): String = message ?: javaClass.simpleName

    private companion object {
        const val ENABLED = "biometricUnlock"
        const val ON = "on"
        const val REMINDER = "passwordReminderDays"
        const val NEVER = "never"
        const val LAST_TYPED = "lastTypedUnlock"
    }
}
