package dev.keething.domain.biometric

import dev.keething.domain.secret.Secret

/** Whether this phone can keep the master password for a fingerprint unlock. */
public sealed interface BiometricAvailability {
    public data object Available : BiometricAvailability

    /** No sensor, no fingerprint enrolled, …: [reason], for the user. */
    public data class Unavailable(val reason: String) : BiometricAvailability
}

/** What came of asking for the fingerprint to open the stored password. */
public sealed interface StoredPasswordOpening {
    /** The stored password; the caller wipes it. */
    public class Opened(public val password: Secret) : StoredPasswordOpening

    /** The user cancelled or chose to type the password, or the system closed the prompt. */
    public data object Cancelled : StoredPasswordOpening

    /** The fingerprint check failed for good this time (too many attempts, a sensor error): [reason]. */
    public data class Failed(val reason: String) : StoredPasswordOpening

    /**
     * The key no longer opens the stored password: a fingerprint was added or all were removed, or the
     * screen lock was removed. Only typing the password again helps.
     */
    public data object KeyInvalidated : StoredPasswordOpening

    public data object NothingStored : StoredPasswordOpening
}

/**
 * Port: the master password kept for the fingerprint unlock (SPEC §5), sealed so that only a strong
 * biometric check opens it again. Sealing needs no fingerprint. Except [open], the functions block on
 * file and keystore work: call them off the main thread.
 */
public interface BiometricPasswordStore {
    public fun availability(): BiometricAvailability

    /** Whether a password is stored, without opening it (never a prompt). */
    public fun hasPassword(): Boolean

    /** Seals [password] in place of the one stored; the caller still wipes its own. Throws when that fails. */
    public fun save(password: Secret)

    /**
     * Asks for the fingerprint, then opens the stored password; [onRecognised] is called once the fingerprint is
     * accepted, before the password is opened (in StrongBox that takes a moment). Throws when the stored copy
     * cannot be read.
     */
    public suspend fun open(onRecognised: () -> Unit): StoredPasswordOpening

    /** Deleting nothing is not an error. Throws when it fails. */
    public fun delete()
}
