package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxTemplate
import dev.keething.kdbx.schema.KdbxSchema
import dev.keething.kdbx.schema.SchemaViolation
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XmlTree
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
    /** What KeePass's schema does not allow in the file as it was read: kept, never a reason to refuse a save. */
    private val schemaBefore: Set<SchemaViolation> = KdbxSchema.violations(root)

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
     * ones stored once (as KeePassXC does); every reference must still mean the same bytes. A `PreviousParentGroup`
     * out of the schema's order (an older Keething's) is moved to its place. Throws [EditException.BreaksFormat]
     * when the file would have a schema violation it did not have when read ([KdbxSchema]).
     */
    public fun write(key: DatabaseKey): WrittenDatabase {
        KdbxSchema.placePreviousParentGroups(root)
        val pool = AttachmentPool(binaries, binaryFlags)
        val referenced = pool.digests(root)
        pool.compact(root)
        if (pool.digests(root) != referenced) throw EditException.Malformed("attachments renumbered wrongly")
        val broken = KdbxSchema.added(schemaBefore, KdbxSchema.violations(root))
        if (broken.isNotEmpty()) throw EditException.BreaksFormat(broken.first().describe(more = broken.size - 1))
        return WrittenDatabase(KdbxWriter.write(root, pool.binaries, pool.flags, template, key), pool.binaries)
    }

    /** What KeePass's schema does not allow in the tree as it stands ([KdbxSchema]): for the probe. */
    public fun schemaViolations(): Set<SchemaViolation> = KdbxSchema.violations(root)

    /** The XML as it stands, every protected value left empty: for checking it against `KDBX_XML.xsd` (the probe). */
    public fun xmlWithoutSecrets(): ByteArray = XmlTree.withoutSecrets(root)

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
