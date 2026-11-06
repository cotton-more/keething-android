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
 * The fictitious database of the card's app addresses (keething-spec card.app-address), written by the phone's own
 * core: one entry naming an installed app in its URL (Chrome), a missing one in a `KP2A_URL` field, an installed one in
 * a field of the user's own name, the same address in a field typed `text` (stays text) and a web address. Password
 * `pw`, as the fixtures'.
 *
 * Usage: `task app-address:database -- <new.kdbx>`.
 */
fun main(arguments: Array<String>) {
    if (arguments.size != 1) fail("usage: MakeAppAddressDatabase <new.kdbx>")
    val output = File(arguments[0])
    if (output.exists()) fail("$output exists: give a new file")
    val now = Instant.now()
    val kdf = KdbxFormat.Kdf.Argon2(KdbxFormat.Kdf.Argon2.Variant.ID, iterations = 2, memoryBytes = 64L shl 20, parallelism = 2)
    output.writeBytes(NewDatabase.file("App addresses", output.name, KdbxFormat.Cipher.AES256, kdf, key(), now))

    val store = FileStore(output)
    val saver = VaultSaver(store, NoAppBackups)
    val top = (VaultOpener().open(store, key()) as? OpenResult.Opened)?.opened?.vault?.groups?.single { it.isRoot }
        ?: fail("the new file does not open")
    val changes = EntryChanges(
        title = "App addresses", username = "alex@example.com", password = Secret.of("correct-horse-battery"),
        url = "androidapp://com.android.chrome",
        customFields = listOf(
            field("KP2A_URL_1", "androidapp://com.example.parking"),
            field("KP2A_URL_2", "https://www.example.com/login"),
            field("Store app", "androidapp://com.android.vending"),
            field("As text", "androidapp://com.android.chrome"),
        ),
        fieldTypes = mapOf("As text" to "text"),
    )
    val result = saver.save(VaultEdit.Create(Entry.newId(), GroupDestination(top.id), changes), key(), now)
    if (result !is SaveResult.Saved) fail("not saved: $result")
    result.opened.vault.wipe()

    val vault = (VaultOpener().open(store, key()) as? OpenResult.Opened)?.opened?.vault ?: fail("the file does not open")
    for (entry in vault.entries) println("${entry.title}: ${entry.url}; ${entry.customFields.joinToString { it.name }}; types ${entry.fieldTypes}")
    vault.wipe()
    println("RESULT: $output written (password $PASSWORD)")
}

private const val PASSWORD = "pw"

private fun field(name: String, value: String) = CustomField(name, FieldValue.Plain(value))

private fun key(): DatabaseKey = DatabaseKey.of(Secret.of(PASSWORD))

private object NoAppBackups : DatabaseBackups {
    override fun keep(database: ByteArray, fileName: String, at: Instant) {}
}
