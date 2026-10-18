package dev.keepassbar.domain.vault

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
