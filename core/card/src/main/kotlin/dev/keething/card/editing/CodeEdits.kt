package dev.keething.card.editing

import dev.keething.card.CardText
import dev.keething.domain.secret.Secret
import dev.keething.domain.text.UserText
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.TotpChange
import dev.keething.search.SearchNormalizer
import dev.keething.totp.TotpLabel
import dev.keething.totp.TotpParser
import dev.keething.totp.TotpSetup
import dev.keething.totp.TotpSetupException

/** A code read from what the user gave: the code, and whom a link says it is for (empty for a bare secret). */
public sealed interface ReadCode {
    /** [config]'s key is the caller's: wipe it once used. */
    public data class Code(val config: TotpConfig, val label: TotpLabel) : ReadCode {
        override fun toString(): String = "Code(•••, $label)" // not user text
    }

    public data class Refused(val problem: UserText) : ReadCode
}

/**
 * What the Codes view changes in an entry, one code at a time (keething-spec app.codes-view AC4–AC7): a code is the
 * entry's own ([field] null) or a further one, the custom field ([field]) holding its link. Every change carries
 * secrets of its own, so wiping it after the save leaves the vault's alone.
 */
public object CodeEdits {
    /**
     * A code typed or pasted (a secret or an `otpauth://` link), as the editor takes one (edit.one-time-code AC1–AC3),
     * with the link's issuer and account.
     */
    public fun read(text: String): ReadCode {
        val config = try {
            TotpSetup.parse(text)
        } catch (problem: TotpSetupException) {
            return ReadCode.Refused(EntryEditor.codeProblem(problem))
        }
        return ReadCode.Code(config, TotpParser.label(text) ?: TotpLabel("", ""))
    }

    /** The QR codes of an image or the camera (AC4): the first that is a code; none, the first one's problem. */
    public fun read(texts: List<String>): ReadCode {
        if (texts.isEmpty()) return ReadCode.Refused(EntryEditor.NO_QR_CODE)
        val code = texts.firstOrNull { text -> TotpSetup.parseOrNull(text)?.also { it.secret.wipe() } != null }
        return read(code ?: texts.first())
    }

    /**
     * Why a further code cannot be named [name] (AC4), as the editor says it of any field; null when it can. Its own
     * name is fine.
     */
    public fun nameProblem(entry: Entry, field: String, name: String): UserText? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return UserText.of(CardText.CODE_NO_NAME)
        if (trimmed in EntryChanges.RESERVED_FIELD_NAMES) return UserText.of(CardText.NAME_RESERVED, trimmed)
        return if (trimmed != field && entry.customFields.any { it.name == trimmed }) UserText.of(CardText.NAME_TAKEN) else null
    }

    /**
     * Edit code (AC4): the code becomes [config] (its secret, digits, period and algorithm), a further code's field is
     * renamed [name] (its type follows it), and nothing else changes. The entry's own is saved as the editor saves it,
     * in the entry's format; a further one's link replaces its field's value, the field's protection kept.
     */
    public fun edit(entry: Entry, field: String?, config: TotpConfig, name: String? = null): EntryChanges {
        val link = TotpSetup.otpauthUri(config, entry.title, entry.username)
        if (field == null) return EntryChanges(totp = TotpChange.Set(config.copy(secret = config.secret.copy()), link))
        val renamed = name?.trim()?.takeIf { it.isNotEmpty() } ?: field
        val fields = entry.customFields.map { custom ->
            if (custom.name != field) return@map copied(custom)
            CustomField(renamed, if (custom.isProtected) FieldValue.Protected(Secret.of(link)) else FieldValue.Plain(link))
        }
        val types = entry.fieldTypes.takeIf { renamed != field && field in it }?.mapKeys { (key, _) -> if (key == field) renamed else key }
        return EntryChanges(customFields = fields, fieldTypes = types)
    }

    /**
     * Delete from the entry (AC5): the fields the entry's own code is read from, or the further code's one field (and its
     * type); no further code takes the own code's place, and nothing else changes.
     */
    public fun delete(entry: Entry, field: String?): EntryChanges {
        if (field == null) return EntryChanges(totp = TotpChange.Remove)
        return EntryChanges(
            customFields = entry.customFields.filter { it.name != field }.map(::copied),
            fieldTypes = entry.fieldTypes.takeIf { field in it }?.minus(field),
        )
    }

    /**
     * Undo of [delete] (AC5): the code put back into [current] as it was in [before] — the own code's fields as the file
     * held them, a further code's field (and its type) where it stood among the fields.
     */
    public fun restore(before: Entry, current: Entry, field: String?): EntryChanges {
        if (field == null) return EntryChanges(totp = TotpChange.Fields(before.codeFields.map(::copied)))
        val index = before.customFields.indexOfFirst { it.name == field }
        if (index < 0) return EntryChanges()
        val fields = current.customFields.filter { it.name != field }.map(::copied).toMutableList()
        // Before the field that followed it, else after the one before it, else last.
        val next = before.customFields.drop(index + 1).firstNotNullOfOrNull { after -> fields.indexOfFirst { it.name == after.name }.takeIf { it >= 0 } }
        val previous = before.customFields.take(index).reversed().firstNotNullOfOrNull { ahead -> fields.indexOfFirst { it.name == ahead.name }.takeIf { it >= 0 } }
        fields.add(next ?: previous?.plus(1) ?: fields.size, copied(before.customFields[index]))
        val types = before.fieldTypes[field]?.let { type -> current.fieldTypes + (field to type) }
        return EntryChanges(customFields = fields, fieldTypes = types)
    }

    /**
     * Whether the code is all the entry holds but its title (AC6): no username, password, URL, notes, attachments, nor a
     * custom field or a code besides it. Deleting it then moves the entry to the recycle bin.
     */
    public fun isAllItHolds(entry: Entry, field: String?): Boolean {
        val bare = entry.username.isEmpty() && entry.password.isEmpty() && entry.url.isEmpty() && entry.notes.isEmpty() && entry.attachments.isEmpty()
        if (!bare) return false
        return if (field == null) entry.customFields.isEmpty() else !entry.hasCodeFields && entry.customFields.map { it.name } == listOf(field)
    }

    /** The entry of [entries] titled [title], compared as the search folds text (AC7); the first in file order. */
    public fun sameTitle(entries: List<Entry>, title: String): Entry? {
        val folded = SearchNormalizer.normalize(title.trim()).takeIf { it.isNotEmpty() } ?: return null
        return entries.firstOrNull { SearchNormalizer.normalize(it.title.trim()) == folded }
    }

    /**
     * A code added to an existing entry (AC7, edit.one-time-code AC5): its own when it has no code fields, else a protected
     * field "One-time code N" holding its link (code fields that give no code are never overwritten). The field's name,
     * null for the own code.
     */
    public fun addTo(entry: Entry, config: TotpConfig): Pair<EntryChanges, String?> {
        val link = TotpSetup.otpauthUri(config, entry.title, entry.username)
        if (entry.totp == null && !entry.hasCodeFields) return EntryChanges(totp = TotpChange.Set(config.copy(secret = config.secret.copy()), link)) to null
        val taken = entry.customFields.mapTo(HashSet()) { it.name.trim() }
        val name = generateSequence(2) { it + 1 }.map { "${EntryEditor.ANOTHER_CODE} $it" }.first { it !in taken }
        val fields = entry.customFields.map(::copied) + CustomField(name, FieldValue.Protected(Secret.of(link)))
        return EntryChanges(customFields = fields) to name
    }

    /** A new entry for the code (AC7): the title, the username and the code. */
    public fun newEntry(title: String, username: String, config: TotpConfig): EntryChanges {
        val link = TotpSetup.otpauthUri(config, title.trim(), username.trim())
        return EntryChanges(title = title.trim(), username = username.trim(), totp = TotpChange.Set(config.copy(secret = config.secret.copy()), link))
    }

    private fun copied(field: CustomField): CustomField = when (val value = field.value) {
        is FieldValue.Protected -> CustomField(field.name, FieldValue.Protected(value.secret.copy()))
        is FieldValue.Plain -> field
    }

    private val CustomField.isProtected: Boolean get() = value is FieldValue.Protected
}
