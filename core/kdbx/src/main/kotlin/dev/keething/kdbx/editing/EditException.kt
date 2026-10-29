package dev.keething.kdbx.editing

/** Why an edit could not be applied to the file as it is now (Mac `EntryLifecycle.Failure`, `GroupLifecycle.Failure`). */
public sealed class EditException(message: String) : Exception(message) {
    public class EntryNotFound : EditException("entry not found")

    public class GroupNotFound : EditException("group not found")

    /** A new entry's id is taken. */
    public class EntryExists : EditException("entry exists")

    /** The database has the recycle bin turned off. */
    public class RecycleBinDisabled : EditException("recycle bin turned off")

    public class AlreadyInRecycleBin : EditException("already in the recycle bin")

    /** Only an entry in the recycle bin is restored. */
    public class NotInRecycleBin : EditException("not in the recycle bin")

    /** The recycle bin's `PreviousParentGroup` links go round in a circle. */
    public class MalformedLinks : EditException("previous groups in a circle")

    /**
     * The change would leave the file with something KeePass's schema does not allow that it did not have before:
     * [what] says it as "an entry's PreviousParentGroup: out of order", never with a value.
     */
    public class BreaksFormat(public val what: String) : EditException("breaks the format: $what")

    /** The document lacks what every KeePass file has (`Meta`, the root group). */
    public class Malformed(detail: String) : EditException("malformed: $detail")
}
