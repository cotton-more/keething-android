package dev.keething.domain.vault

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.totp.TotpFormat
import java.time.Instant

/**
 * What an edit of an entry changes (SPEC §3.3; Mac `EntryChanges`); null leaves a part as it is. Applied to the
 * database file itself, so everything an edit does not mention (other fields, attachments, icons, history,
 * other apps' data) stays untouched.
 */
public data class EntryChanges(
    val title: String? = null,
    val username: String? = null,
    val password: Secret? = null,
    val url: String? = null,
    val notes: String? = null,
    /**
     * Notes protected in the file or not (SPEC §6.4). null keeps the value's own protection, or gives a new one
     * `Meta/MemoryProtection`'s.
     */
    val notesProtected: Boolean? = null,
    /**
     * The custom fields as they should be afterwards, in order: those missing from it are removed, new ones
     * added. The one-time code's fields ([RESERVED_FIELD_NAMES]) are never touched through it.
     */
    val customFields: List<CustomField>? = null,
    /** The field types as they should be afterwards (Mac SPEC §3.12); empty removes them. */
    val fieldTypes: Map<String, String>? = null,
    /** The entry's kind ([EntryType], SPEC §6.4) set or its item removed. */
    val entryType: EntryTypeChange? = null,
    val tags: List<String>? = null,
    val expiry: Expiry? = null,
    /**
     * The attachments as they should be afterwards, in order (keething-spec edit.attachments; Mac SPEC §3.18): kept ones,
     * perhaps renamed, and new ones; those missing from it are removed.
     */
    val attachments: List<AttachmentChange>? = null,
    /**
     * The one-time code (Mac SPEC §3.23): set (every code field removed, then written in the entry's format or the
     * database's, [TotpFormat.forNewCode]), removed, or its fields put back as they were ([TotpChange.Fields]).
     */
    val totp: TotpChange? = null,
    /**
     * Another group for an existing entry (keething-spec groups.entry-group): it moves there as KeePassXC moves an
     * entry, without a history snapshot of its own. Not used for a new entry ([VaultEdit.Create] names its group).
     */
    val group: GroupDestination? = null,
) {
    public data class Expiry(val expires: Boolean, val time: Instant?)

    val isEmpty: Boolean get() = this == EntryChanges()

    /** The changes to the entry's own data, without a move. */
    val fieldChanges: EntryChanges get() = copy(group = null)

    /** Overwrites the secrets it carries (once the save is done with them). */
    public fun wipe() {
        password?.wipe()
        customFields?.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() }
        (totp as? TotpChange.Set)?.config?.secret?.wipe()
        (totp as? TotpChange.Fields)?.fields?.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() }
        attachments?.forEach { (it as? AttachmentChange.Add)?.content?.wipe() }
    }

    public companion object {
        /** The standard fields' keys in the file. */
        public val STANDARD_KEYS: List<String> = listOf("Title", "UserName", "Password", "URL", "Notes")

        /** The fields a one-time code is kept in, in every format ([TotpFormat]). */
        public val TOTP_FIELD_NAMES: Set<String> = TotpFormat.FIELD_NAMES

        /** Keys that are not custom fields: the standard ones and the one-time code's. */
        public val RESERVED_FIELD_NAMES: Set<String> = STANDARD_KEYS.toSet() + TOTP_FIELD_NAMES
    }
}

/** One attachment of an entry after an edit (Mac `AttachmentChange`; keething-spec edit.attachments). */
public sealed interface AttachmentChange {
    /** The name it has afterwards. */
    public val name: String

    /** The attachment called [from] stays, called [name] (the same: unchanged). */
    public data class Keep(val from: String, override val name: String) : AttachmentChange

    /**
     * A new attachment. [content] is wiped with the change ([EntryChanges.wipe]); the file takes a copy of its own. It is a
     * secret although the file it came from lies unencrypted on the phone: once in the database it is one of its attachments.
     */
    public data class Add(override val name: String, val content: Secret) : AttachmentChange {
        override fun toString(): String = "Add($name, ${content.size} bytes)"
    }
}

/** A one-time code set or removed (Mac `TOTPChange`). */
public sealed interface TotpChange {
    /** [config] is the code (the save's check compares it); [uri] is what goes into `otp` when it is written as a link. */
    public data class Set(val config: TotpConfig, val uri: String) : TotpChange {
        // The link holds the key: never in a log.
        override fun toString(): String = "Set(•••)"
    }

    public data object Remove : TotpChange

    /**
     * Every code field removed, then exactly [fields] written ([Entry.codeFields] as they were): the undo of a removed
     * code (keething-spec app.codes-view AC5). Each is a code field's ([TotpFormat.FIELD_NAMES]); others are ignored.
     */
    public data class Fields(val fields: List<CustomField>) : TotpChange {
        override fun toString(): String = "Fields(${fields.map { it.name }})"
    }
}
