package dev.keething.sharing.payload

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.TotpChange
import dev.keething.totp.TotpSetup

/** A part of an entry the sender may leave out (SPEC §10); the title, tags and expiry always go. */
public sealed interface EntryPart {
    public data object Username : EntryPart

    public data object Password : EntryPart

    public data object Website : EntryPart

    public data object Notes : EntryPart

    public data class Field(val name: String) : EntryPart

    public data object OneTimeCode : EntryPart
}

/** What of an entry goes into an answer. */
public object Sending {
    /**
     * Left out unless the sender turns them on: the one-time code, and custom fields KeePass keeps a code's secret in
     * (`TimeOtp-…`, `HmacOtp-…`, which the app does not read as a code): they hand over the sign-in's second step too.
     */
    public fun leftOutByDefault(entry: Entry): Set<EntryPart> =
        parts(entry).filter(::isSecondStep).toSet()

    /** The one-time code, or a field that holds a code's secret: sending it shares the second step of the sign-in. */
    public fun isSecondStep(part: EntryPart): Boolean = when (part) {
        EntryPart.OneTimeCode -> true
        is EntryPart.Field -> CODE_FIELD_PREFIXES.any { part.name.startsWith(it, ignoreCase = true) }
        else -> false
    }

    private val CODE_FIELD_PREFIXES = listOf("TimeOtp-", "HmacOtp-")

    /** The parts [entry] has, those with a value, in this order: the standard fields, the custom ones, the code. */
    public fun parts(entry: Entry): List<EntryPart> = buildList {
        if (entry.username.isNotEmpty()) add(EntryPart.Username)
        if (!entry.password.isEmpty()) add(EntryPart.Password)
        if (entry.url.isNotEmpty()) add(EntryPart.Website)
        if (entry.notes.isNotEmpty()) add(EntryPart.Notes)
        entry.customFields.forEach { add(EntryPart.Field(it.name)) }
        if (entry.totp != null) add(EntryPart.OneTimeCode)
    }

    /** [entry] as an answer carries it, without [leftOut]; its secrets are copies, the caller's to wipe. */
    public fun shared(entry: Entry, leftOut: Set<EntryPart>): SharedEntry {
        fun keeps(part: EntryPart) = part !in leftOut
        val fields = entry.customFields.filter { keeps(EntryPart.Field(it.name)) }.map { it.copy(value = it.value.copied()) }
        return SharedEntry(
            title = entry.title,
            username = if (keeps(EntryPart.Username)) entry.username else "",
            password = if (keeps(EntryPart.Password)) entry.password.copy() else Secret.empty(),
            url = if (keeps(EntryPart.Website)) entry.url else "",
            notes = if (keeps(EntryPart.Notes)) entry.notes else "",
            customFields = fields,
            fieldTypes = entry.fieldTypes.filterKeys { name -> fields.any { it.name == name } },
            tags = entry.tags,
            expires = entry.expiryTime?.takeIf { entry.expires },
            totp = entry.totp?.takeIf { keeps(EntryPart.OneTimeCode) }?.let { it.copy(secret = it.secret.copy()) },
        )
    }

    private fun FieldValue.copied(): FieldValue = if (this is FieldValue.Protected) FieldValue.Protected(secret.copy()) else this
}

/** A received entry as a new one in the database. */
public object Receiving {
    /**
     * The tag every received entry gets besides the sender's (SPEC §10): who made an answer cannot be proven, so what
     * came from outside is easy to find (`#received`) and to look at again; taking the tag off says it was.
     */
    public const val TAG: String = "received"

    /**
     * A new entry's fields for [entry] (SPEC §10): everything it carries, [TAG] added, its one-time code written as
     * KeePassXC writes it. The secrets are copies: wipe the changes once saved.
     */
    public fun changes(entry: SharedEntry): EntryChanges = EntryChanges(
        title = entry.title,
        username = entry.username,
        password = entry.password.copy(),
        url = entry.url,
        notes = entry.notes,
        customFields = entry.customFields.map { field ->
            val value = field.value
            if (value is FieldValue.Protected) CustomField(field.name, FieldValue.Protected(value.secret.copy())) else field
        },
        fieldTypes = entry.fieldTypes,
        tags = if (entry.tags.any { it.trim().equals(TAG, ignoreCase = true) }) entry.tags else entry.tags + TAG,
        expiry = entry.expires?.let { EntryChanges.Expiry(expires = true, time = it) },
        totp = entry.totp?.let { code ->
            val config = TotpConfig(code.secret.copy(), code.digits, code.period, code.algorithm)
            TotpChange.Set(config, TotpSetup.otpauthUri(config, entry.title, entry.username))
        },
    )
}
