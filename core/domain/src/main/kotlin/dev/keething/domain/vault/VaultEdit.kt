package dev.keething.domain.vault

import java.time.Instant

/**
 * One change written into the database file (SPEC §3.3; Mac `VaultEdit`, the part the phone does): an entry's
 * ([EntryEdit]) or the groups' ([GroupEdit]).
 */
public sealed interface VaultEdit {
    /**
     * A change of one entry. A `loadedModified` is the entry's `LastModificationTime` as loaded: when the file says
     * otherwise, the entry was changed elsewhere and the edit is refused.
     */
    public sealed interface EntryEdit : VaultEdit {
        public val entryId: String
    }

    /** A change of the groups (keething-spec groups.manage): what is in them keeps everything but its path. */
    public sealed interface GroupEdit : VaultEdit

    /** The entry's fields; its old version goes into its history. With [EntryChanges.group] it also moves there. */
    public data class Update(
        override val entryId: String,
        val loadedModified: Instant?,
        val changes: EntryChanges,
    ) : EntryEdit

    /**
     * A new entry ([Entry.newId]) at the end of a group's entries, with the fields that are set: an existing group's,
     * or the last of the new groups made in it with the save.
     */
    public data class Create(
        override val entryId: String,
        val group: GroupDestination,
        val fields: EntryChanges,
    ) : EntryEdit

    /** Into the recycle bin (made when there is none), remembering the group it came from. */
    public data class MoveToRecycleBin(override val entryId: String, val loadedModified: Instant?) : EntryEdit

    /** Out of the recycle bin, back into the group it came from (the root group when that is gone). */
    public data class Restore(override val entryId: String, val loadedModified: Instant?) : EntryEdit

    /**
     * For good, with its history (keething-spec edit.delete-for-good): an entry in the recycle bin, or any entry when the
     * database has the bin turned off. A `DeletedObjects` record tells a synchronising copy to delete it too.
     */
    public data class DeleteForGood(override val entryId: String, val loadedModified: Instant?) : EntryEdit

    /** A new name (trimmed) for a group outside the recycle bin. */
    public data class RenameGroup(val groupId: String, val name: String) : GroupEdit

    /** A new, empty group (its name trimmed) after the other groups of a group outside the recycle bin. */
    public data class CreateGroup(val parentId: String, val group: NewGroup) : GroupEdit

    /** A group outside the recycle bin with everything in it into another group outside it, or the root group. */
    public data class MoveGroup(val groupId: String, val intoGroupId: String) : GroupEdit

    /** A group with nothing in it, outside the recycle bin, for good. */
    public data class DeleteEmptyGroup(val groupId: String) : GroupEdit
}
