package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
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

    /**
     * Applies [edit] to the tree as KeePassXC would (SPEC §3.3); throws an [EditException] when it cannot. New groups
     * meet the file as it is now ([GroupLifecycle.resolving]): one another app made since the read is taken, not made
     * twice.
     */
    public fun apply(edit: VaultEdit, now: Instant): AppliedEdit = when (edit) {
        is VaultEdit.Update -> {
            val location = EntryLifecycle.locate(edit.entryId, root) ?: throw EditException.EntryNotFound()
            EntryEditing.apply(edit.changes, location.entry, root, now)
            val wanted = edit.changes.group
            if (wanted == null) AppliedEdit(expectedGroupId = null) else move(edit.entryId, EntryLifecycle.uuidText(location.group), wanted, now)
        }
        is VaultEdit.Create -> {
            if (EntryLifecycle.locate(edit.entryId, root) != null) throw EditException.EntryExists()
            val destination = GroupLifecycle.resolving(edit.group, root)
            GroupLifecycle.create(destination.newGroups, destination.groupId, root, now)
            val entry = EntryLifecycle.create(edit.entryId, destination.finalGroupId, edit.fields, root, now)
            AppliedEdit(
                expectedGroupId = destination.finalGroupId,
                createdGroupIds = destination.newGroups.map(NewGroup::id),
                customIconId = entry.first("CustomIconUUID")?.text?.trim(),
                destination = destination,
            )
        }
        is VaultEdit.MoveToRecycleBin -> AppliedEdit(expectedGroupId = EntryLifecycle.moveToRecycleBin(edit.entryId, root, now))
        is VaultEdit.Restore -> EntryLifecycle.restore(edit.entryId, root, now).let {
            AppliedEdit(expectedGroupId = it.destinationId, createdGroupIds = it.createdGroupIds)
        }
        is VaultEdit.RenameGroup -> {
            GroupLifecycle.rename(edit.groupId, edit.name, root, now)
            AppliedEdit(expectedGroupId = null)
        }
        is VaultEdit.CreateGroup -> {
            GroupLifecycle.createNamed(edit.group, edit.parentId, root, now)
            AppliedEdit(expectedGroupId = null, createdGroupIds = listOf(edit.group.id))
        }
        is VaultEdit.MoveGroup -> {
            GroupLifecycle.move(edit.groupId, edit.intoGroupId, root, now)
            AppliedEdit(expectedGroupId = null)
        }
        is VaultEdit.DeleteEmptyGroup -> {
            GroupLifecycle.deleteEmpty(edit.groupId, root, now)
            AppliedEdit(expectedGroupId = null, deletedGroupIds = listOf(edit.groupId))
        }
    }

    /**
     * The entry (just edited, or not) into [wanted]'s group, made first where it is new, as KeePassXC moves an entry
     * (keething-spec groups.entry-group AC3); the group it [left] and each parent the move leaves empty go for good.
     */
    private fun move(entryId: String, left: String, wanted: GroupDestination, now: Instant): AppliedEdit {
        val destination = GroupLifecycle.resolving(wanted, root)
        GroupLifecycle.create(destination.newGroups, destination.groupId, root, now)
        EntryLifecycle.moveEntry(entryId, destination.finalGroupId, root, now)
        val emptied = if (left != destination.finalGroupId) GroupLifecycle.removeEmpty(left, root, now) else emptyList()
        return AppliedEdit(
            expectedGroupId = destination.finalGroupId,
            createdGroupIds = destination.newGroups.map(NewGroup::id),
            destination = destination,
            deletedGroupIds = emptied,
        )
    }

    /** `Meta/MemoryProtection`'s `ProtectNotes`: the protection Notes take where an entry had none (SPEC §6.4). */
    public val protectsNotes: Boolean get() = EntryEditing.memoryProtection(root).getValue("Notes")

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

/**
 * What an applied edit should leave, for the check of the written file. A group renamed or moved is the edit's own
 * ([VaultEdit.RenameGroup], [VaultEdit.MoveGroup]): what the check needs of it is there.
 */
public data class AppliedEdit(
    /** The group the entry should be in afterwards; null when it stays where it was, and for a group's edit. */
    val expectedGroupId: String?,
    /** Groups made, outermost first: on a restore's way back, the levels of [destination] made, a new empty group. */
    val createdGroupIds: List<String> = emptyList(),
    /** The custom icon a new entry took from its group. */
    val customIconId: String? = null,
    /**
     * Where a new or moved entry went, as the file had it ([GroupLifecycle.resolving]): a level another app made
     * since the read is an existing group here, and only the levels still missing were made.
     */
    val destination: GroupDestination? = null,
    /** Groups deleted for good: those a move left empty (innermost first), or the empty group deleted. */
    val deletedGroupIds: List<String> = emptyList(),
)

/** A written file and the attachments in it, in order (this database's own: wiped with it). */
public class WrittenDatabase(public val file: ByteArray, public val binaries: List<Secret>)
