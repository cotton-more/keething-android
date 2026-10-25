package dev.keething.card

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.FieldValue

/**
 * What of a wallet never leaves the phone, in an answer or an envelope (SPEC §10.1): whoever has it has the wallet. By
 * the field's type, and, failing closed, by the value where nothing says what it is: what the offer to type a field
 * ([TypeSuggestion]) calls a phrase, an extended private key, a WIF private key. Protected values are read here too.
 * Not caught: a phrase inside longer notes, an Ethereum private key (64 hex digits look like any other secret).
 */
public object WalletSecrets {
    public enum class Kind {
        /** A recovery phrase, typed or by its value or name. */
        PHRASE,

        /** A phrase's passphrase (the 25th word). */
        PASSPHRASE,

        /** An extended private key (`xprv…`) or a WIF private key. */
        PRIVATE_KEY,
    }

    /** The custom fields of [entry] that never go, by name, in the entry's order. */
    public fun fields(entry: Entry): Map<String, Kind> {
        val types = FieldTypes(entry)
        val found = linkedMapOf<String, Kind>()
        for (field in entry.customFields) {
            val kind = when (types.type(field.name)) {
                FieldType.CRYPTO_SEED -> Kind.PHRASE
                FieldType.CRYPTO_PASSPHRASE -> Kind.PASSPHRASE
                FieldType.CRYPTO_XPUB -> if (field.text()?.let(ExtendedKey::read)?.isPrivate == true) Kind.PRIVATE_KEY else null
                else -> null
            } ?: field.text()?.let(::inValue) ?: Kind.PHRASE.takeIf { isPhraseByName(field) }
            kind?.let { found[field.name] = it }
        }
        // Beside a phrase, what the offer would type as its passphrase.
        if (Kind.PHRASE in found.values) {
            entry.customFields.firstOrNull { it.name !in found && types.type(it.name) == null && TypeSuggestion.name(it) in TypeSuggestion.PASSPHRASE && !it.isEmpty }
                ?.let { found[it.name] = Kind.PASSPHRASE }
        }
        return found
    }

    /** What the entry's password is, when it never goes. */
    public fun password(entry: Entry): Kind? = entry.password.takeUnless(Secret::isEmpty)?.let { inValue(it.reveal()) }

    /** What the entry's notes, as a whole, are when they never go; a phrase among other lines is not caught. */
    public fun notes(entry: Entry): Kind? = entry.notes.takeIf { it.isNotBlank() }?.let(::inValue)

    /** A phrase by its check, an extended private key by its checksum, a WIF key. */
    private fun inValue(text: String): Kind? = when {
        SeedPhrase.read(text)?.passes == true -> Kind.PHRASE
        ExtendedKey.read(text)?.let { it.isPrivate && it.passes } == true -> Kind.PRIVATE_KEY
        WifKey.isKey(text) -> Kind.PRIVATE_KEY
        else -> null
    }

    /** Under a phrase's name: words of a list, or anything protected (as the offer to type reads it). */
    private fun isPhraseByName(field: CustomField): Boolean {
        if (TypeSuggestion.name(field) !in TypeSuggestion.SEED) return false
        return field.plainText?.let { SeedPhrase.read(it)?.isWords == true } ?: !field.isEmpty
    }

    private fun CustomField.text(): String? = when (val v = value) {
        is FieldValue.Plain -> v.text
        is FieldValue.Protected -> v.secret.reveal()
    }
}
