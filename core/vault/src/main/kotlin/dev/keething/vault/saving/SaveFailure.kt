package dev.keething.vault.saving

import dev.keething.domain.vault.VaultGroup
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.OpenedVault

/** Why a save wrote nothing (Mac `KDBXFileSaver.Failure`), in terms the screens can show. Never carries entry data. */
public sealed interface SaveFailure {
    /** The file could not be read and opened again; a password changed elsewhere is [OpenFailure.WrongPassword]. */
    public data class CannotOpen(val failure: OpenFailure) : SaveFailure

    /** The entry is gone from the file (deleted elsewhere). */
    public data object EntryNotFound : SaveFailure

    /** The entry was changed elsewhere after it was loaded: the vault is read again, nothing saved. */
    public data object ChangedElsewhere : SaveFailure

    /** The group is gone from the file (deleted elsewhere): a new or moved entry's, or the group changed. */
    public data object GroupNotFound : SaveFailure

    /** A group's new name is empty. */
    public data object InvalidName : SaveFailure

    /** The group there has another group of that name by now, case and accents aside (keething-spec groups.manage AC1). */
    public data object GroupNameTaken : SaveFailure

    /**
     * Not changed so: the root group, the recycle bin or a group in it, a move into the bin, into itself or a group
     * inside it, or where it is (groups.manage AC5, AC7); the file may have changed since it was read.
     */
    public data object GroupNotChangeable : SaveFailure

    /** The group to delete has something in it by now (groups.manage AC6). */
    public data object GroupNotEmpty : SaveFailure

    /** The database has the recycle bin turned off. */
    public data object RecycleBinDisabled : SaveFailure

    /** The entry is not where the change expects it (in the recycle bin, or out of it). */
    public data object WrongPlace : SaveFailure

    /** The written file did not read back as expected; nothing was replaced. */
    public data class VerificationFailed(val detail: String) : SaveFailure

    /**
     * The change would leave the file with something KeePass's own schema does not allow that it did not have
     * before ([what], without values, e.g. "an entry's CustomIconUUID: no such custom icon"); nothing was replaced.
     */
    public data class BreaksFormat(val what: String) : SaveFailure

    /** A KDBX 3.1 database, viewed only: Keething changes databases only in KDBX 4 (keething-spec kdbx.kdbx3 AC6). */
    public data object ViewOnly : SaveFailure

    /** The file changed on disk while saving, twice in a row. */
    public data object FileKeepsChanging : SaveFailure

    /** A file could not be read or written: the database, its backup or the new file. */
    public data class WriteFailed(val detail: String) : SaveFailure

    /** The phone has not enough memory to make the new file. */
    public data object OutOfMemory : SaveFailure

    /**
     * The database locked and the save did not finish in time (keething-spec kdbx.hardening AC3): it stopped before the
     * backup and the replace, so the file and its backups are as they were.
     */
    public data object Locked : SaveFailure
}

internal sealed interface SaveResult {
    /**
     * The file was replaced; [opened] is it read back, ready to be the vault. [deletedGroups]: the groups deleted for
     * good, as they were (the empty ones a move left, innermost first, or the empty group deleted).
     */
    class Saved(val opened: OpenedVault, val deletedGroups: List<VaultGroup> = emptyList()) : SaveResult

    class Failed(val failure: SaveFailure) : SaveResult
}
