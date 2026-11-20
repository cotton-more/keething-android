package dev.keething.sharing.payload

import dev.keething.card.CryptoWallet
import dev.keething.card.CryptoAddress
import dev.keething.card.FieldType
import dev.keething.card.FieldTypes
import dev.keething.card.OneTimeCodes
import dev.keething.card.WalletSecrets
import dev.keething.domain.secret.Secret
import dev.keething.domain.text.UserText
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryTypeChange
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
     * Left out unless the sender turns them on: the one-time code, custom fields that hold a code's secret all the same
     * (`HmacOtp-…`, KeePass's counter-based codes, which the app does not make, or `TimeOtp-…` under a name KeePass
     * does not read) or a code's `otpauth://` link (a second code, whether it reads or not): they hand over the
     * sign-in's second step too;
     * and a wallet's extended public key, which shows all of the wallet (SPEC §10.1).
     */
    public fun leftOutByDefault(entry: Entry): Set<EntryPart> =
        parts(entry).filter { isSecondStep(entry, it) || isWalletKey(entry, it) }.toSet()

    /** The one-time code, or a field that holds a code's secret or link: sending it shares the second step of the sign-in. */
    public fun isSecondStep(entry: Entry, part: EntryPart): Boolean = when (part) {
        EntryPart.OneTimeCode -> true
        is EntryPart.Field -> CODE_FIELD_PREFIXES.any { part.name.startsWith(it, ignoreCase = true) } ||
            entry.customFields.any { it.name == part.name && OneTimeCodes.holdsLink(it) }
        else -> false
    }

    /** A field typed as an extended public key: with it, whoever gets it sees every address and balance of the wallet. */
    public fun isWalletKey(entry: Entry, part: EntryPart): Boolean =
        part is EntryPart.Field && FieldTypes(entry).type(part.name) == FieldType.CRYPTO_XPUB

    private val CODE_FIELD_PREFIXES = listOf("TimeOtp-", "HmacOtp-") // not user text

    /**
     * The parts of [entry] that never go, whatever the sender turns on (SPEC §10.1): a wallet's recovery phrase, its
     * passphrase, a private key. Shown as locked rows with what they are; never among [parts].
     */
    public fun neverSent(entry: Entry): Map<EntryPart, WalletSecrets.Kind> = buildMap {
        WalletSecrets.password(entry)?.let { put(EntryPart.Password, it) }
        WalletSecrets.notes(entry)?.let { put(EntryPart.Notes, it) }
        WalletSecrets.fields(entry).forEach { (name, kind) -> put(EntryPart.Field(name), kind) }
    }

    /**
     * Why [entry]'s one-time code stays on this phone although it has code fields: they give no code here (Steam, a
     * counter-based link, a broken secret), the card's reason; shown as a locked row. Null otherwise.
     */
    public fun codeLeftBehind(entry: Entry): UserText? = OneTimeCodes.problem(entry)

    /** The parts [entry] has that may go, those with a value, in this order: the standard fields, the custom ones, the code. */
    public fun parts(entry: Entry): List<EntryPart> {
        val never = neverSent(entry).keys
        return buildList {
            if (entry.username.isNotEmpty()) add(EntryPart.Username)
            if (!entry.password.isEmpty()) add(EntryPart.Password)
            if (entry.url.isNotEmpty()) add(EntryPart.Website)
            if (entry.notes.isNotEmpty()) add(EntryPart.Notes)
            entry.customFields.forEach { add(EntryPart.Field(it.name)) }
            if (entry.totp != null) add(EntryPart.OneTimeCode)
        }.filter { it !in never }
    }

    /** [entry] as an answer carries it, without [leftOut] nor what is [neverSent]; its secrets are copies, the caller's to wipe. */
    public fun shared(entry: Entry, leftOut: Set<EntryPart>): SharedEntry {
        val never = neverSent(entry).keys
        fun keeps(part: EntryPart) = part !in leftOut && part !in never
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
            notesProtected = keeps(EntryPart.Notes) && entry.notesProtected,
            // The kind goes as the title does: a note left without its text is a note all the same.
            entryType = entry.entryType,
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
     * A received field that is a wallet's receiving address, typed or by its format (SPEC §10.1): in an answer, to be
     * checked with the sender another way before paying to it, since anyone who saw the request could have made it.
     */
    public fun isAddress(entry: SharedEntry, field: CustomField): Boolean =
        FieldTypes(entry.fieldTypes).type(field.name) == FieldType.CRYPTO_ADDRESS || field.plainText?.let(CryptoAddress::read) != null

    /**
     * A received field that is never copied, as on the card: a recovery phrase, its passphrase, a private key typed as
     * an extended key (an older phone may send one).
     */
    public fun isNeverCopied(entry: SharedEntry, field: CustomField): Boolean =
        CryptoWallet.isNeverCopied(field, FieldTypes(entry.fieldTypes).type(field.name))

    /**
     * A new entry's fields for [entry] (SPEC §10): everything it carries, [TAG] added, its one-time code written in the
     * format the database's codes use (KeePassXC's link when it has none). Notes sent protected are written protected,
     * others as the database protects new Notes; the kind as it was stored, one this version does not know too
     * (sharing.receiving AC9). The secrets are copies: wipe the changes once saved.
     */
    public fun changes(entry: SharedEntry): EntryChanges = EntryChanges(
        title = entry.title,
        username = entry.username,
        password = entry.password.copy(),
        url = entry.url,
        notes = entry.notes,
        notesProtected = true.takeIf { entry.notesProtected },
        customFields = entry.customFields.map { field ->
            val value = field.value
            if (value is FieldValue.Protected) CustomField(field.name, FieldValue.Protected(value.secret.copy())) else field
        },
        fieldTypes = entry.fieldTypes,
        entryType = entry.entryType?.let(EntryTypeChange::Stored),
        tags = if (entry.tags.any { it.trim().equals(TAG, ignoreCase = true) }) entry.tags else entry.tags + TAG,
        expiry = entry.expires?.let { EntryChanges.Expiry(expires = true, time = it) },
        totp = entry.totp?.let { code ->
            val config = TotpConfig(code.secret.copy(), code.digits, code.period, code.algorithm)
            TotpChange.Set(config, TotpSetup.otpauthUri(config, entry.title, entry.username))
        },
    )
}
