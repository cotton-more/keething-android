package dev.keething.vault.probe

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.creating.NewDatabase
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxFormat
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.saving.SaveResult
import dev.keething.vault.saving.VaultSaver
import java.io.File
import java.time.Instant

/**
 * The fictitious database of the wallet's never-copied checks (keething-spec types.crypto-wallet AC3, AC4, AC8, AC9,
 * types.crypto-checks AC9), written by the phone's own core: a wallet whose extended key is BIP-32's test vector 5 (a
 * private key behind `xpub`), a protected extended public key, the same key unprotected, a passphrase in an entry that
 * is no wallet, and an ordinary entry with a protected custom field. Password `pw`, as the fixtures'. Every value is a
 * published test vector or made up.
 *
 * Usage: `task wallet:database -- <new.kdbx>`. KeePassXC opening the result is checked before it is called done.
 */
fun main(arguments: Array<String>) {
    if (arguments.size != 1) fail("usage: MakeWalletDatabase <new.kdbx>")
    val output = File(arguments[0])
    if (output.exists()) fail("$output exists: give a new file")
    val now = Instant.now()
    val kdf = KdbxFormat.Kdf.Argon2(KdbxFormat.Kdf.Argon2.Variant.ID, iterations = 2, memoryBytes = 64L shl 20, parallelism = 2)
    output.writeBytes(NewDatabase.file("Wallets", output.name, KdbxFormat.Cipher.AES256, kdf, key(), now))

    val store = FileStore(output)
    val saver = VaultSaver(store, NoWalletBackups)
    val top = (VaultOpener().open(store, key()) as? OpenResult.Opened)?.opened?.vault?.groups?.single { it.isRoot }
        ?: fail("the new file does not open")
    val entries = listOf(
        EntryChanges(
            title = "1 Cold wallet (xpub hides a private key)",
            customFields = listOf(
                field("Seed", PHRASE, protected = true), field("Passphrase", "harbour-lamp-1987", protected = true),
                field("BTC address", "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"), field("Key", VECTOR_5), field("Path", "m/84'/0'/0'"),
            ),
            fieldTypes = mapOf(
                "Seed" to "crypto.seed", "Passphrase" to "crypto.passphrase", "BTC address" to "crypto.address",
                "Key" to "crypto.xpub", "Path" to "crypto.path",
            ),
        ),
        EntryChanges(
            title = "2 Watch-only, key protected",
            customFields = listOf(field("zpub", ZPUB, protected = true), field("BTC address", "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")),
            fieldTypes = mapOf("zpub" to "crypto.xpub", "BTC address" to "crypto.address"),
        ),
        EntryChanges(
            title = "3 Watch-only, key in clear (copies)",
            customFields = listOf(field("zpub", ZPUB), field("BTC address", "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")),
            fieldTypes = mapOf("zpub" to "crypto.xpub", "BTC address" to "crypto.address"),
        ),
        EntryChanges(
            title = "4 Passphrase alone (no wallet)",
            customFields = listOf(field("Wallet passphrase", "tidal-orbit-lantern"), field("Note", "the phrase is on the steel plate")),
            fieldTypes = mapOf("Wallet passphrase" to "crypto.passphrase"),
        ),
        EntryChanges(
            title = "5 Router admin (a protected field)", username = "admin", password = Secret.of("correct-horse-battery"),
            url = "http://192.168.1.1",
            customFields = listOf(field("Admin PIN", "4815", protected = true), field("Model", "Lighthouse AX3000")),
        ),
    )
    for (changes in entries) {
        val result = saver.save(VaultEdit.Create(Entry.newId(), GroupDestination(top.id), changes), key(), now)
        if (result !is SaveResult.Saved) fail("not saved: $result")
        result.opened.vault.wipe()
    }

    val vault = (VaultOpener().open(store, key()) as? OpenResult.Opened)?.opened?.vault ?: fail("the file does not open")
    for (entry in vault.entries.sortedBy { it.title }) {
        println("${entry.title}: ${entry.customFields.joinToString { "${it.name}${if (it.isProtected) " (protected)" else ""}" }}; types ${entry.fieldTypes}")
    }
    vault.wipe()

    KeePassXc.export(output, Secret.of(PASSWORD)) ?: fail("KeePassXC cannot open the file")
    println("KeePassXC: opens it")
    println("RESULT: $output written (password $PASSWORD)")
}

private const val PASSWORD = "pw"

/** BIP-39's test vector (trezor/python-mnemonic, English): a phrase whose checksum passes. */
private const val PHRASE = "legal winner thank year wave sausage worth useful legal winner thank yellow"

/** BIP-32's test vector 5, "pubkey version / prvkey mismatch": a private key written behind `xpub`. */
private const val VECTOR_5 = "xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6LBpB85b3D2yc8sfvZU521AAwdZafEz7mnzBBsz4wKY5fTtTQBm"

/** BIP-84's test vector: an account's extended public key. */
private const val ZPUB = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

private fun field(name: String, value: String, protected: Boolean = false) =
    CustomField(name, if (protected) FieldValue.Protected(Secret.of(value)) else FieldValue.Plain(value))

private fun key(): DatabaseKey = DatabaseKey.of(Secret.of(PASSWORD))

private object NoWalletBackups : DatabaseBackups {
    override fun keep(database: ByteArray, fileName: String, at: Instant) {}
}
