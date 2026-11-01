package dev.keething.vault.probe

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.EntryTypeChange
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
import org.w3c.dom.Element

/**
 * The fictitious database of the notes' phone checks (keething-spec plans/done/android-notes.md, `an/Notes.kdbx`), written
 * by the phone's own core: a protected note, an unprotected one, an entry with Notes only (a note by the rule, no
 * kind), an ordinary entry with a password and protected Notes, and one whose protected Notes are longer than the
 * card's seven lines. Password `pw`, as the fixtures'.
 *
 * Usage: `task notes:database -- <new.kdbx>`. KeePassXC's export of the result is checked before it is called done.
 */
fun main(arguments: Array<String>) {
    if (arguments.size != 1) fail("usage: MakeNotesDatabase <new.kdbx>")
    val output = File(arguments[0])
    if (output.exists()) fail("$output exists: give a new file")
    val now = Instant.now()
    val kdf = KdbxFormat.Kdf.Argon2(KdbxFormat.Kdf.Argon2.Variant.ID, iterations = 2, memoryBytes = 64L shl 20, parallelism = 2)
    output.writeBytes(NewDatabase.file("Notes", output.name, KdbxFormat.Cipher.AES256, kdf, key(), now))

    val store = FileStore(output)
    val saver = VaultSaver(store, NoBackups)
    val top = (VaultOpener().open(store, key()) as? OpenResult.Opened)?.opened?.vault?.groups?.single { it.isRoot }
        ?: fail("the new file does not open")
    val note = EntryTypeChange.Set(EntryType.NOTE)
    val entries = listOf(
        EntryChanges(
            title = "Films to watch", notes = "Dune: Part Two\nArrival\nBlade Runner 2049\nthe one with the sandworms (again)",
            notesProtected = true, entryType = note, tags = listOf("films"),
        ),
        EntryChanges(
            title = "Book club", notes = "November: The Left Hand of Darkness\nDecember: Exhalation\n\nBring the snacks list.",
            notesProtected = false, entryType = note,
        ),
        EntryChanges(title = "Imported recipe", notes = "Lentil soup\n200 g red lentils\n1 onion, 2 carrots\n30 minutes"),
        EntryChanges(
            title = "Router admin", username = "admin", password = Secret.of("correct-horse-battery"), url = "http://192.168.1.1",
            notes = "Guest Wi-Fi: Lighthouse-Guest\nGuest password: sea-glass-42\nReset button: back, left", notesProtected = true,
        ),
        EntryChanges(
            title = "Server rack", username = "ops", password = Secret.of("tidal-orbit-lantern"), url = "https://rack.example.net",
            notes = (1..12).joinToString("\n") { "Slot $it: " + listOf("switch", "NAS", "router", "UPS", "patch panel", "spare")[it % 6] },
            notesProtected = true,
        ),
    )
    for (changes in entries) {
        val result = saver.save(VaultEdit.Create(Entry.newId(), GroupDestination(top.id), changes), key(), now)
        if (result !is SaveResult.Saved) fail("not saved: $result")
        result.opened.vault.wipe()
    }

    val vault = (VaultOpener().open(store, key()) as? OpenResult.Opened)?.opened?.vault ?: fail("the file does not open")
    for (entry in vault.entries.sortedBy { it.title }) {
        println("${entry.title}: note ${entry.isNote}, kind ${entry.entryType}, Notes protected ${entry.notesProtected}")
    }
    vault.wipe()

    val export = KeePassXc.export(output, Secret.of(PASSWORD)) ?: fail("KeePassXC cannot open the file")
    val protectedNotes = export.getElementsByTagName("String").let { strings ->
        (0 until strings.length).map { strings.item(it) as Element }
            .filter { it.child("Key")?.textContent == "Notes" && it.child("Value")?.getAttribute("ProtectInMemory") == "True" }
            .size
    }
    if (protectedNotes != 3) fail("KeePassXC lists $protectedNotes protected Notes, not 3")
    println("KeePassXC: opens it, 3 Notes protected")
    println("RESULT: $output written (password $PASSWORD)")
}

private const val PASSWORD = "pw"

private fun key(): DatabaseKey = DatabaseKey.of(Secret.of(PASSWORD))

private object NoBackups : DatabaseBackups {
    override fun keep(database: ByteArray, fileName: String, at: Instant) {}
}
