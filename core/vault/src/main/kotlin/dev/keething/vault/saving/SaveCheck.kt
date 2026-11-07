package dev.keething.vault.saving

import dev.keething.domain.text.TextFolding
import dev.keething.domain.totp.TotpFormat
import dev.keething.domain.text.XmlText
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryTypeChange
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.TotpChange
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import dev.keething.kdbx.editing.AppliedEdit
import dev.keething.totp.TotpResolver
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The check before a written file replaces the old one (SPEC §3.3 step 5; port of the Mac `KDBXFileSaver.verify`):
 * read back as an unlock would read it. An entry's edit: every other entry exactly as before, the groups as before
 * but those it made and those a move left empty, and the changed entry exactly as asked (one deleted for good: gone). A change of the groups:
 * the groups as expected ([expectedGroups]), every entry as before but for its path, the recycle bin as before.
 * Groups are matched by id, and each group's parent is checked too, found by the file's order (a group's groups follow
 * it), so two groups of one name in one parent are each themselves.
 */
internal object SaveCheck {
    class Failed(val detail: String) : Exception(detail)

    /**
     * What the file says of Notes an edit writes where there were none (SPEC §6.4): [protected], the database's
     * `ProtectNotes`, for a new entry's and for those of the entry [missingIn] (an edited one that had no Notes).
     */
    class NewNotes(val protected: Boolean = false, val missingIn: String? = null)

    /**
     * [fileBefore] and [fileAfter]: every group of each file in file order, the recycle bin's own too, which the vaults'
     * groups leave out.
     */
    fun verify(
        before: Vault,
        after: Vault,
        edit: VaultEdit,
        applied: AppliedEdit,
        now: Instant,
        fileBefore: List<VaultGroup> = before.groups,
        fileAfter: List<VaultGroup> = after.groups,
        notes: NewNotes = NewNotes(),
    ) {
        when (edit) {
            is VaultEdit.EntryEdit -> verifyEntry(before, after, edit, applied, now, fileBefore, fileAfter, notes)
            is VaultEdit.GroupEdit -> verifyGroups(before, after, edit, fileBefore, fileAfter)
        }
        // What the save says it deleted (groups.entry-group AC7) is what went.
        val gone = before.groups.map { it.id }.toSet() - after.groups.map { it.id }.toSet()
        if (applied.deletedGroupIds.toSet() != gone || applied.deletedGroupIds.size != gone.size) fail("the groups deleted are not those reported")
    }

    /**
     * The check of a database written in another format (a KDBX 3.1 file upgraded; keething-spec kdbx.kdbx3 AC8): every
     * entry, group and setting as before, the times to the second, as the new file stores them.
     */
    fun verifyUnchanged(before: Vault, after: Vault, fileBefore: List<VaultGroup>, fileAfter: List<VaultGroup>) {
        fun Instant?.seconds() = this?.let(::seconds)
        fun Entry.toSeconds() = copy(expiryTime = expiryTime.seconds(), lastModified = lastModified.seconds(), created = created.seconds())
        fun Vault.toSeconds() = copy(
            entries = entries.map { it.toSeconds() },
            settings = settings?.toSeconds(),
            recycleBin = recycleBin.map { it.toSeconds() },
            templates = templates.map { it.toSeconds() },
            customIcons = emptyMap(),
        )
        if (all(before).mapValues { it.value.toSeconds() } != all(after)) fail("entries differ")
        if (before.toSeconds() != after.toSeconds()) fail("the database changed")
        if (fileBefore != fileAfter) fail("the groups changed")
        if (before.customIcons.mapValues { it.value.image.toList() } != after.customIcons.mapValues { it.value.image.toList() }) fail("the custom icons changed")
    }

    private fun verifyEntry(
        before: Vault,
        after: Vault,
        edit: VaultEdit.EntryEdit,
        applied: AppliedEdit,
        now: Instant,
        fileBefore: List<VaultGroup>,
        fileAfter: List<VaultGroup>,
        notes: NewNotes,
    ) {
        val old = all(before)
        val new = all(after)
        val id = edit.entryId
        if (old.keys - id != new.keys - id) fail("entries differ")
        for ((other, entry) in old) {
            if (other != id && new[other] != entry) fail("another entry changed")
        }

        val groups = before.groups.associateBy { it.id }.toMutableMap()
        val parentsBefore = parents(fileBefore)
        val parents = parentsBefore.toMutableMap()
        // Where a new or moved entry goes: its new groups by the names asked, each inside the one before.
        val destination = when (edit) {
            is VaultEdit.Create -> applied.destination ?: edit.group
            is VaultEdit.Update -> edit.changes.group?.let { applied.destination ?: it }
            else -> null
        }
        if (destination != null) {
            val asked = (edit as? VaultEdit.Create)?.group ?: (edit as VaultEdit.Update).changes.group!!
            // As the file had it: the levels it has by now are its groups, and only the rest are made (groups.new-groups AC3, AC4).
            val made = asked.newGroups.takeLast(destination.newGroups.size)
            if (destination.newGroups.map { it.id } != made.map { it.id } ||
                destination.newGroups.map { it.name } != made.map { it.name.trim() } ||
                applied.expectedGroupId != destination.finalGroupId
            ) {
                fail("the new groups are not those asked")
            }
            // The levels the file had: each the first group of its name, case and accents aside, in the one before.
            val inside = parents(before.groups)
            var reached = asked.groupId
            for (level in asked.newGroups.dropLast(destination.newGroups.size)) {
                val name = TextFolding.fold(level.name.trim())
                reached = before.groups.firstOrNull { inside[it.id] == reached && TextFolding.fold(it.name.trim()) == name }?.id
                    ?: fail("the new groups are not those asked")
            }
            if (reached != destination.groupId) fail("the new groups are not those asked")
            // ... and the first level made is one it did not have: no namesake made beside it.
            destination.newGroups.firstOrNull()?.let { first ->
                val name = TextFolding.fold(first.name.trim())
                if (before.groups.any { inside[it.id] == reached && TextFolding.fold(it.name.trim()) == name }) fail("the new groups are not those asked")
            }
            var path = groups[destination.groupId]?.path ?: fail("the new groups are not those asked")
            var parent = destination.groupId
            for (group in destination.newGroups) {
                if (group.id in groups) fail("the new groups are not those asked")
                val name = XmlText.writable(group.name)
                path = path + name
                groups[group.id] = VaultGroup(group.id, name, path)
                parents[group.id] = parent
                parent = group.id
            }
        }
        val parentsAfter = parents(fileAfter)
        // A restored entry's group, made again on the way back: where the file put it.
        if (edit is VaultEdit.Restore) {
            for (made in applied.createdGroupIds) {
                val group = after.groups.firstOrNull { it.id == made }
                if (group == null || made in groups) fail("a group is missing")
                groups[made] = group
                parents[made] = parentsAfter[made]
            }
        }
        // The recycle bin's own groups as before; a recycled entry's bin made in the root group when there was none.
        val bin = fileBefore.filter { it.id !in groups }.associateBy { it.id }.toMutableMap()
        val binAfter = fileAfter.filter { group -> after.groups.none { it.id == group.id } }.associateBy { it.id }
        val madeBin = applied.expectedGroupId.takeIf { edit is VaultEdit.MoveToRecycleBin && it !in bin && it !in groups }
        val rootId = before.groups.firstOrNull { it.isRoot }?.id
        binAfter[madeBin]?.takeIf { parentsAfter[it.id] == rootId }?.let { made ->
            bin[made.id] = made
            parents[made.id] = parentsAfter[made.id]
        }
        if (binAfter != bin) fail("the recycle bin's groups changed")
        // Those a move left empty (groups.entry-group AC5, AC6): the group the entry left, then each parent in turn, none
        // the root, none holding another entry (one holding a group would leave that group missing below).
        if (applied.deletedGroupIds.isNotEmpty()) {
            if (edit !is VaultEdit.Update || destination == null) fail("a group that was not empty went")
            var expected = old[id]?.groupId
            for (gone in applied.deletedGroupIds) {
                val group = groups[gone]
                if (gone != expected || group == null || group.isRoot || gone == destination.finalGroupId ||
                    old.values.any { it.id != id && it.groupId == gone }
                ) {
                    fail("a group that was not empty went")
                }
                groups.remove(gone)
                parents.remove(gone)
                expected = parentsBefore[gone]
            }
        }
        if (after.groups.associateBy { it.id } != groups || after.groups.size != groups.size ||
            after.recycleBinEnabled != before.recycleBinEnabled || parentsAfter != parents
        ) {
            fail("the groups changed")
        }

        when (edit) {
            is VaultEdit.Update -> {
                val actual = new[id] ?: fail("the edit did not come out as asked")
                val newNotes = notes.protected.takeIf { notes.missingIn == id }
                var expected = applyTo(old[id] ?: fail("entries differ"), edit.changes, newNotes, actual).copy(lastModified = actual.lastModified)
                if (destination != null) {
                    val group = groups[destination.finalGroupId] ?: fail("the group is missing")
                    expected = expected.copy(groupPath = group.path, groupId = group.id)
                }
                if (actual != expected) fail("the edit did not come out as asked")
            }
            is VaultEdit.Create -> {
                val actual = new[id]
                if (id in old || actual == null) fail("the new entry is missing")
                val group = groups[destination!!.finalGroupId] ?: fail("the group is missing")
                // A new entry's expiry time is its creation time, as in KeePassXC (it does not expire).
                val expected = applyTo(
                    Entry(
                        id, notesProtected = notes.protected, groupPath = group.path, groupId = group.id,
                        expiryTime = seconds(now), created = seconds(now),
                        lastModified = actual.lastModified,
                        customIconId = applied.customIconId?.takeIf { it in after.customIcons },
                    ),
                    edit.fields,
                    written = actual,
                )
                if (actual != expected || after.recycleBin.any { it.id == id }) fail("the new entry did not come out as asked")
            }
            is VaultEdit.MoveToRecycleBin, is VaultEdit.Restore -> {
                val actual = new[id]
                val toBin = edit is VaultEdit.MoveToRecycleBin
                if (actual == null || actual.groupId != applied.expectedGroupId || after.recycleBin.any { it.id == id } != toBin) {
                    fail("the entry did not move")
                }
                val expected = (old[id] ?: fail("entries differ"))
                    .copy(groupPath = actual.groupPath, groupId = actual.groupId, lastModified = actual.lastModified)
                if (actual != expected) fail("the moved entry changed")
            }
            is VaultEdit.DeleteForGood -> if (id !in old || id in new) fail("the entry is still there")
        }
    }

    /**
     * A change of the groups alone (groups.manage; the Mac `verify(groups:path:tags:)`): every group of the file comes
     * out as [expectedGroups], each in its parent; every entry as before but for its path, its group's new one (the
     * bin's too, when the bin is inside the group changed).
     */
    private fun verifyGroups(before: Vault, after: Vault, edit: VaultEdit.GroupEdit, fileBefore: List<VaultGroup>, fileAfter: List<VaultGroup>) {
        val groups = expectedGroups(fileBefore, edit).associateBy { it.id }
        val parents = parents(fileBefore).toMutableMap()
        // A name the parent has, case and accents aside, is refused by the file (groups.manage AC1, AC3, AC5).
        fun taken(name: String, parent: String?, except: String?): Boolean =
            fileBefore.any { it.id != except && parents[it.id] == parent && TextFolding.fold(it.name.trim()) == TextFolding.fold(name.trim()) }
        val namesake = when (edit) {
            is VaultEdit.RenameGroup -> taken(edit.name, parents[edit.groupId], edit.groupId)
            is VaultEdit.MoveGroup -> taken(groups[edit.groupId]?.name ?: "", edit.intoGroupId, edit.groupId)
            is VaultEdit.CreateGroup -> taken(edit.group.name, edit.parentId, null)
            is VaultEdit.DeleteEmptyGroup -> false
        }
        if (namesake) fail("the groups did not come out as asked")
        when (edit) {
            is VaultEdit.MoveGroup -> parents[edit.groupId] = edit.intoGroupId
            is VaultEdit.CreateGroup -> parents[edit.group.id] = edit.parentId
            is VaultEdit.DeleteEmptyGroup -> parents.remove(edit.groupId)
            is VaultEdit.RenameGroup -> {}
        }
        if (fileAfter.associateBy { it.id } != groups || fileAfter.size != groups.size || parents(fileAfter) != parents ||
            after.recycleBinEnabled != before.recycleBinEnabled
        ) {
            fail("the groups did not come out as asked")
        }
        val old = all(before)
        val new = all(after)
        if (old.keys != new.keys || before.recycleBin.map { it.id }.toSet() != after.recycleBin.map { it.id }.toSet()) fail("entries differ")
        val inBin = before.recycleBin.map { it.id }.toSet()
        for ((id, entry) in old) {
            val group = groups[entry.groupId]
            val expected = if (group == null && id in inBin) entry else entry.copy(groupPath = group?.path ?: fail("an entry changed"))
            if (new[id] != expected) fail("an entry changed")
        }
    }

    /**
     * The groups as [edit] should leave them (the Mac `expectedStructure`): a renamed or moved group and every group
     * below it at its new path, a new group added in its parent, the group deleted gone. Names trimmed, as written.
     */
    private fun expectedGroups(groups: List<VaultGroup>, edit: VaultEdit.GroupEdit): List<VaultGroup> {
        fun index(id: String) = groups.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: fail("the group is missing")

        // The group at [at] and everything below it at [newPath].
        fun moving(at: Int, newPath: List<String>, name: String): List<VaultGroup> {
            val oldDepth = groups[at].path.size
            val below = below(groups, at)
            return groups.mapIndexed { i, group ->
                when {
                    i == at -> group.copy(name = name, path = newPath)
                    i in below -> group.copy(path = newPath + group.path.drop(oldDepth))
                    else -> group
                }
            }
        }
        return when (edit) {
            is VaultEdit.RenameGroup -> {
                val at = index(edit.groupId)
                val name = XmlText.writable(edit.name.trim())
                moving(at, groups[at].path.dropLast(1) + name, name)
            }
            is VaultEdit.MoveGroup -> {
                val at = index(edit.groupId)
                moving(at, groups[index(edit.intoGroupId)].path + groups[at].name, groups[at].name)
            }
            is VaultEdit.CreateGroup -> {
                val name = XmlText.writable(edit.group.name.trim())
                groups + VaultGroup(edit.group.id, name, groups[index(edit.parentId)].path + name)
            }
            is VaultEdit.DeleteEmptyGroup -> {
                val at = index(edit.groupId)
                groups.filterIndexed { i, _ -> i != at }
            }
        }
    }

    /** The indexes of the groups inside the one at [at]: in file order they follow it, deeper than it. */
    private fun below(groups: List<VaultGroup>, at: Int): IntRange {
        var end = at + 1
        while (end < groups.size && groups[end].path.size > groups[at].path.size) end++
        return at + 1 until end
    }

    /** Each group's parent (null for the root): in file order, the last group before it one level up. */
    private fun parents(groups: List<VaultGroup>): Map<String, String?> {
        val open = ArrayList<VaultGroup>()
        val parents = HashMap<String, String?>()
        for (group in groups) {
            while (open.isNotEmpty() && open.last().path.size >= group.path.size) open.removeAt(open.lastIndex)
            parents[group.id] = open.lastOrNull()?.id
            open += group
        }
        return parents
    }

    /**
     * An entry as [changes] leave it: tags trimmed and times to the second, as the file stores them. [newNotesProtected]:
     * the protection Notes take when the entry has none and the change writes them. [written]: the entry as saved, whose
     * code fields a set code is checked by its code, not its fields.
     */
    private fun applyTo(entry: Entry, changes: EntryChanges, newNotesProtected: Boolean? = null, written: Entry? = null): Entry {
        var expected = entry
        changes.title?.let { expected = expected.copy(title = it) }
        changes.username?.let { expected = expected.copy(username = it) }
        changes.password?.let { expected = expected.copy(password = it) }
        changes.url?.let { expected = expected.copy(url = it) }
        if (changes.notes != null || changes.notesProtected != null) newNotesProtected?.let { expected = expected.copy(notesProtected = it) }
        changes.notes?.let { expected = expected.copy(notes = it) }
        changes.notesProtected?.let { expected = expected.copy(notesProtected = it) }
        changes.customFields?.let { fields ->
            expected = expected.copy(customFields = fields.filter { it.name !in EntryChanges.RESERVED_FIELD_NAMES })
        }
        changes.fieldTypes?.let { expected = expected.copy(fieldTypes = it) }
        when (val type = changes.entryType) {
            is EntryTypeChange.Set -> expected = expected.copy(entryType = type.type.stored)
            is EntryTypeChange.Stored -> expected = expected.copy(entryType = type.value)
            EntryTypeChange.Remove -> expected = expected.copy(entryType = null)
            null -> {}
        }
        changes.tags?.let { tags -> expected = expected.copy(tags = tags.map { it.trim() }.filter { it.isNotEmpty() }) }
        changes.expiry?.let { expiry ->
            expected = expected.copy(expires = expiry.expires)
            expiry.time?.let { expected = expected.copy(expiryTime = seconds(it)) }
        }
        when (val totp = changes.totp) {
            // The format the code is written in is the writer's choice: its fields are taken as written, the code compared.
            is TotpChange.Set -> expected = expected.copy(totp = totp.config, hasCodeFields = true, codeFields = written?.codeFields.orEmpty())
            TotpChange.Remove -> expected = expected.copy(totp = null, hasCodeFields = false, codeFields = emptyList())
            is TotpChange.Fields -> {
                val fields = totp.fields.filter { it.name in TotpFormat.FIELD_NAMES }
                val texts = fields.associate { field ->
                    field.name to when (val value = field.value) {
                        is FieldValue.Plain -> value.text
                        is FieldValue.Protected -> value.secret.reveal()
                    }
                }
                expected = expected.copy(totp = TotpResolver.resolve(texts), hasCodeFields = fields.isNotEmpty(), codeFields = fields)
            }
            null -> {}
        }
        return expected.asWritten()
    }

    /**
     * As the file holds the entry: what XML 1.0 cannot hold left out of its plain values, as the writer leaves it out
     * (keething-spec kdbx.hardening AC7); protected values keep every character.
     */
    private fun Entry.asWritten(): Entry = copy(
        title = XmlText.writable(title),
        username = XmlText.writable(username),
        url = XmlText.writable(url),
        notes = if (notesProtected) notes else XmlText.writable(notes),
        customFields = customFields.map { field ->
            val value = (field.value as? FieldValue.Plain)?.let { FieldValue.Plain(XmlText.writable(it.text)) } ?: field.value
            CustomField(XmlText.writable(field.name), value)
        },
        tags = tags.map(XmlText::writable).filter { it.isNotEmpty() },
    )

    /** Every entry by id: those outside the bin, the settings entry, those in the bin and the templates. */
    private fun all(vault: Vault): Map<String, Entry> =
        (vault.entries + listOfNotNull(vault.settings) + vault.recycleBin + vault.templates).associateBy { it.id }

    private fun seconds(time: Instant): Instant = time.truncatedTo(ChronoUnit.SECONDS)

    private fun fail(detail: String): Nothing = throw Failed(detail)
}
