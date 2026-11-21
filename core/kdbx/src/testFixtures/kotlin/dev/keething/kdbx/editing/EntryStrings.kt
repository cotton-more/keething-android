package dev.keething.kdbx.editing

import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader

/** An entry's fields as the file holds them: for tests that a save leaves some of them as they were. */
public object EntryStrings {
    /** The fields of the entry [uuid] in [file] whose names pass [name], in file order, as `key=value` (a protected one with `*`). */
    public fun of(file: ByteArray, key: DatabaseKey, uuid: String, name: (String) -> Boolean): List<String> {
        val editable = KdbxReader.openForEditing(file, key)
        try {
            return EntryEditing.entry(uuid, editable.root)!!.all("String").mapNotNull { string ->
                val field = string.first("Key")?.text ?: ""
                val value = string.first("Value")
                if (!name(field) || value == null) null else value.secret?.let { "$field=${it.reveal()}*" } ?: "$field=${value.text}"
            }
        } finally {
            editable.wipe()
        }
    }
}
