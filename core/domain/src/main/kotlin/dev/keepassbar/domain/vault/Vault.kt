package dev.keepassbar.domain.vault

/** The unlocked database's content. */
public data class Vault(
    /** All entries except those in the recycle bin, history items and the settings entry. */
    val entries: List<Entry>,
    /**
     * The first entry titled "KeePassBar" outside the recycle bin: the Mac app's settings (field types), not
     * an entry to browse.
     */
    val settings: Entry? = null,
    /** The groups outside the recycle bin, in file order (the root group first). */
    val groups: List<VaultGroup> = emptyList(),
    /** The entries in the recycle bin (at any depth), in file order. */
    val recycleBin: List<Entry> = emptyList(),
    /** `Meta/RecycleBinEnabled`. */
    val recycleBinEnabled: Boolean = true,
    /** `Meta/DefaultUserName`: the username a new entry starts with. */
    val defaultUsername: String = "",
    /** The database's custom icons (favicons KeePassXC downloaded) by UUID. */
    val customIcons: Map<String, CustomIcon> = emptyMap(),
) {
    /** Overwrites every secret the vault holds (the lock). */
    public fun wipe() {
        entries.forEach(Entry::wipe)
        recycleBin.forEach(Entry::wipe)
        settings?.wipe()
    }

    public companion object {
        /** Title of the entry that holds the Mac app's settings. */
        public const val SETTINGS_TITLE: String = "KeePassBar"
    }
}

/** A custom icon's image as the file stores it (PNG, usually). */
public class CustomIcon(public val image: ByteArray) {
    override fun equals(other: Any?): Boolean = other is CustomIcon && image.contentEquals(other.image)

    override fun hashCode(): Int = image.contentHashCode()
}
