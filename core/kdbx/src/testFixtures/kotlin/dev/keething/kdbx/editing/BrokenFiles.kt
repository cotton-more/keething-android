package dev.keething.kdbx.editing

import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.writer.KdbxWriter

/** Files with what KeePass's schema does not allow, as other apps (or an older Keething) leave them: for the save's tests. */
public object BrokenFiles {
    /**
     * [file] with its root group given a custom icon that is not in the file (`RCustomIcon`): a new entry in that
     * group would take it. Returns the file and the group's id.
     */
    public fun rootGroupWithAMissingIcon(file: ByteArray, key: DatabaseKey): Pair<ByteArray, String> {
        val editable = KdbxReader.openForEditing(file, key)
        try {
            val top = editable.root.first("Root")!!.first("Group")!!
            top.insertAfter(XElement("CustomIconUUID", "AAECAwQFBgcICQoLDA0ODw=="), top.first("IconID"))
            return KdbxWriter.write(editable.root, editable.binaries, editable.binaryFlags, editable.template, key) to
                EntryLifecycle.uuidText(top)
        } finally {
            editable.wipe()
        }
    }
}
