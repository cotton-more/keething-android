package dev.keething.domain.vault

import dev.keething.domain.secret.Secret
import dev.keething.domain.text.DomainText
import dev.keething.domain.text.UserText
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.totp.TotpFormat
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** An entry of the database, identified by its KeePass UUID. */
public data class Entry(
    /** KeePass entry UUID, base64 as in the XML. */
    val id: String,
    val title: String = "",
    val username: String = "",
    val password: Secret = Secret.empty(),
    val url: String = "",
    val notes: String = "",
    /** Notes are protected in the file (`Protected="True"`): a note's text by default (SPEC §6.4). An edit keeps it. */
    val notesProtected: Boolean = false,
    /** Group names from the top-level group down, without the database's root group. */
    val groupPath: List<String> = emptyList(),
    /** UUID of the group the entry is in ("" when not known). */
    val groupId: String = "",
    val tags: List<String> = emptyList(),
    /** Everything but the standard fields and the one-time code's fields, in file order. */
    val customFields: List<CustomField> = emptyList(),
    /**
     * The types of its custom fields (Mac SPEC §3.12): field name → type name as stored, kept in the entry's
     * `CustomData` ([EntryFieldTypes]). A field without one is read by its value.
     */
    val fieldTypes: Map<String, String> = emptyMap(),
    /** Its kind as stored in its `CustomData` ([EntryType], SPEC §6.4), null when it has none; kept as it is. */
    val entryType: String? = null,
    val totp: TotpConfig? = null,
    /** It has fields of a one-time code ([TotpFormat.FIELD_NAMES]), whether or not they make a code [totp] can read. */
    val hasCodeFields: Boolean = false,
    /**
     * The one-time code's own fields as the file holds them, in file order, protection kept: what puts the code back
     * as it was after it is removed (keething-spec app.codes-view AC5, [TotpChange.Fields]).
     */
    val codeFields: List<CustomField> = emptyList(),
    /**
     * A passkey's fields as the file holds them, in file order, protection kept ([Passkey], keething-spec card.passkey):
     * apart from [customFields], so nothing shows, searches, sends or edits them; a save leaves them as they are.
     */
    val passkeyFields: List<CustomField> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val expires: Boolean = false,
    val expiryTime: Instant? = null,
    /** `Times/LastModificationTime`: tells whether the entry was changed elsewhere before an edit is saved. */
    val lastModified: Instant? = null,
    /** `Times/CreationTime`. */
    val created: Instant? = null,
    /** UUID of the database's custom icon (a downloaded favicon) the entry shows, if any. */
    val customIconId: String? = null,
) {
    /** Title for display: the title, else the URL's host, else "(Untitled)" in the phone's language. */
    val displayTitle: UserText get() = UserText.plain(title.ifEmpty { urlHost ?: return UserText.of(DomainText.UNTITLED) })

    /** What the entry is ordered and matched by, and its monogram drawn from: the title, else the URL's host, else "". */
    val sortTitle: String get() = title.ifEmpty { urlHost.orEmpty() }

    /** The host of the entry's URL, for showing (title fallback, Open Website's label). */
    val urlHost: String? get() = UrlHost.of(url)

    /** The host of the entry's URL when it is a web address, not an app's (keething-spec search.list AC1). */
    val webHost: String? get() = if (AndroidAppAddress.packageOf(url) != null) null else urlHost

    /**
     * The entry's web addresses as written: its URL, then its additional URLs and its custom fields of type `url`
     * (keething-spec autofill.matching AC1). A protected value is never read (fail closed).
     */
    val addresses: List<String>
        get() = (listOf(url) + customFields.filter { it.isAdditionalUrl || isUrlField(it) }.mapNotNull { it.plainText })
            .filter { it.isNotEmpty() }

    private fun isUrlField(field: CustomField): Boolean = fieldTypes[field.name]?.trim().equals("url", ignoreCase = true)

    /**
     * Whether the entry is a note (keething-spec types.note AC1–AC3; Mac `Entry.isNote`): the one definition the card,
     * the list and the editor share. A stored `note` makes one, whatever else the entry has. Any other stored kind
     * leaves it an ordinary entry. Without one it is a note when its Notes are not blank, its username, password and
     * URL are blank, and it has no custom fields, no passkey and no one-time code (attachments allowed); reading it so writes
     * nothing.
     */
    val isNote: Boolean
        get() = EntryType.isNote(entryType, notes, username, password, url, customFields.isEmpty() && passkeyFields.isEmpty() && totp == null && !hasCodeFields)

    /** The passkey KeePassXC put into the entry, null when it has none. */
    val passkey: Passkey? get() = Passkey.of(passkeyFields)

    public fun isExpired(at: Instant): Boolean = expires && expiryTime != null && expiryTime <= at

    /** Overwrites the entry's secrets (the lock). */
    public fun wipe() {
        password.wipe()
        customFields.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() }
        codeFields.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() }
        passkeyFields.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() }
        totp?.secret?.wipe()
    }

    public companion object {
        /** A new entry's id: a random UUID's 16 bytes in base64, as the XML writes it (Mac `Entry.newID`). */
        public fun newId(): String {
            val uuid = UUID.randomUUID()
            val bytes = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
            return Base64.getEncoder().encodeToString(bytes.array())
        }
    }
}
