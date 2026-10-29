package dev.keething.kdbx.editing

import dev.keething.domain.vault.EntryChanges
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.writer.KdbxWriter

/** Files whose one-time codes are in another app's format (keething-spec `kdbx.otp-storage`): for the save's tests. */
public object CodeFiles {
    /**
     * [file] with every entry's code fields gone, then [fields] (after its other fields) on the entry [uuid]: the file
     * other apps leave when one entry has its code in, say, KeePass's own fields.
     */
    public fun withCodeFields(file: ByteArray, key: DatabaseKey, uuid: String, fields: List<Pair<String, String>>): ByteArray {
        val editable = KdbxReader.openForEditing(file, key)
        try {
            for (entry in entries(editable.root.first("Root")!!)) {
                entry.all("String").filter { it.first("Key")?.text in EntryChanges.TOTP_FIELD_NAMES }.forEach {
                    it.wipeSecrets()
                    entry.remove(it)
                }
            }
            val entry = EntryEditing.entry(uuid, editable.root)!!
            var previous = entry.all("String").lastOrNull() ?: entry.first("Times")
            for ((name, value) in fields) {
                val string = XElement("String").apply { append(XElement("Key", name)); append(XElement("Value", value)) }
                entry.insertAfter(string, previous)
                previous = string
            }
            return KdbxWriter.write(editable.root, editable.binaries, editable.binaryFlags, editable.template, key)
        } finally {
            editable.wipe()
        }
    }

    /** The code fields of the entry [uuid] in [file], in their order, as `key=value` (a protected one with `*`). */
    public fun codeFields(file: ByteArray, key: DatabaseKey, uuid: String): List<String> {
        val editable = KdbxReader.openForEditing(file, key)
        try {
            return EntryEditing.entry(uuid, editable.root)!!.all("String").mapNotNull { string ->
                val name = string.first("Key")?.text ?: ""
                val value = string.first("Value")
                if (name !in EntryChanges.TOTP_FIELD_NAMES || value == null) null else value.secret?.let { "$name=${it.reveal()}*" } ?: "$name=${value.text}"
            }
        } finally {
            editable.wipe()
        }
    }

    private fun entries(group: XElement): List<XElement> = group.all("Entry") + group.all("Group").flatMap(::entries)
}
