package dev.keething.kdbx.editing

import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.creating.NewDatabase
import dev.keething.kdbx.schema.KdbxSchema
import dev.keething.kdbx.tree.XElement
import java.time.Instant

/** Changes of the file's `Meta`: the database's own settings rather than an entry's or a group's. */
internal object MetaEditing {
    /**
     * The database's name and colour (keething-spec databases.rename-recolour AC1-AC3): a name with its
     * `DatabaseNameChanged`, a colour with `SettingsChanged`, as KeePass marks them; each only when asked for. An element
     * the file lacks goes where the schema has it.
     */
    fun nameAndColor(edit: VaultEdit.NameAndColor, root: XElement, now: Instant) {
        val meta = root.first("Meta") ?: throw EditException.Malformed("the file has no Meta")
        val iso = EntryLifecycle.usesIsoTimes(root)
        edit.name?.let { name ->
            set("DatabaseName", NewDatabase.databaseName(name), meta)
            set("DatabaseNameChanged", EntryLifecycle.time(now, iso), meta)
        }
        edit.color?.let { color ->
            set("Color", color, meta)
            set("SettingsChanged", EntryLifecycle.time(now, iso), meta)
        }
    }

    /**
     * A new master key (keething-spec databases.change-master-key AC4): `MasterKeyChanged` now, and a
     * `MasterKeyChangeForceOnce` that asked for it set back, as KeePass does once the key is changed.
     */
    fun masterKeyChanged(root: XElement, now: Instant) {
        val meta = root.first("Meta") ?: throw EditException.Malformed("the file has no Meta")
        set("MasterKeyChanged", EntryLifecycle.time(now, EntryLifecycle.usesIsoTimes(root)), meta)
        meta.first("MasterKeyChangeForceOnce")?.takeIf { it.text.trim().equals("true", ignoreCase = true) }?.text = "False"
    }

    private fun set(name: String, text: String, meta: XElement) {
        val existing = meta.first(name)
        if (existing != null) existing.text = text else KdbxSchema.insert(XElement(name, text), meta)
    }
}
