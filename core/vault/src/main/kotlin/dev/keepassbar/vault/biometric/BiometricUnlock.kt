package dev.keepassbar.vault.biometric

import dev.keepassbar.domain.biometric.BiometricAvailability
import dev.keepassbar.domain.biometric.BiometricPasswordStore
import dev.keepassbar.domain.biometric.StoredPasswordOpening
import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.settings.SettingsStore
import dev.keepassbar.vault.opening.OpenFailure
import dev.keepassbar.vault.session.SessionState
import dev.keepassbar.vault.session.UnlockOutcome
import dev.keepassbar.vault.session.VaultSession
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
) {
    public val canUnlock: Boolean get() = enabled && availability == BiometricAvailability.Available && stored
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
 *
 * Call it from [scope]'s thread (the main thread); the store's file and keystore work runs on [io], which
 * runs one task at a time in the app, so saves and deletes happen in the order they were asked for.
 */
public class BiometricUnlock(
    private val session: VaultSession,
    private val store: BiometricPasswordStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(BiometricState(enabled = settings.string(ENABLED) == ON))
    public val state: StateFlow<BiometricState> = mutableState.asStateFlow()

    /** Asks the phone again whether it can, and the store whether a password is there. */
    public suspend fun refresh() {
        val availability = withContext(io) { store.availability() }
        val stored = withContext(io) { runCatching { store.hasPassword() }.getOrDefault(false) }
        mutableState.update { it.copy(availability = availability, stored = stored) }
    }

    /**
     * Turning it on only sets the switch: the password is stored at the next typed unlock. Turning it off
     * deletes the stored password; returns why that failed, or null.
     */
    public suspend fun setEnabled(enabled: Boolean): String? {
        settings.setString(ENABLED, if (enabled) ON else null)
        mutableState.update { it.copy(enabled = enabled, setupFailure = null) }
        return if (enabled) null else delete()
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
                if (outcome.await() == UnlockOutcome.Opened && state.value.enabled) storeTyped(kept)
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
        mutableState.update { it.copy(prompting = true) }
        try {
            val availability = withContext(io) { store.availability() }
            mutableState.update { it.copy(availability = availability) }
            if (availability is BiometricAvailability.Unavailable) return FingerprintOutcome.NotAvailable(availability.reason)
            if (!withContext(io) { runCatching { store.hasPassword() }.getOrDefault(false) }) return nothingStored()
            val opening = try {
                store.open()
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
            mutableState.update { it.copy(prompting = false) }
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

    private fun Exception.reason(): String = message ?: javaClass.simpleName

    private companion object {
        const val ENABLED = "biometricUnlock"
        const val ON = "on"
    }
}
