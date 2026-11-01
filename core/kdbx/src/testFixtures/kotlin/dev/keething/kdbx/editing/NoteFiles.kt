package dev.keething.kdbx.editing

import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XmlTree
import dev.keething.kdbx.writer.KdbxWriter

/** Files for the save's tests of notes (keething-spec `types.note`): the database's own Notes protection, an entry without Notes. */
public object NoteFiles {
    /** [file] with `Meta/MemoryProtection/ProtectNotes` set to [on]. */
    public fun protectingNotes(file: ByteArray, key: DatabaseKey, on: Boolean = true): ByteArray = changed(file, key) { root ->
        val meta = root.first("Meta")!!
        val protection = meta.first("MemoryProtection") ?: XElement("MemoryProtection").also(meta::append)
        val notes = protection.first("ProtectNotes") ?: XElement("ProtectNotes").also(protection::append)
        notes.text = if (on) "True" else "False"
    }

    /** [file] with the entry [uuid]'s `Notes` string taken out, as another app may leave an entry. */
    public fun withoutNotes(file: ByteArray, key: DatabaseKey, uuid: String): ByteArray = changed(file, key) { root ->
        val entry = EntryEditing.entry(uuid, root)!!
        entry.all("String").filter { it.first("Key")?.text == "Notes" }.forEach {
            it.wipeSecrets()
            entry.remove(it)
        }
    }

    /** The entry [uuid] of [file] as XML, every protected value written `Protected="True"` and empty. */
    public fun entryXml(file: ByteArray, key: DatabaseKey, uuid: String): String {
        val editable = KdbxReader.openForEditing(file, key)
        try {
            return XmlTree.withoutSecrets(EntryEditing.entry(uuid, editable.root)!!).decodeToString()
        } finally {
            editable.wipe()
        }
    }

    private fun changed(file: ByteArray, key: DatabaseKey, change: (XElement) -> Unit): ByteArray {
        val editable = KdbxReader.openForEditing(file, key)
        try {
            change(editable.root)
            return KdbxWriter.write(editable.root, editable.binaries, editable.binaryFlags, editable.template, key)
        } finally {
            editable.wipe()
        }
    }
}
