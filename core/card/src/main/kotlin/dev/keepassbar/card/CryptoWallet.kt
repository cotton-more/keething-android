package dev.keepassbar.card

import dev.keepassbar.domain.vault.CustomField

/**
 * An entry with a field typed `crypto.seed`, `crypto.address` or `crypto.xpub`: its `crypto.*` fields as one section,
 * drawn as a wallet (SPEC §6.2). The recovery phrase, its passphrase and a private key typed as an extended key are
 * masked and never copied; the addresses, the extended public key and the path copy as any value.
 */
internal object CryptoSection : TypedSection {
    override fun make(context: CardContext): Pair<CardSection, Set<Int>>? {
        val seed = context.typed(FieldType.CRYPTO_SEED).firstOrNull()
        val addresses = context.typed(FieldType.CRYPTO_ADDRESS)
        val xpub = context.typed(FieldType.CRYPTO_XPUB).firstOrNull()
        if (seed == null && addresses.isEmpty() && xpub == null) return null
        val parts = context.parts { it.isCryptoPart }
        val (title, labels) = context.sectionLabels(parts)
        val labelOf = parts.zip(labels).associate { (part, label) -> part.index to label }
        val wallet = CryptoWallet.make(
            seed = seed,
            passphrase = context.typed(FieldType.CRYPTO_PASSPHRASE).firstOrNull(),
            addresses = addresses,
            xpub = xpub,
            path = context.typed(FieldType.CRYPTO_PATH).firstOrNull(),
            label = { labelOf.getValue(it).ifEmpty { "Address" } },
        )
        val rows = parts.zip(labels).map { (part, label) ->
            val row = context.row(part.index, part.value, label, ImportedFieldName.displayName(part.value.name))
            val type = context.interpreter.type(part.value)
            val privateKey = type == FieldType.CRYPTO_XPUB && part.value.plainText?.let(ExtendedKey::read)?.isPrivate == true
            if (type?.isNeverCopied == true || privateKey) row.copy(neverCopied = true).concealed() else row
        }
        return CardSection("crypto", CardSection.Kind.Crypto(wallet), title, rows) to parts.map { it.index }.toSet()
    }
}

/**
 * What the wallet's face shows (SPEC §6.2): the recovery phrase (read only when shown: [SeedPhrase]), its passphrase,
 * the unprotected receiving addresses with their network and check, the extended public key, the derivation path.
 */
public data class CryptoWallet(
    val seedRowId: String?,
    val passphraseRowId: String?,
    val addresses: List<Address>,
    val xpub: Key?,
    val path: Path?,
    /** A field typed `crypto.xpub` holds a private key (`xprv…`): it is masked and never copied, and the face says why. */
    val privateKeyRowId: String?,
) {
    /** A receiving address: [read] is null when its format is none known here. */
    public data class Address(val rowId: String, val label: String, val text: String, val read: CryptoAddress?)

    public data class Key(val rowId: String, val label: String, val text: String, val read: ExtendedKey?)

    public data class Path(val rowId: String, val text: String, val description: String?)

    /** The network, from the first address the face recognises, else the path's coin, else a key's prefix that says it. */
    val network: CryptoNetwork?
        get() = addresses.firstNotNullOfOrNull { it.read?.network }
            ?: path?.let { DerivationPath.network(it.text) }
            ?: xpub?.read?.takeIf { it.prefix != "xpub" }?.network

    /** The rows drawn on the face; the others are rows under it. */
    val faceRowIds: Set<String>
        get() = setOfNotNull(seedRowId, passphraseRowId, xpub?.rowId, path?.rowId) + addresses.map { it.rowId }

    internal companion object {
        fun make(
            seed: IndexedValue<CustomField>?, passphrase: IndexedValue<CustomField>?, addresses: List<IndexedValue<CustomField>>,
            xpub: IndexedValue<CustomField>?, path: IndexedValue<CustomField>?, label: (Int) -> String,
        ): CryptoWallet {
            val key = xpub?.value?.plainText?.trim()?.takeIf { it.isNotEmpty() }?.let { text -> Key(RowIds.custom(xpub.index), label(xpub.index), text, ExtendedKey.read(text)) }
            return CryptoWallet(
                seedRowId = seed?.takeIf { !it.value.isEmpty }?.let { RowIds.custom(it.index) },
                passphraseRowId = passphrase?.takeIf { !it.value.isEmpty }?.let { RowIds.custom(it.index) },
                // A protected address is a row of its own, masked: the face reads nothing protected but the phrase.
                addresses = addresses.mapNotNull { field ->
                    val text = field.value.plainText?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    Address(RowIds.custom(field.index), label(field.index), text, CryptoAddress.read(text))
                },
                xpub = key?.takeIf { it.read?.isPrivate != true },
                path = path?.value?.plainText?.trim()?.takeIf { it.isNotEmpty() }?.let { Path(RowIds.custom(path.index), it, DerivationPath.describe(it)) },
                privateKeyRowId = key?.takeIf { it.read?.isPrivate == true }?.rowId,
            )
        }
    }
}
