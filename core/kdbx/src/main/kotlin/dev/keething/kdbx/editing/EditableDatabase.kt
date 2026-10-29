package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxTemplate
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.writer.KdbxWriter
import dev.keething.kdbx.xml.KdbxDocument
import java.time.Instant

/**
 * A database opened to be changed and written back (SPEC §3.3; Mac `KDBXReader.Editable`): the whole XML as a
 * tree that keeps everything, the attachments with their flags, and the format. [wipe] when done.
 */
public class EditableDatabase internal constructor(
    /**
     * The file as the reader sees it, read from the same decrypted XML as the tree: what the save's check
     * compares the written file with. A fault in the tree would show there as a difference.
     */
    public val document: KdbxDocument,
    internal val root: XElement,
    /** The inner header's attachments in file order; the entries' `Ref`s point into it. */
    public val binaries: List<Secret>,
    internal val binaryFlags: List<Byte>,
    internal val template: KdbxTemplate,
) {
    /** Applies [edit] to the tree as KeePassXC would (SPEC §3.3); throws an [EditException] when it cannot. */
    public fun apply(edit: VaultEdit, now: Instant): AppliedEdit = when (edit) {
        is VaultEdit.Update -> {
            val location = EntryLifecycle.locate(edit.entryId, root) ?: throw EditException.EntryNotFound()
            EntryEditing.apply(edit.changes, location.entry, root, now)
            AppliedEdit(expectedGroupId = null)
        }
        is VaultEdit.Create -> {
            val entry = EntryLifecycle.create(edit.entryId, edit.groupId, edit.fields, root, now)
            AppliedEdit(expectedGroupId = edit.groupId, customIconId = entry.first("CustomIconUUID")?.text?.trim())
        }
        is VaultEdit.MoveToRecycleBin -> AppliedEdit(expectedGroupId = EntryLifecycle.moveToRecycleBin(edit.entryId, root, now))
        is VaultEdit.Restore -> EntryLifecycle.restore(edit.entryId, root, now).let {
            AppliedEdit(expectedGroupId = it.destinationId, createdGroupIds = it.createdGroupIds)
        }
    }

    /** Renames the entries' `CustomData` item [from] to [to] everywhere, history included ([CustomDataRename]). */
    public fun renameEntryCustomData(from: String, to: String): CustomDataRenamed = CustomDataRename.rename(from, to, root)

    /**
     * Writes the file as the tree stands now. Attachments nothing refers to any more are dropped and identical
     * ones stored once (as KeePassXC does); every reference must still mean the same bytes.
     */
    public fun write(key: DatabaseKey): WrittenDatabase {
        val pool = AttachmentPool(binaries, binaryFlags)
        val referenced = pool.digests(root)
        pool.compact(root)
        if (pool.digests(root) != referenced) throw EditException.Malformed("attachments renumbered wrongly")
        return WrittenDatabase(KdbxWriter.write(root, pool.binaries, pool.flags, template, key), pool.binaries)
    }

    /** Overwrites every secret: the tree's, the document's and the attachments. */
    public fun wipe() {
        root.wipeSecrets()
        document.entries.forEach { entry -> entry.fields.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() } }
        binaries.forEach(Secret::wipe)
    }
}

/** What an applied edit should leave, for the check of the written file. */
public data class AppliedEdit(
    /** The group the entry should be in afterwards; null when it stays where it was. */
    val expectedGroupId: String?,
    /** Groups a restore made on the way (outermost first). */
    val createdGroupIds: List<String> = emptyList(),
    /** The custom icon a new entry took from its group. */
    val customIconId: String? = null,
)

/** A written file and the attachments in it, in order (this database's own: wiped with it). */
public class WrittenDatabase(public val file: ByteArray, public val binaries: List<Secret>)
