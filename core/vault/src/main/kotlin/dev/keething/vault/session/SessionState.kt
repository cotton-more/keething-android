package dev.keething.vault.session

import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.vault.Vault
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.saving.SaveFailure
import kotlin.time.Duration

/** Why the vault is locked (the lock screen shows it as a chip, SPEC §6). */
public sealed interface LockReason {
    /** Not unlocked since the app started. */
    public data object Initial : LockReason

    /** The lock button. */
    public data object Manual : LockReason

    /** The app was in the background for the lock timeout (SPEC §4). */
    public data object Timeout : LockReason

    /** Opening the database failed. */
    public data class UnlockFailed(val failure: OpenFailure) : LockReason

    /** A reload found that the key no longer opens the database: its password was changed elsewhere. */
    public data object PasswordChanged : LockReason
}

/** How one unlock ended. */
public sealed interface UnlockOutcome {
    public data object Opened : UnlockOutcome

    public data class Failed(val failure: OpenFailure) : UnlockOutcome

    /** A lock came before the file was opened; what was opened is wiped. */
    public data object Interrupted : UnlockOutcome
}

/** How one save ended. */
public sealed interface SaveOutcome {
    /** The file was replaced and the vault is the file read back. */
    public data object Saved : SaveOutcome

    public data class Failed(val failure: SaveFailure) : SaveOutcome

    /** A lock came first; what the save read back is wiped (the file may have been written). */
    public data object Interrupted : SaveOutcome
}

public sealed interface SessionState {
    public data class Locked(val reason: LockReason) : SessionState

    public data object Unlocking : SessionState

    public data class Unlocked(
        val vault: Vault,
        /** Syncthing's conflict copies next to the database, newest first (SPEC §3.4). */
        val conflictCopies: List<ConflictCopy> = emptyList(),
        /** Why the last reload failed, when the vault shown is the one before it; cleared by the next success. */
        val reloadFailure: OpenFailure? = null,
        /** How long the last key derivation took; null when the key derived before was used. */
        val keyDerivation: Duration? = null,
    ) : SessionState
}
