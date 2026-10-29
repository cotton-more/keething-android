package dev.keething.domain.biometric

import dev.keething.domain.secret.Secret

/** Whether this phone can keep the master password for a fingerprint unlock. */
public sealed interface BiometricAvailability {
    public data object Available : BiometricAvailability

    /** No sensor, no fingerprint enrolled, …: [reason], for the user. */
    public data class Unavailable(val reason: String) : BiometricAvailability
}

/** What came of asking for the fingerprint to open a stored password. */
public sealed interface StoredPasswordOpening {
    /** The stored password (empty for the finger alone); the caller wipes it. */
    public class Opened(public val password: Secret) : StoredPasswordOpening

    /** The user cancelled or chose to type the password, or the system closed the prompt. */
    public data object Cancelled : StoredPasswordOpening

    /** The fingerprint check failed for good this time (too many attempts, a sensor error): [reason]. */
    public data class Failed(val reason: String) : StoredPasswordOpening

    /**
     * The key no longer opens any stored password: a fingerprint was added or all were removed, or the screen lock
     * was removed. Every database's is lost; only typing each password again helps.
     */
    public data object KeyInvalidated : StoredPasswordOpening

    /** This database's stored copy no longer opens (the key does, for the others): it alone is lost. */
    public data object CopyInvalidated : StoredPasswordOpening

    public data object NothingStored : StoredPasswordOpening
}

/**
 * Port: each database's master password kept for the fingerprint unlock (SPEC §5), sealed so that only a strong
 * biometric check opens it again; [database] names a database's own (its id as a file name). Sealing needs no
 * fingerprint. Except [open] and [confirm], the functions block on file and keystore work: call them off the main
 * thread.
 */
public interface BiometricPasswordStores {
    public fun availability(): BiometricAvailability

    /** Whether a password is stored for [database], without opening it (never a prompt). */
    public fun hasPassword(database: String): Boolean

    /** Seals [password] in place of the one stored for [database]; the caller still wipes its own. Throws when that fails. */
    public fun save(database: String, password: Secret)

    /**
     * Asks for the fingerprint, then opens [database]'s stored password; [onRecognised] is called once the fingerprint
     * is accepted, before the password is opened (in StrongBox that takes a moment). Throws when the stored copy cannot
     * be read.
     */
    public suspend fun open(database: String, onRecognised: () -> Unit): StoredPasswordOpening

    /**
     * Asks for the fingerprint and opens nothing: for a database that opens with its key file alone, where the
     * fingerprint is the check that it is the user. [StoredPasswordOpening.Opened] carries an empty password.
     */
    public suspend fun confirm(onRecognised: () -> Unit): StoredPasswordOpening

    /** [database]'s; deleting nothing is not an error. Throws when it fails. */
    public fun delete(database: String)

    /** Every database's, also of databases no longer in the list, and the key. Throws when it fails. */
    public fun deleteAll()
}
