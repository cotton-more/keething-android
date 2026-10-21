package dev.keepassbar.kdbx.xml

import dev.keepassbar.domain.vault.CustomIcon
import dev.keepassbar.domain.vault.FieldValue
import java.time.Instant

/**
 * What the file's XML says, without KeePass's meaning applied (Mac `RawDocument`): which fields are the
 * standard ones, the one-time code or the recycle bin is decided by the vault.
 */
public class KdbxDocument(
    public val meta: KdbxMeta,
    /** Every group, in file order (the root group first). */
    public val groups: List<KdbxGroup>,
    /** Every entry outside the entries' histories, in file order. */
    public val entries: List<KdbxEntry>,
)

public data class KdbxMeta(
    /** "" when the file names none. */
    val recycleBinUuid: String = "",
    /** `RecycleBinEnabled`: on unless the file says "False". */
    val recycleBinEnabled: Boolean = true,
    val defaultUserName: String = "",
    val customIcons: Map<String, CustomIcon> = emptyMap(),
)

public data class KdbxGroup(
    val uuid: String,
    val name: String,
    /** Group names below the root group, outermost first (empty for the root group). */
    val path: List<String>,
    /** UUIDs of the group and all enclosing groups, outermost first. */
    val ancestorUuids: List<String>,
)

public data class KdbxEntry(
    val uuid: String,
    /** `String` items in file order; a protected value is a secret. */
    val fields: List<KdbxField>,
    val tags: String = "",
    val expires: Boolean = false,
    val expiryTime: Instant? = null,
    val lastModified: Instant? = null,
    val created: Instant? = null,
    val attachments: List<KdbxAttachmentRef> = emptyList(),
    /** Group names below the root group, outermost first. */
    val groupPath: List<String> = emptyList(),
    /** UUIDs of all enclosing groups including the root group, outermost first. */
    val ancestorGroupUuids: List<String> = emptyList(),
    val customIconUuid: String? = null,
    /** The entry's own `CustomData` items, key → value (other apps' data; Keething's field types). */
    val customData: Map<String, String> = emptyMap(),
)

public data class KdbxField(val key: String, val value: FieldValue)

/** An entry's attachment: its name, and which of the file's attachments it is (null when unreadable). */
public data class KdbxAttachmentRef(val name: String, val ref: Int?)
