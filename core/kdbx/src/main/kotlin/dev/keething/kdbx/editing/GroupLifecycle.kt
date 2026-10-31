package dev.keething.kdbx.editing

import dev.keething.domain.text.TextFolding
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.kdbx.schema.KdbxSchema
import dev.keething.kdbx.tree.XElement
import java.time.Instant

/**
 * Makes, renames, moves and deletes groups in the tree as KeePassXC does (port of the Mac `GroupLifecycle` but the
 * recycle bin's group delete and restore, which the phone leaves to the Mac; keething-spec groups.new-groups,
 * groups.manage, groups.entry-group):
 * - a new group gets the elements KeePassXC writes for one (the plain folder icon, every time now, auto-type and
 *   searching inherited) and goes after its parent's other groups;
 * - a moved group goes after the target's groups, remembering where it was (`PreviousParentGroup`,
 *   `Times/LocationChanged`);
 * - a group deleted (empty, or left empty by an entry's move) goes for good with a `DeletedObjects` record, so a
 *   synchronising copy deletes it too;
 * - the root group, the recycle bin and the groups in it are never changed here; a restore makes groups again
 *   outside the bin ([home]).
 */
internal object GroupLifecycle {
    // Making

    /**
     * Makes [groups] in the group [parentId] (outside the recycle bin), each inside the one before, their names
     * trimmed. Returns the last one (the parent when [groups] is empty).
     */
    fun create(groups: List<NewGroup>, parentId: String, root: XElement, now: Instant): XElement {
        val path = EntryLifecycle.group(parentId, root)
        if (path == null || EntryLifecycle.isInRecycleBin(path, root)) throw EditException.GroupNotFound()
        if (groups.any { it.name.isBlank() }) throw EditException.InvalidName()
        var parent = path.last()
        for (group in groups) parent = make(NewGroup(group.id, group.name.trim()), parent, root, now)
        return parent
    }

    /**
     * [destination] against the file as it is now (groups.new-groups AC3): a new group whose parent has a group of
     * that name by now (case and accents folded, the first of a name; another app made it since the read) is that
     * group, and only the levels still missing are made, their names trimmed as they are written. Never the recycle
     * bin.
     */
    fun resolving(destination: GroupDestination, root: XElement): GroupDestination {
        var parent = EntryLifecycle.group(destination.groupId, root)?.last() ?: return destination
        val bin = EntryLifecycle.recycleBinUuid(root)
        var result = destination
        while (true) {
            val wanted = TextFolding.fold(result.newGroups.firstOrNull()?.name?.trim() ?: break)
            val existing = parent.all("Group").firstOrNull { group ->
                EntryLifecycle.uuidText(group) != bin && TextFolding.fold(name(group).trim()) == wanted
            } ?: break
            result = GroupDestination(EntryLifecycle.uuidText(existing), result.newGroups.drop(1))
            parent = existing
        }
        return result.copy(newGroups = result.newGroups.map { it.copy(name = it.name.trim()) })
    }

    /** A new, empty group in [parentId] (outside the recycle bin), its name trimmed and not one the parent has. */
    fun createNamed(group: NewGroup, parentId: String, root: XElement, now: Instant) {
        val path = EntryLifecycle.group(parentId, root) ?: throw EditException.GroupNotFound()
        if (EntryLifecycle.isInRecycleBin(path, root)) throw EditException.GroupNotChangeable()
        make(NewGroup(group.id, freeName(group.name, path.last(), except = null)), path.last(), root, now)
    }

    // Changing

    /**
     * A new name (trimmed) for a group outside the recycle bin, as KeePassXC's `Group::setName`: the group is touched,
     * nothing in it changes. Refused when its parent has another group of that name in any case, since a path would
     * then name either.
     */
    fun rename(groupId: String, name: String, root: XElement, now: Instant) {
        val path = changeable(groupId, root)
        val group = path.last()
        val trimmed = freeName(name, path[path.size - 2], except = group)
        val element = group.first("Name")
        if (element != null) element.text = trimmed else KdbxSchema.insert(XElement("Name", trimmed), group)
        EntryLifecycle.touch(group, now, EntryLifecycle.usesIsoTimes(root))
    }

    /**
     * A group with everything in it into [targetId], as KeePassXC's `Group::setParent`: after the target's groups,
     * remembering where it was (`PreviousParentGroup`, `LocationChanged`), both groups touched. Refused into the bin,
     * into itself or a group inside it, where it is, and next to a group of its name.
     */
    fun move(groupId: String, targetId: String, root: XElement, now: Instant) {
        val path = changeable(groupId, root)
        val targetPath = EntryLifecycle.group(targetId, root) ?: throw EditException.GroupNotFound()
        val group = path.last()
        val parent = path[path.size - 2]
        val target = targetPath.last()
        if (EntryLifecycle.isInRecycleBin(targetPath, root) || targetPath.any { it === group } || target === parent) {
            throw EditException.GroupNotChangeable()
        }
        if (isTaken(name(group), target, except = group)) throw EditException.GroupNameTaken()
        relocate(group, parent, target, root, now)
    }

    // Deleting

    /** A group with nothing in it, outside the recycle bin, for good with a `DeletedObjects` record. */
    fun deleteEmpty(groupId: String, root: XElement, now: Instant) {
        val path = changeable(groupId, root)
        val group = path.last()
        if (!isEmpty(group)) throw EditException.GroupNotEmpty()
        path[path.size - 2].remove(group)
        EntryLifecycle.touch(path[path.size - 2], now, EntryLifecycle.usesIsoTimes(root))
        recordDeleted(listOf(EntryLifecycle.uuidText(group)), root, now)
    }

    /**
     * After an entry left the group [groupId]: the group, when nothing is left in it, and then each parent the same
     * way, go for good with `DeletedObjects` records (nothing is lost). Never the root group, the recycle bin or a
     * group in it, nor KeePassXC's templates group (`Meta/EntryTemplatesGroup`). Returns them, innermost first.
     */
    fun removeEmpty(groupId: String, root: XElement, now: Instant): List<String> {
        val path = EntryLifecycle.group(groupId, root)?.toMutableList() ?: return emptyList()
        if (EntryLifecycle.isInRecycleBin(path, root)) return emptyList()
        val templates = root.first("Meta")?.first("EntryTemplatesGroup")?.text?.trim()
        val removed = mutableListOf<String>()
        while (path.size > 1 && EntryLifecycle.uuidText(path.last()) != templates && isEmpty(path.last())) {
            val group = path.removeAt(path.lastIndex)
            path.last().remove(group)
            removed += EntryLifecycle.uuidText(group)
        }
        if (removed.isEmpty()) return emptyList()
        EntryLifecycle.touch(path.last(), now, EntryLifecycle.usesIsoTimes(root))
        recordDeleted(removed, root, now)
        return removed
    }

    /** `DeletedObjects` records, so a synchronising copy deletes them too; made after the root group when missing. */
    private fun recordDeleted(uuids: List<String>, root: XElement, now: Instant) {
        val rootElement = root.first("Root") ?: throw EditException.Malformed("no Root")
        val deleted = rootElement.first("DeletedObjects") ?: XElement("DeletedObjects").also { KdbxSchema.insert(it, rootElement) }
        val stamp = EntryLifecycle.time(now, EntryLifecycle.usesIsoTimes(root))
        for (uuid in uuids) {
            val record = XElement("DeletedObject")
            record.append(XElement("UUID", uuid))
            record.append(XElement("DeletionTime", stamp))
            deleted.append(record)
        }
    }

    // Restoring

    /**
     * The group outside the bin that something in the bin goes back into; [parents] are the groups it is in,
     * outermost first. In a group that is in the bin (recycled with it): that group's own place, by name. Right in
     * the bin: its `PreviousParentGroup`, by name when that is in the bin too, the root group when it is gone.
     * Groups missing on the way are made, and their UUIDs added to [created].
     */
    fun home(element: XElement, parents: List<XElement>, root: XElement, now: Instant, created: MutableList<String>, depth: Int = 0): XElement {
        if (depth >= 64) throw EditException.MalformedLinks()
        val top = EntryLifecycle.topGroup(root) ?: throw EditException.GroupNotFound()
        val parent = parents.lastOrNull() ?: throw EditException.GroupNotFound()
        val bin = EntryLifecycle.recycleBinUuid(root)
        if (EntryLifecycle.uuidText(parent) == bin) {
            val previous = element.first("PreviousParentGroup")?.text?.trim() ?: ""
            if (previous.isEmpty() || previous == bin) return top
            val previousPath = EntryLifecycle.group(previous, root) ?: return top
            if (!EntryLifecycle.isInRecycleBin(previousPath.dropLast(1), root)) return previousPath.last()
            return counterpart(previousPath.last(), previousPath.dropLast(1), root, now, created, depth + 1)
        }
        return counterpart(parent, parents.dropLast(1), root, now, created, depth + 1)
    }

    /** The group outside the bin that stands for [group] (in the bin): the one of its name in its home, or a new one there. */
    private fun counterpart(group: XElement, parents: List<XElement>, root: XElement, now: Instant, created: MutableList<String>, depth: Int): XElement {
        val target = home(group, parents, root, now, created, depth)
        namesake(group, target, root)?.let { return it }
        val made = make(NewGroup(Entry.newId(), name(group)), target, root, now)
        for (element in listOf("Notes", "IconID")) {
            val text = group.first(element)?.text
            val mine = made.first(element)
            if (text != null && mine != null) mine.text = text
        }
        group.first("CustomIconUUID")?.text?.takeIf { it.isNotEmpty() }?.let {
            made.insertAfter(XElement("CustomIconUUID", it), made.first("IconID"))
        }
        created += EntryLifecycle.uuidText(made)
        return made
    }

    /** The first group in [target] with [group]'s name (never the recycle bin). */
    private fun namesake(group: XElement, target: XElement, root: XElement): XElement? {
        val bin = EntryLifecycle.recycleBinUuid(root)
        val wanted = name(group)
        return target.all("Group").firstOrNull { it !== group && name(it) == wanted && EntryLifecycle.uuidText(it) != bin }
    }

    // Helpers

    /** A new group as KeePassXC writes one (the plain folder icon, every time now, auto-type and searching inherited). */
    private fun make(new: NewGroup, parent: XElement, root: XElement, now: Instant): XElement {
        if (EntryLifecycle.group(new.id, root) != null || EntryLifecycle.locate(new.id, root) != null) {
            throw EditException.Malformed("the new group's id is taken")
        }
        val group = XElement("Group")
        group.append(XElement("UUID", new.id))
        group.append(XElement("Name", new.name))
        group.append(XElement("Notes"))
        group.append(XElement("IconID", "48"))
        group.append(EntryLifecycle.newTimes(now, EntryLifecycle.usesIsoTimes(root)))
        group.append(XElement("IsExpanded", "True"))
        group.append(XElement("DefaultAutoTypeSequence"))
        group.append(XElement("EnableAutoType", "null"))
        group.append(XElement("EnableSearching", "null"))
        group.append(XElement("LastTopVisibleEntry", EntryLifecycle.NULL_UUID))
        parent.append(group)
        return group
    }

    /** The group and the groups it is in, when it may be renamed, moved or deleted: not the root, the bin or in it. */
    private fun changeable(groupId: String, root: XElement): List<XElement> {
        val path = EntryLifecycle.group(groupId, root) ?: throw EditException.GroupNotFound()
        if (path.size < 2 || EntryLifecycle.isInRecycleBin(path, root)) throw EditException.GroupNotChangeable()
        return path
    }

    /** [name] trimmed, when it is not empty and [parent] has no other group of that name ([isTaken]). */
    private fun freeName(name: String, parent: XElement, except: XElement?): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw EditException.InvalidName()
        if (isTaken(trimmed, parent, except)) throw EditException.GroupNameTaken()
        return trimmed
    }

    /**
     * Whether [parent] has a group other than [except] called [name], case and accents aside as a path's levels are
     * matched ([resolving]): a path would name either.
     */
    private fun isTaken(name: String, parent: XElement, except: XElement?): Boolean {
        val wanted = TextFolding.fold(name.trim())
        return parent.all("Group").any { it !== except && TextFolding.fold(name(it).trim()) == wanted }
    }

    /** Moves [group] from [parent] to the end of [target]'s groups, remembering [parent]. */
    private fun relocate(group: XElement, parent: XElement, target: XElement, root: XElement, now: Instant) {
        val iso = EntryLifecycle.usesIsoTimes(root)
        parent.remove(group)
        target.append(group)
        val previous = group.first("PreviousParentGroup")
        if (previous != null) {
            previous.text = EntryLifecycle.uuidText(parent)
        } else {
            // KeePass's schema (`TGroup`): after LastTopVisibleEntry, before Tags, CustomData and the entries.
            KdbxSchema.insert(XElement("PreviousParentGroup", EntryLifecycle.uuidText(parent)), group)
        }
        EntryEditing.setTime("LocationChanged", now, EntryEditing.child("Times", group), iso)
        EntryLifecycle.touch(parent, now, iso)
        EntryLifecycle.touch(target, now, iso)
    }

    private fun isEmpty(group: XElement): Boolean = group.elements.none { it.name == "Entry" || it.name == "Group" }

    private fun name(group: XElement): String = group.first("Name")?.text ?: ""
}
