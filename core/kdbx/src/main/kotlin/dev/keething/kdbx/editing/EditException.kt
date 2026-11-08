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

    /** Only an entry in the recycle bin is restored, or deleted for good while the bin is on. */
    public class NotInRecycleBin : EditException("not in the recycle bin")

    /** The recycle bin's `PreviousParentGroup` links go round in a circle. */
    public class MalformedLinks : EditException("previous groups in a circle")

    /** A group's name must not be empty. */
    public class InvalidName : EditException("a group needs a name")

    /** The parent has another group of that name already, in any case (a path would name either). */
    public class GroupNameTaken : EditException("the parent has a group of that name")

    /**
     * Not renamed, moved or deleted: the root group, the recycle bin or a group in it (keething-spec groups.manage
     * AC7); nor made in one of those, nor moved into the bin, into itself or a group inside it, or where it is.
     */
    public class GroupNotChangeable : EditException("the group cannot be changed so")

    /** Only a group with no entries and no groups is deleted this way. */
    public class GroupNotEmpty : EditException("the group is not empty")

    /**
     * The change would leave the file with something KeePass's schema does not allow that it did not have before:
     * [what] says it as "an entry's PreviousParentGroup: out of order", never with a value.
     */
    public class BreaksFormat(public val what: String) : EditException("breaks the format: $what")

    /** An attachment the edit keeps is not on the entry (keething-spec edit.attachments). */
    public class AttachmentNotFound : EditException("an attachment to keep is gone")

    /** The entry's history has no such version (any more; keething-spec edit.history-restore). */
    public class VersionNotFound : EditException("no such version in the history")

    /** The document lacks what every KeePass file has (`Meta`, the root group). */
    public class Malformed(detail: String) : EditException("malformed: $detail")
}
