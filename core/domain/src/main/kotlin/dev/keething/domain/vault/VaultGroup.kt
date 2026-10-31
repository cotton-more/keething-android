package dev.keething.domain.vault

/** A group of the database. Group names need not be unique, so a group is identified by its UUID. */
public data class VaultGroup(
    /** KeePass group UUID, base64 as in the XML. */
    val id: String,
    val name: String,
    /** Group names from the top-level group down, without the root group (as [Entry.groupPath]). */
    val path: List<String>,
) {
    val isRoot: Boolean get() = path.isEmpty()
}

/** A group made on the way, when an entry goes into a group that is not there yet (Mac `NewGroup`). */
public data class NewGroup(
    /** A new KeePass UUID ([Entry.newId]), made once per draft. */
    val id: String,
    val name: String,
)

/**
 * Where an entry goes (Mac `GroupDestination`): an existing group, perhaps with new groups made in it, each inside the
 * one before; the entry goes into the last.
 */
public data class GroupDestination(
    /** The existing group. */
    val groupId: String,
    val newGroups: List<NewGroup> = emptyList(),
) {
    /** The group the entry ends up in. */
    val finalGroupId: String get() = newGroups.lastOrNull()?.id ?: groupId
}
