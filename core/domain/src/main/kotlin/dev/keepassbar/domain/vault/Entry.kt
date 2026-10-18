package dev.keepassbar.domain.vault

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.totp.TotpConfig
import java.time.Instant

/** An entry of the database, identified by its KeePass UUID. */
public data class Entry(
    /** KeePass entry UUID, base64 as in the XML. */
    val id: String,
    val title: String = "",
    val username: String = "",
    val password: Secret = Secret.empty(),
    val url: String = "",
    val notes: String = "",
    /** Group names from the top-level group down, without the database's root group. */
    val groupPath: List<String> = emptyList(),
    /** UUID of the group the entry is in ("" when not known). */
    val groupId: String = "",
    val tags: List<String> = emptyList(),
    /** Everything but the standard fields and the one-time code's fields, in file order. */
    val customFields: List<CustomField> = emptyList(),
    val totp: TotpConfig? = null,
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
    /** Title for display: the title, else the URL's host, else "(Untitled)". */
    val displayTitle: String get() = title.ifEmpty { urlHost ?: "(Untitled)" }

    /** The host of the entry's URL, for showing (title fallback, list subtitle). */
    val urlHost: String? get() = UrlHost.of(url)

    /**
     * The entry's web addresses as written: its URL, then its additional URLs. A protected value is never
     * read (fail closed).
     */
    val addresses: List<String>
        get() = (listOf(url) + customFields.filter { it.isAdditionalUrl }.mapNotNull { it.plainText })
            .filter { it.isNotEmpty() }

    public fun isExpired(at: Instant): Boolean = expires && expiryTime != null && expiryTime <= at

    /** Overwrites the entry's secrets (the lock). */
    public fun wipe() {
        password.wipe()
        customFields.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() }
        totp?.secret?.wipe()
    }
}
