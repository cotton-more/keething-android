package dev.keething.vault.saving

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

    /** The group for a new entry is gone from the file. */
    public data object GroupNotFound : SaveFailure

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

    /** The file changed on disk while saving, twice in a row. */
    public data object FileKeepsChanging : SaveFailure

    /** A file could not be read or written: the database, its backup or the new file. */
    public data class WriteFailed(val detail: String) : SaveFailure

    /** The phone has not enough memory to make the new file. */
    public data object OutOfMemory : SaveFailure
}

internal sealed interface SaveResult {
    /** The file was replaced; [opened] is it read back, ready to be the vault. */
    class Saved(val opened: OpenedVault) : SaveResult

    class Failed(val failure: SaveFailure) : SaveResult
}
