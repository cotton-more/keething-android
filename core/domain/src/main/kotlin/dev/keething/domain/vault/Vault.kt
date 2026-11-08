package dev.keething.domain.vault

import java.time.Instant

/** The unlocked database's content. */
public data class Vault(
    /** All entries except those in the recycle bin, history items and the settings entry. */
    val entries: List<Entry>,
    /**
     * The first entry titled "Keething" outside the recycle bin: the Mac app's settings (the e-mail alias
     * providers' keys), not an entry to browse.
     */
    val settings: Entry? = null,
    /** The groups outside the recycle bin, in file order (the root group first). */
    val groups: List<VaultGroup> = emptyList(),
    /** The entries in the recycle bin (at any depth), in file order. */
    val recycleBin: List<Entry> = emptyList(),
    /**
     * The entries in the templates group (at any depth; keething-spec groups.templates): forms another app fills new
     * entries from, never listed, searched or offered. Its groups are not in [groups].
     */
    val templates: List<Entry> = emptyList(),
    /** The templates group itself, null when the file has none. */
    val templatesGroup: VaultGroup? = null,
    /** `Meta/RecycleBinEnabled`. */
    val recycleBinEnabled: Boolean = true,
    /** `Meta/DefaultUserName`: the username a new entry starts with. */
    val defaultUsername: String = "",
    /** The database's custom icons (favicons KeePassXC downloaded) by UUID. */
    val customIcons: Map<String, CustomIcon> = emptyMap(),
    /** `Meta/DatabaseName`, its outer spaces aside; empty when the file has none. */
    val name: String = "",
    /** `Meta/Color` as written (KeePass 2.x writes `#RRGGBB`); null when the file has none. */
    val color: String? = null,
    /** `Meta/MasterKeyChanged`: when its master key was last changed; null when the file does not say. */
    val masterKeyChanged: Instant? = null,
) {
    /** Overwrites every secret the vault holds (the lock). */
    public fun wipe() {
        entries.forEach(Entry::wipe)
        recycleBin.forEach(Entry::wipe)
        templates.forEach(Entry::wipe)
        settings?.wipe()
    }

    public companion object {
        /** The title of the entry that holds the Mac app's settings (outer spaces ignored). */
        public const val SETTINGS_TITLE: String = "Keething"
    }
}

/** A custom icon's image as the file stores it (PNG, usually). */
public class CustomIcon(public val image: ByteArray) {
    override fun equals(other: Any?): Boolean = other is CustomIcon && image.contentEquals(other.image)

    override fun hashCode(): Int = image.contentHashCode()
}
