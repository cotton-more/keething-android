package dev.keepassbar.domain.vault

import java.time.Instant

/**
 * One change written into the database file (SPEC §3.3; Mac `VaultEdit`, the part the phone does). A
 * `loadedModified` is the entry's `LastModificationTime` as loaded: when the file says otherwise, the entry was
 * changed elsewhere and the edit is refused.
 */
public sealed interface VaultEdit {
    public val entryId: String

    /** The entry's fields; its old version goes into its history. */
    public data class Update(
        override val entryId: String,
        val loadedModified: Instant?,
        val changes: EntryChanges,
    ) : VaultEdit

    /** A new entry ([Entry.newId]) at the end of an existing group's entries, with the fields that are set. */
    public data class Create(
        override val entryId: String,
        val groupId: String,
        val fields: EntryChanges,
    ) : VaultEdit

    /** Into the recycle bin (made when there is none), remembering the group it came from. */
    public data class MoveToRecycleBin(override val entryId: String, val loadedModified: Instant?) : VaultEdit

    /** Out of the recycle bin, back into the group it came from (the root group when that is gone). */
    public data class Restore(override val entryId: String, val loadedModified: Instant?) : VaultEdit
}
