package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.kdbx.schema.KdbxSchema
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.xml.KdbxTime
import java.time.Instant

/**
 * Creates, recycles, restores, moves and deletes entries in the tree as KeePassXC does (SPEC §3.3; port of the Mac
 * `EntryLifecycle`):
 * - a new entry gets the elements KeePassXC writes for one, every time set to now, and the group's icon when
 *   the group has its own (not the plain folder);
 * - recycling moves it into the recycle bin (`Meta/RecycleBinUUID`; the bin is made when there is none),
 *   remembering where it was (`PreviousParentGroup`) and when it moved (`Times/LocationChanged`);
 * - restoring moves it back ([GroupLifecycle.home]);
 * - a move into another group remembers the group it left, without a history snapshot ([moveEntry]);
 * - deleting from the bin (or with the bin turned off) removes it for good and adds a `DeletedObjects` record, so a
 *   synchronising copy deletes it too ([deleteForGood]).
 * A group that gains or loses an entry gets a new `LastModificationTime` / `LastAccessTime`, as in KeePassXC.
 */
internal object EntryLifecycle {
    /** An entry and the groups it is in, outermost first (its own group last). */
    class Location(val entry: XElement, val groups: List<XElement>) {
        val group: XElement get() = groups.last()
    }

    /** Where a restored entry went, and the groups made on the way (outermost first). */
    data class Restoration(val destinationId: String, val createdGroupIds: List<String>)

    /** KeePassXC's icons: a group's plain folder (closed and open) and the recycle bin. */
    private val FOLDER_ICONS = setOf("48", "49")
    private const val RECYCLE_BIN_ICON = "43"
    const val NULL_UUID = "AAAAAAAAAAAAAAAAAAAAAA=="

    // Finding

    /** The current version of an entry (not one in a history) and where it is. */
    fun locate(uuid: String, root: XElement): Location? {
        fun search(group: XElement, path: List<XElement>): Location? {
            val here = path + group
            for (child in group.elements) {
                if (child.name == "Entry" && uuidText(child) == uuid) return Location(child, here)
                if (child.name == "Group") search(child, here)?.let { return it }
            }
            return null
        }
        return topGroup(root)?.let { search(it, emptyList()) }
    }

    /** A group and the groups it is in, outermost first (the group itself last). */
    fun group(uuid: String, root: XElement): List<XElement>? {
        fun search(group: XElement, path: List<XElement>): List<XElement>? {
            val here = path + group
            if (uuidText(group) == uuid) return here
            for (child in group.all("Group")) search(child, here)?.let { return it }
            return null
        }
        return topGroup(root)?.let { search(it, emptyList()) }
    }

    /** Whether the groups of a location (outermost first) include the recycle bin. */
    fun isInRecycleBin(groups: List<XElement>, root: XElement): Boolean {
        val bin = recycleBinUuid(root) ?: return false
        return groups.any { uuidText(it) == bin }
    }

    // Changes

    /** Adds a new entry with [fields] at the end of the group's entries; its new attachments go into [pool]. */
    fun create(entryId: String, groupId: String, fields: EntryChanges, root: XElement, now: Instant, pool: AttachmentPool? = null): XElement {
        if (locate(entryId, root) != null) throw EditException.EntryExists()
        val group = groupId.takeIf { it.isNotEmpty() }?.let { group(it, root)?.last() } ?: throw EditException.GroupNotFound()
        val iso = usesIsoTimes(root)

        val entry = XElement("Entry")
        entry.append(XElement("UUID", entryId))
        // KeePassXC: the group's icon, unless it is the plain folder (a custom icon replaces the number).
        val groupIcon = group.first("IconID")?.text?.trim() ?: "48"
        val customIcon = group.first("CustomIconUUID")?.text?.trim() ?: ""
        val useGroupIcon = customIcon.isNotEmpty() || groupIcon !in FOLDER_ICONS
        entry.append(XElement("IconID", if (useGroupIcon && customIcon.isEmpty()) groupIcon else "0"))
        if (useGroupIcon && customIcon.isNotEmpty()) entry.append(XElement("CustomIconUUID", customIcon))
        for (name in listOf("ForegroundColor", "BackgroundColor", "OverrideURL", "Tags")) entry.append(XElement(name))
        entry.append(newTimes(now, iso))
        val protection = EntryEditing.memoryProtection(root)
        // KeePassXC writes the fields sorted by key.
        for (key in EntryChanges.STANDARD_KEYS.sorted()) {
            val string = XElement("String")
            string.append(XElement("Key", key))
            string.append(XElement("Value").apply { if (protection.getValue(key)) secret = Secret.empty() })
            entry.append(string)
        }
        val autoType = XElement("AutoType")
        autoType.append(XElement("Enabled", "True"))
        autoType.append(XElement("DataTransferObfuscation", "0"))
        autoType.append(XElement("DefaultSequence"))
        entry.append(autoType)
        entry.append(XElement("History"))

        EntryEditing.write(fields, entry, root, now, pool)
        insertEntry(entry, group)
        touch(group, now, iso)
        return entry
    }

    /** Into the recycle bin, made when there is none. Returns the bin's UUID. */
    fun moveToRecycleBin(entryId: String, root: XElement, now: Instant): String {
        val location = locate(entryId, root) ?: throw EditException.EntryNotFound()
        if (!recycleBinEnabled(root)) throw EditException.RecycleBinDisabled()
        if (isInRecycleBin(location.groups, root)) throw EditException.AlreadyInRecycleBin()
        val bin = existingRecycleBin(root) ?: createRecycleBin(root, now)
        move(location, bin, root, now)
        return uuidText(bin)
    }

    /**
     * Out of the recycle bin into the group it came from (Mac SPEC §3.17, §3.24): recycled on its own, into its
     * `PreviousParentGroup` (the root group when that is gone); recycled with its group, into that group's place,
     * made again by name, without the group's other entries, when it is not there.
     */
    fun restore(entryId: String, root: XElement, now: Instant): Restoration {
        val location = locate(entryId, root) ?: throw EditException.EntryNotFound()
        if (!isInRecycleBin(location.groups, root)) throw EditException.NotInRecycleBin()
        val created = mutableListOf<String>()
        val destination = GroupLifecycle.home(location.entry, location.groups, root, now, created)
        move(location, destination, root, now)
        return Restoration(uuidText(destination), created)
    }

    /**
     * Into another group outside the recycle bin (keething-spec groups.entry-group AC3), as KeePassXC moves an entry
     * ([move]). Where it is already, nothing changes.
     */
    fun moveEntry(entryId: String, groupId: String, root: XElement, now: Instant) {
        val location = locate(entryId, root) ?: throw EditException.EntryNotFound()
        if (isInRecycleBin(location.groups, root)) throw EditException.AlreadyInRecycleBin()
        val path = group(groupId, root)
        if (path == null || isInRecycleBin(path, root)) throw EditException.GroupNotFound()
        if (path.last() !== location.group) move(location, path.last(), root, now)
    }

    /**
     * The entry and its history out of the file for good, recorded in `Root/DeletedObjects` (keething-spec
     * edit.delete-for-good AC1, AC3): only from the recycle bin, or anywhere when the bin is turned off. Its
     * attachments that nothing else refers to go when the file is written ([AttachmentPool.compact], AC4).
     */
    fun deleteForGood(entryId: String, root: XElement, now: Instant) {
        val location = locate(entryId, root) ?: throw EditException.EntryNotFound()
        if (recycleBinEnabled(root) && !isInRecycleBin(location.groups, root)) throw EditException.NotInRecycleBin()
        location.group.remove(location.entry)
        touch(location.group, now, usesIsoTimes(root))
        GroupLifecycle.recordDeleted(listOf(entryId), root, now)
    }

    // Moving

    /**
     * As KeePassXC's `Entry::setGroup`: the entry remembers the group it left, its location time and (since that
     * changes it) its modification time become now; both groups are touched. No history snapshot.
     */
    fun move(location: Location, destination: XElement, root: XElement, now: Instant) {
        val iso = usesIsoTimes(root)
        val entry = location.entry
        location.group.remove(entry)
        insertEntry(entry, destination)

        val previous = uuidText(location.group)
        val existing = entry.first("PreviousParentGroup")
        if (existing != null) {
            existing.text = previous
        } else {
            // KeePass's schema (`TEntry`): after Tags (or what comes before it), before Times.
            KdbxSchema.insert(XElement("PreviousParentGroup", previous), entry)
        }
        val times = entry.first("Times") ?: XElement("Times").also { KdbxSchema.insert(it, entry) }
        for (name in listOf("LastModificationTime", "LastAccessTime", "LocationChanged")) EntryEditing.setTime(name, now, times, iso)
        touch(location.group, now, iso)
        touch(destination, now, iso)
    }

    /** After the group's last entry; a group's entries come before its subgroups. */
    fun insertEntry(entry: XElement, group: XElement) {
        val last = group.all("Entry").lastOrNull()
        val firstGroup = group.first("Group")
        when {
            last != null -> group.insertAfter(entry, last)
            firstGroup != null -> group.insertBefore(entry, firstGroup)
            else -> group.append(entry)
        }
    }

    // The recycle bin

    fun recycleBinEnabled(root: XElement): Boolean =
        !(root.first("Meta")?.first("RecycleBinEnabled")?.text?.trim().equals("false", ignoreCase = true))

    /**
     * Whether [groups] (a path from the root) is in the templates group (keething-spec groups.templates AC1): the group
     * `Meta/EntryTemplatesGroup` names, when it is not the root group nor the recycle bin or a group in it.
     */
    fun isInTemplates(groups: List<XElement>, root: XElement): Boolean {
        val templates = root.first("Meta")?.first("EntryTemplatesGroup")?.text?.trim()?.takeUnless { it.isEmpty() || it == NULL_UUID } ?: return false
        val at = groups.indexOfFirst { uuidText(it) == templates }
        if (at < 1) return false
        val path = group(templates, root) ?: return false
        return path.size > 1 && !isInRecycleBin(path, root)
    }

    fun recycleBinUuid(root: XElement): String? {
        val uuid = root.first("Meta")?.first("RecycleBinUUID")?.text?.trim() ?: ""
        return uuid.takeUnless { it.isEmpty() || it == NULL_UUID }
    }

    /** The bin group, when `Meta/RecycleBinUUID` names a group that exists. */
    private fun existingRecycleBin(root: XElement): XElement? = recycleBinUuid(root)?.let { group(it, root)?.last() }

    /**
     * As KeePassXC's `Database::createRecycleBin`: "Recycle Bin" at the end of the root group, with the bin icon,
     * searching and auto-type off; `Meta` points to it.
     */
    fun createRecycleBin(root: XElement, now: Instant): XElement {
        val meta = root.first("Meta") ?: throw EditException.Malformed("no Meta")
        val top = topGroup(root) ?: throw EditException.Malformed("no root group")
        val iso = usesIsoTimes(root)
        val bin = XElement("Group")
        bin.append(XElement("UUID", Entry.newId()))
        bin.append(XElement("Name", "Recycle Bin"))
        bin.append(XElement("Notes"))
        bin.append(XElement("IconID", RECYCLE_BIN_ICON))
        bin.append(newTimes(now, iso))
        bin.append(XElement("IsExpanded", "True"))
        bin.append(XElement("DefaultAutoTypeSequence"))
        bin.append(XElement("EnableAutoType", "false"))
        bin.append(XElement("EnableSearching", "false"))
        bin.append(XElement("LastTopVisibleEntry", NULL_UUID))
        top.append(bin)
        touch(top, now, iso)

        val uuid = uuidText(bin)
        val existing = meta.first("RecycleBinUUID")
        if (existing != null) existing.text = uuid else meta.insertAfter(XElement("RecycleBinUUID", uuid), meta.first("RecycleBinEnabled"))
        val changed = meta.first("RecycleBinChanged")
        if (changed != null) {
            changed.text = time(now, iso)
        } else {
            meta.insertAfter(XElement("RecycleBinChanged", time(now, iso)), meta.first("RecycleBinUUID"))
        }
        return bin
    }

    // Helpers

    /** The database's root group (`Root/Group`). */
    fun topGroup(root: XElement): XElement? = root.first("Root")?.first("Group")

    fun uuidText(element: XElement): String = element.first("UUID")?.text?.trim() ?: ""

    /** KDBX 4 writes base64 times; a file that has ISO ones (from KDBX 3) keeps them. */
    fun usesIsoTimes(root: XElement): Boolean =
        topGroup(root)?.first("Times")?.elements?.any { KdbxTime.isIso(it.text) } ?: false

    fun time(time: Instant, iso: Boolean): String = if (iso) KdbxTime.iso(time) else KdbxTime.base64(time)

    /** KeePassXC's `TimeInfo` of a new item: every time now, not expiring, never used. */
    fun newTimes(now: Instant, iso: Boolean): XElement {
        val times = XElement("Times")
        val stamp = time(now, iso)
        for (name in listOf("LastModificationTime", "CreationTime", "LastAccessTime", "ExpiryTime")) times.append(XElement(name, stamp))
        times.append(XElement("Expires", "False"))
        times.append(XElement("UsageCount", "0"))
        times.append(XElement("LocationChanged", stamp))
        return times
    }

    /** A group gained or lost an entry (KeePassXC's `Group::updateTimeinfo`). */
    fun touch(group: XElement, now: Instant, iso: Boolean) {
        val times = group.first("Times") ?: return
        EntryEditing.setTime("LastModificationTime", now, times, iso)
        EntryEditing.setTime("LastAccessTime", now, times, iso)
    }
}
