package dev.keepassbar.kdbx.editing

import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.kdbx.tree.XElement
import java.time.Instant

/**
 * The part of the Mac `GroupLifecycle` a restore needs (Mac SPEC §3.24): where something in the recycle bin goes
 * back to, and making a group there again when it is gone. The phone changes no groups otherwise.
 */
internal object GroupLifecycle {
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
        val made = create(Entry.newId(), name(group), target, root, now)
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

    /** A new group as KeePassXC writes one (the plain folder icon, every time now, auto-type and searching inherited). */
    private fun create(id: String, name: String, parent: XElement, root: XElement, now: Instant): XElement {
        if (EntryLifecycle.group(id, root) != null || EntryLifecycle.locate(id, root) != null) {
            throw EditException.Malformed("the new group's id is taken")
        }
        val group = XElement("Group")
        group.append(XElement("UUID", id))
        group.append(XElement("Name", name))
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

    /** The first group in [target] with [group]'s name (never the recycle bin). */
    private fun namesake(group: XElement, target: XElement, root: XElement): XElement? {
        val bin = EntryLifecycle.recycleBinUuid(root)
        val wanted = name(group)
        return target.all("Group").firstOrNull { it !== group && name(it) == wanted && EntryLifecycle.uuidText(it) != bin }
    }

    private fun name(group: XElement): String = group.first("Name")?.text ?: ""
}
