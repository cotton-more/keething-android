package dev.keething.vault.probe

import dev.keething.domain.vault.EntryFieldTypes
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import java.io.File
import org.w3c.dom.Element

/** The field types' key under the apps' former name, written before 2026-10-21. */
private const val FORMER_KEY = "KeePassBar.FieldTypes"

/**
 * Renames the field types' `CustomData` item from the apps' former name to [EntryFieldTypes.CUSTOM_DATA_KEY] in
 * every entry, history item and the recycle bin, into a NEW file; the database given is only read. Prints
 * counts, never a value, a title or a name.
 *
 * Usage: `tools/rename-field-types.sh <database.kdbx> <new.kdbx>` (asks for the password).
 * The new file is checked before it is called done: our reader opens it with as many entries, KeePassXC's
 * export of it is the original's with only the key renamed, and the former key is nowhere in it.
 */
fun main(arguments: Array<String>) {
    if (arguments.size != 2) fail("usage: RenameFieldTypes <database.kdbx> <new.kdbx>")
    val input = File(arguments[0])
    val output = File(arguments[1])
    if (!input.isFile) fail("No database at $input")
    if (output.exists()) fail("$output exists: give a new file")
    val password = readPasswordLine()
    val key = DatabaseKey.of(password)

    val editable = KdbxReader.openForEditing(input.readBytes(), key)
    val renamed = editable.renameEntryCustomData(FORMER_KEY, EntryFieldTypes.CUSTOM_DATA_KEY)
    println("Renamed: ${renamed.entries} entries, ${renamed.historyItems} history items (${renamed.dropped} had both keys: the new one kept)")
    if (renamed.entries + renamed.historyItems == 0) {
        editable.wipe()
        println("RESULT: nothing to rename, no file written")
        return
    }
    val written = editable.write(key)
    editable.wipe()
    written.binaries.forEach { it.wipe() }
    output.writeBytes(written.file)

    val opened = { file: File -> (VaultOpener().open(FileStore(file), DatabaseKey.of(password.copy())) as? OpenResult.Opened)?.opened?.vault }
    val before = opened(input) ?: fail("RESULT: the database no longer opens here")
    val after = opened(output) ?: fail("RESULT: the new file does not open here")
    val counts = { v: dev.keething.domain.vault.Vault -> listOf(v.entries.size, v.recycleBin.size, v.groups.size) }
    if (counts(before) != counts(after)) fail("RESULT: entries, bin or groups differ: ${counts(before)} vs ${counts(after)}")
    println("Opens:   ${after.entries.size} entries, ${after.recycleBin.size} in the bin, ${after.groups.size} groups, as before")
    before.wipe()
    after.wipe()

    val original = KeePassXc.export(input, password) ?: fail("KeePassXC cannot export the database")
    val again = KeePassXc.export(input, password) ?: fail("KeePassXC cannot export the database")
    val result = KeePassXc.export(output, password) ?: fail("RESULT: KeePassXC cannot open the new file")
    val noise = differences(original, again).toSet()
    renameInExport(original)
    val diff = differences(original, result).filterNot { it in noise }
    if (diff.isNotEmpty()) {
        println("The original with the key renamed vs the new file:")
        diff.take(12).forEach { println("  $it") }
        fail("RESULT: they differ in more than the key")
    }
    if (result.hasKey(FORMER_KEY)) fail("RESULT: the former key is still in the new file")
    println("KeePassXC: the new file is the original with only the key renamed")
    password.wipe()
    println("RESULT: $output is ready; replace the database with it")
}

/** What the rename should have done, done to KeePassXC's export of the original. */
private fun renameInExport(element: Element) {
    if (element.tagName == "Entry") {
        element.child("CustomData")?.let { data ->
            val items = data.children().filter { it.tagName == "Item" }
            val old = items.firstOrNull { it.child("Key")?.textContent == FORMER_KEY }
            if (old != null) {
                if (items.any { it.child("Key")?.textContent == EntryFieldTypes.CUSTOM_DATA_KEY }) data.removeChild(old) else old.child("Key")!!.textContent = EntryFieldTypes.CUSTOM_DATA_KEY
                KeePassXc.sortCustomData(data)
            }
        }
    }
    element.children().forEach(::renameInExport)
}

private fun Element.hasKey(key: String): Boolean =
    (tagName == "Key" && textContent == key) || children().any { it.hasKey(key) }
