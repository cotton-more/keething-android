package dev.keething.vault.session

import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.StoppedSave
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import dev.keething.vault.opening.DatabaseSize
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.saving.SaveFailure
import java.time.Instant
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

/** Whether a key typed is the one the database is open with (keething-spec databases.change-master-key AC1). */
public sealed interface KeyConfirmation {
    /** It is; [withPassword]: it has a password, empty or not, rather than its key file alone. */
    public data class Confirmed(val withPassword: Boolean) : KeyConfirmation

    /** It is not. */
    public data object Wrong : KeyConfirmation

    /** Its key file could not be read: nothing was compared. */
    public data class Failed(val failure: OpenFailure) : KeyConfirmation

    /** Not open (any more). */
    public data object Locked : KeyConfirmation
}

/**
 * A database's contents in brief, to choose between it and a conflict copy (keething-spec kdbx.conflict-copies AC6):
 * how many entries (the recycle bin's apart) and the newest change of one, the bin's too; null when none has a time.
 */
public data class VaultSummary(val entries: Int, val lastChanged: Instant?) {
    public companion object {
        public fun of(vault: Vault): VaultSummary =
            VaultSummary(vault.entries.size, (vault.entries + vault.recycleBin).mapNotNull { it.lastModified }.maxOrNull())
    }
}

/** What a conflict copy holds, read with the database's key (keething-spec kdbx.conflict-copies AC6). */
public sealed interface CopyContents {
    public data class Read(val summary: VaultSummary) : CopyContents

    /** It does not open with the database's key, or at all ([failure]); gone is [OpenFailure.Missing]. */
    public data class NotOpened(val failure: OpenFailure) : CopyContents
}

/** How one save ended. */
public sealed interface SaveOutcome {
    /**
     * The file was replaced and the vault is the file read back. [deletedGroups]: the groups the save deleted for good,
     * as they were (the empty ones a move left, innermost first, or the empty group deleted; keething-spec
     * groups.entry-group AC7). [keptCopy]: an upgrade's, the name its KDBX 3.1 file was kept under beside it.
     * [copyLeft]: a conflict copy taken in the database's place, still there: it could not be deleted afterwards.
     */
    public data class Saved(val deletedGroups: List<VaultGroup> = emptyList(), val keptCopy: String? = null, val copyLeft: String? = null) : SaveOutcome

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
        /**
         * A KDBX 3.1 database: read like any other, every change refused ([SaveFailure.ViewOnly]; keething-spec
         * kdbx.kdbx3 AC6). Keething changes databases only in KDBX 4.
         */
        val viewOnly: Boolean = false,
        /** How large the file last read is, and its attachments (keething-spec app.large-database AC3). */
        val size: DatabaseSize = DatabaseSize(0, 0),
        /**
         * Whether a save has the memory it needs, judged at the unlock and kept until the lock (AC3); read-only beside
         * others ([SaveRoom.TooLarge.alone] false), it is judged again when they lock, only ever to changeable.
         */
        val room: SaveRoom = SaveRoom.Enough,
        /**
         * It was read-only beside other databases and became changeable once they locked (keething-spec
         * app.large-database AC3, databases.read-only-mark AC3): said until [VaultSession.changeableAgainSaid].
         */
        val changeableAgain: Boolean = false,
    ) : SessionState {
        /** Every change refused: KDBX 3.1 ([viewOnly]), or too large for the memory the app is given ([room]). */
        val readOnly: Boolean get() = viewOnly || room is SaveRoom.TooLarge
    }
}

/**
 * A change that was not saved because the database locked first (keething-spec kdbx.hardening AC3): it was still being
 * saved when the lock's 10 s ran out, it failed after the lock, or it was waiting for the save before it. What kind of
 * change and the [id] of its entry or group only: never a value, kept through the lock until the next unlock said it.
 */
public data class UnsavedChange(val kind: Kind, val id: String) {
    public enum class Kind { ENTRY_CHANGED, ENTRY_CREATED, ENTRY_TO_RECYCLE_BIN, ENTRY_RESTORED, ENTRY_DELETED, VERSION_RESTORED, GROUP_RENAMED, GROUP_CREATED, GROUP_MOVED, GROUP_DELETED, NAME_AND_COLOR }

    internal companion object {
        fun of(edit: VaultEdit): UnsavedChange = when (edit) {
            is VaultEdit.Update -> UnsavedChange(Kind.ENTRY_CHANGED, edit.entryId)
            is VaultEdit.Create -> UnsavedChange(Kind.ENTRY_CREATED, edit.entryId)
            is VaultEdit.MoveToRecycleBin -> UnsavedChange(Kind.ENTRY_TO_RECYCLE_BIN, edit.entryId)
            is VaultEdit.Restore -> UnsavedChange(Kind.ENTRY_RESTORED, edit.entryId)
            is VaultEdit.DeleteForGood -> UnsavedChange(Kind.ENTRY_DELETED, edit.entryId)
            is VaultEdit.RestoreVersion -> UnsavedChange(Kind.VERSION_RESTORED, edit.entryId)
            is VaultEdit.RenameGroup -> UnsavedChange(Kind.GROUP_RENAMED, edit.groupId)
            is VaultEdit.CreateGroup -> UnsavedChange(Kind.GROUP_CREATED, edit.parentId)
            is VaultEdit.MoveGroup -> UnsavedChange(Kind.GROUP_MOVED, edit.groupId)
            is VaultEdit.DeleteEmptyGroup -> UnsavedChange(Kind.GROUP_DELETED, edit.groupId)
            is VaultEdit.NameAndColor -> UnsavedChange(Kind.NAME_AND_COLOR, "")
        }
    }
}
