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

    /** The version to restore is not in the entry's history any more (keething-spec edit.history-restore). */
    public data object VersionGone : SaveFailure

    /** The written file did not read back as expected; nothing was replaced. */
    public data class VerificationFailed(val detail: String) : SaveFailure

    /**
     * The change would leave the file with something KeePass's own schema does not allow that it did not have
     * before ([what], without values, e.g. "an entry's CustomIconUUID: no such custom icon"); nothing was replaced.
     */
    public data class BreaksFormat(val what: String) : SaveFailure

    /** A KDBX 3.1 database, viewed only: Keething changes databases only in KDBX 4 (keething-spec kdbx.kdbx3 AC6). */
    public data object ViewOnly : SaveFailure

    /**
     * The KDBX 3.1 file could not be kept beside the database before its upgrade ([detail]: why); nothing was written
     * (keething-spec kdbx.kdbx3 AC7).
     */
    public data class CopyNotKept(val detail: String) : SaveFailure

    /** The conflict copy to take in the database's place is not there any more (keething-spec kdbx.conflict-copies AC6). */
    public data object CopyGone : SaveFailure

    /**
     * The conflict copy to take in the database's place does not open with the database's key, or at all ([failure]):
     * nothing was replaced (keething-spec kdbx.conflict-copies AC6).
     */
    public data class CopyNotOpened(val failure: OpenFailure) : SaveFailure

    /** The backup to restore is not there any more (keething-spec databases.backups-window AC8). */
    public data object BackupGone : SaveFailure

    /**
     * The backup to restore would not open: its header is damaged, or its bytes are not those kept (keething-spec
     * databases.backups-window AC5, AC8); nothing was replaced.
     */
    public data object BackupDamaged : SaveFailure

    /**
     * The backup to restore was opened with the database's key and is not readable ([failure]: damaged inside, a format
     * Keething does not read), not a key it rejects (that one is restored, keething-spec databases.backups-window AC7);
     * nothing was replaced.
     */
    public data class BackupNotOpened(val failure: OpenFailure) : SaveFailure

    /** The file changed on disk while saving, twice in a row. */
    public data object FileKeepsChanging : SaveFailure

    /** A file could not be read or written: the database, its backup or the new file. */
    public data class WriteFailed(val detail: String) : SaveFailure

    /** The phone has not enough memory to make the new file. */
    public data object OutOfMemory : SaveFailure

    /**
     * Read-only: the database is too large for a save to fit safely in the memory the app is given, so nothing was
     * tried (keething-spec app.large-database AC3; [dev.keething.vault.session.SaveRoom.TooLarge], whose [alone] it is).
     */
    public data class TooLarge(val alone: Boolean) : SaveFailure

    /**
     * The database locked and the save did not finish in time (keething-spec kdbx.hardening AC3): it stopped before the
     * backup and the replace, so the file and its backups are as they were.
     */
    public data object Locked : SaveFailure
}

internal sealed interface SaveResult {
    /**
     * The file was replaced; [opened] is it read back, ready to be the vault. [deletedGroups]: the groups deleted for
     * good, as they were (the empty ones a move left, innermost first, or the empty group deleted). [keptCopy]: the
     * name an upgrade's KDBX 3.1 file was kept under beside the database. [copyLeft]: the conflict copy taken in the
     * database's place that could not be deleted afterwards.
     */
    class Saved(
        val opened: OpenedVault,
        val deletedGroups: List<VaultGroup> = emptyList(),
        val keptCopy: String? = null,
        val copyLeft: String? = null,
    ) : SaveResult

    class Failed(val failure: SaveFailure) : SaveResult
}

internal sealed interface RestoreResult {
    /**
     * The file was replaced with the backup's bytes. [opened]: those read back with the database's key, ready to be the
     * vault; null when no key was given (locked) or the backup does not open with it ([olderKey]). [beforeRestoring]:
     * the backup the file as it was is in, to undo it with.
     */
    class Restored(val opened: OpenedVault?, val olderKey: Boolean, val beforeRestoring: String) : RestoreResult

    class Failed(val failure: SaveFailure) : RestoreResult
}
