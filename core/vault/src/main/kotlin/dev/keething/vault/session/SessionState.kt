package dev.keething.vault.session

import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.StoppedSave
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
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
    /**
     * The file was replaced and the vault is the file read back. [deletedGroups]: the groups the save deleted for good,
     * as they were (the empty ones a move left, innermost first, or the empty group deleted; keething-spec
     * groups.entry-group AC7).
     */
    public data class Saved(val deletedGroups: List<VaultGroup> = emptyList()) : SaveOutcome

    public data class Failed(val failure: SaveFailure) : SaveOutcome

    /**
     * A lock came first; what the save read back is wiped. The file may have been written: when it was not, the next
     * unlock says so ([SessionState.Unlocked.unsaved]).
     */
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
        /**
         * A save the app did not finish (it ended in the middle) was put right, this way (keething-spec databases.saf AC5):
         * said until [VaultSession.stoppedSaveSaid].
         */
        val stoppedSave: StoppedSave? = null,
        /**
         * The changes a lock kept out of the file before this unlock (keething-spec kdbx.hardening AC3), in the order they
         * were saved: said until [VaultSession.unsavedSaid].
         */
        val unsaved: List<UnsavedChange> = emptyList(),
    ) : SessionState
}

/**
 * A change that was not saved because the database locked first (keething-spec kdbx.hardening AC3): it was still being
 * saved when the lock's 10 s ran out, it failed after the lock, or it was waiting for the save before it. What kind of
 * change and the [id] of its entry or group only: never a value, kept through the lock until the next unlock said it.
 */
public data class UnsavedChange(val kind: Kind, val id: String) {
    public enum class Kind { ENTRY_CHANGED, ENTRY_CREATED, ENTRY_TO_RECYCLE_BIN, ENTRY_RESTORED, ENTRY_DELETED, GROUP_RENAMED, GROUP_CREATED, GROUP_MOVED, GROUP_DELETED }

    internal companion object {
        fun of(edit: VaultEdit): UnsavedChange = when (edit) {
            is VaultEdit.Update -> UnsavedChange(Kind.ENTRY_CHANGED, edit.entryId)
            is VaultEdit.Create -> UnsavedChange(Kind.ENTRY_CREATED, edit.entryId)
            is VaultEdit.MoveToRecycleBin -> UnsavedChange(Kind.ENTRY_TO_RECYCLE_BIN, edit.entryId)
            is VaultEdit.Restore -> UnsavedChange(Kind.ENTRY_RESTORED, edit.entryId)
            is VaultEdit.DeleteForGood -> UnsavedChange(Kind.ENTRY_DELETED, edit.entryId)
            is VaultEdit.RenameGroup -> UnsavedChange(Kind.GROUP_RENAMED, edit.groupId)
            is VaultEdit.CreateGroup -> UnsavedChange(Kind.GROUP_CREATED, edit.parentId)
            is VaultEdit.MoveGroup -> UnsavedChange(Kind.GROUP_MOVED, edit.groupId)
            is VaultEdit.DeleteEmptyGroup -> UnsavedChange(Kind.GROUP_DELETED, edit.groupId)
        }
    }
}
