package dev.keething.kdbx.editing

import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.KeePassXcCli
import dev.keething.kdbx.editing.EditingFixtures.document
import dev.keething.kdbx.editing.EditingFixtures.open
import dev.keething.kdbx.editing.EditingFixtures.reread
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XmlTree
import dev.keething.kdbx.xml.KdbxTime
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Groups made, renamed, moved and deleted when empty, as KeePassXC writes them (keething-spec groups.new-groups,
 * groups.manage, groups.entry-group; the Mac `GroupTests` and `GroupChangeTests` expectations). Fixture databases
 * (password `pw`): the root group holds "Web & Mail" with two entries.
 */
class GroupLifecycleTest {
    private val folder: File = Files.createTempDirectory("kdbx-groups").toFile()
    private val now = Instant.ofEpochSecond(1_900_000_000)

    @AfterTest
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    private fun top(root: XElement): XElement = EntryLifecycle.topGroup(root)!!

    private fun id(group: XElement): String = EntryLifecycle.uuidText(group)

    private fun group(id: String, root: XElement): XElement = assertNotNull(EntryLifecycle.group(id, root), id).last()

    private fun web(root: XElement): String = id(top(root).all("Group").single { it.first("Name")?.text == "Web & Mail" })

    private fun name(group: XElement): String? = group.first("Name")?.text

    private fun time(element: XElement, name: String): Instant? = element.first("Times")?.first(name)?.text?.let(KdbxTime::parse)

    private fun newGroup(name: String) = NewGroup(Entry.newId(), name)

    /** Made directly, as another app would have made it. */
    private fun made(name: String, parent: String, root: XElement): String =
        newGroup(name).also { GroupLifecycle.create(listOf(it), parent, root, now) }.id

    /** The `DeletedObjects` records, UUID and `DeletionTime` as written. */
    private fun deleted(root: XElement): List<Pair<String?, String?>> =
        root.first("Root")?.first("DeletedObjects")?.all("DeletedObject").orEmpty()
            .map { it.first("UUID")?.text to it.first("DeletionTime")?.text }

    /** A group put into the recycle bin as KeePassXC deletes one (the phone does not). */
    private fun binned(name: String, root: XElement): Pair<String, String> {
        val bin = EntryLifecycle.createRecycleBin(root, now)
        val group = group(made(name, id(top(root)), root), root)
        top(root).remove(group)
        bin.append(group)
        return id(bin) to id(group)
    }

    @Test
    fun newGroupsAreWrittenAsKeePassXcWritesThemEachInsideTheOneBeforeAfterTheParentsGroups() {
        val editable = open("kdbx4-aes-aeskdf")
        val root = editable.root
        val a = newGroup("Servers & <Co>")
        val b = newGroup("Prod")

        val last = GroupLifecycle.create(listOf(a, b), id(top(root)), root, now)

        assertEquals(b.id, id(last))
        val made = top(root).all("Group").last()
        assertEquals(listOf("Web & Mail", a.name), top(root).all("Group").map(::name), "after the root's other groups")
        assertEquals(
            listOf("UUID", "Name", "Notes", "IconID", "Times", "IsExpanded", "DefaultAutoTypeSequence", "EnableAutoType",
                "EnableSearching", "LastTopVisibleEntry", "Group"),
            made.elements.map { it.name },
        )
        assertEquals(listOf("48", "True", "null", "null", EntryLifecycle.NULL_UUID),
            listOf("IconID", "IsExpanded", "EnableAutoType", "EnableSearching", "LastTopVisibleEntry").map { made.first(it)?.text })
        for (stamp in listOf("LastModificationTime", "CreationTime", "LastAccessTime", "ExpiryTime", "LocationChanged")) {
            assertEquals(now, time(made, stamp), stamp)
        }
        assertEquals(b.id, id(made.all("Group").single()))

        assertFailsWith<EditException.GroupNotFound> { GroupLifecycle.create(listOf(newGroup("x")), "gone", root, now) }
        val (bin, inBin) = binned("Old", root)
        assertFailsWith<EditException.GroupNotFound>("not in the bin") { GroupLifecycle.create(listOf(newGroup("x")), bin, root, now) }
        assertFailsWith<EditException.GroupNotFound> { GroupLifecycle.create(emptyList(), inBin, root, now) }
        assertFailsWith<EditException.Malformed> { GroupLifecycle.create(listOf(NewGroup(a.id, "Again")), id(top(root)), root, now) }
        assertFailsWith<EditException.InvalidName> { GroupLifecycle.create(listOf(newGroup(" ")), id(top(root)), root, now) }
        assertEquals("Padded", name(GroupLifecycle.create(listOf(newGroup("  Padded ")), id(top(root)), root, now)), "trimmed, as a rename")

        val after = reread(editable)
        assertEquals(listOf(a.name), after.groups.single { it.uuid == a.id }.path)
        assertEquals(listOf(a.name, "Prod"), after.groups.single { it.uuid == b.id }.path)
    }

    @Test
    fun aLevelTheFileHasByNowIsTakenInAnyCaseAndWithoutAccentsTheFirstOfANameNeverTheBin() {
        val root = open("kdbx4-aes-aeskdf").root
        val top = id(top(root))
        val web = web(root)
        val cafe = made("Café", web, root)
        val twin = made("Twin", web, root)
        made("Twin", web, root)
        val inbox = made(" Inbox ", top, root)
        val strasse = made("Straße", top, root)
        binned("Old", root)
        val x = newGroup("web & mail")
        val y = newGroup("CAFE")
        val z = newGroup("Deep")

        assertEquals(GroupDestination(cafe, listOf(z)), GroupLifecycle.resolving(GroupDestination(top, listOf(x, y, z)), root))
        val padded = newGroup(" Deeper ")
        assertEquals(GroupDestination(cafe, listOf(z, padded.copy(name = "Deeper"))),
            GroupLifecycle.resolving(GroupDestination(top, listOf(x, y, z, padded)), root), "the levels to make, trimmed")
        assertEquals(GroupDestination(twin), GroupLifecycle.resolving(GroupDestination(web, listOf(newGroup("twin"))), root), "the first of a name")
        assertEquals(GroupDestination(inbox), GroupLifecycle.resolving(GroupDestination(top, listOf(newGroup("inbox "))), root), "blanks around")
        assertEquals(GroupDestination(strasse), GroupLifecycle.resolving(GroupDestination(top, listOf(newGroup("STRASSE"))), root))

        // From the first level that is not there on, every level is new, even one a group further down is called.
        val gap = GroupDestination(top, listOf(x, newGroup("New"), newGroup("Café")))
        assertEquals(GroupDestination(web, gap.newGroups.drop(1)), GroupLifecycle.resolving(gap, root))
        val bin = GroupDestination(top, listOf(newGroup("Recycle Bin")))
        assertEquals(bin, GroupLifecycle.resolving(bin, root), "never the bin")
        // KeePass 2 lets any group be the bin: one below the top level is never taken either.
        val binGroup = group(EntryLifecycle.recycleBinUuid(root)!!, root)
        top(root).remove(binGroup)
        group(web, root).append(binGroup)
        val deeperBin = GroupDestination(top, listOf(x, newGroup("recycle bin")))
        assertEquals(GroupDestination(web, deeperBin.newGroups.drop(1)), GroupLifecycle.resolving(deeperBin, root))
        val gone = GroupDestination("gone", listOf(x))
        assertEquals(gone, GroupLifecycle.resolving(gone, root))
        assertEquals(GroupDestination(web), GroupLifecycle.resolving(GroupDestination(web), root))
    }

    @Test
    fun anEmptyGroupGoesForGoodThenEachParentItLeavesEmptyNeverTheRootTheBinAGroupInItOrTheTemplatesGroup() {
        val editable = open("kdbx4-aes-aeskdf")
        val root = editable.root
        val top = id(top(root))
        val outer = newGroup("Outer")
        val inner = newGroup("Inner")
        GroupLifecycle.create(listOf(outer, inner), top, root, now)
        val later = now.plusSeconds(60)
        assertEquals(emptyList(), deleted(root), "the fixture's DeletedObjects is empty")

        assertEquals(listOf(inner.id, outer.id), GroupLifecycle.removeEmpty(inner.id, root, later))

        assertNull(EntryLifecycle.group(outer.id, root))
        assertEquals(listOf(inner.id to KdbxTime.base64(later), outer.id to KdbxTime.base64(later)), deleted(root), "innermost first")
        assertEquals(later, time(top(root), "LastModificationTime"), "the group they were in is touched")
        assertEquals(emptyList(), GroupLifecycle.removeEmpty(top, root, now), "not the root group")
        assertEquals(emptyList(), GroupLifecycle.removeEmpty(web(root), root, now), "it has entries")

        val templates = made("Templates", top, root)
        // Made before the file names it the templates group: none is made in it after (groups.templates AC3).
        val child = made("Child", templates, root)
        assertNotNull(root.first("Meta")!!.first("EntryTemplatesGroup")).text = templates
        assertFailsWith<EditException.GroupNotFound> { made("Another", templates, root) }
        assertEquals(listOf(child), GroupLifecycle.removeEmpty(child, root, now), "it stops at the templates group")
        assertEquals(emptyList(), GroupLifecycle.removeEmpty(templates, root, now))
        val (bin, inBin) = binned("Old", root)
        assertEquals(emptyList(), GroupLifecycle.removeEmpty(inBin, root, now), "a group in the bin")
        group(bin, root).remove(group(inBin, root))
        assertEquals(emptyList(), GroupLifecycle.removeEmpty(bin, root, now), "nor the empty bin")
        assertEquals(emptyList(), GroupLifecycle.removeEmpty("gone", root, now))
        assertEquals(3, deleted(root).size)

        val after = reread(editable)
        assertTrue(after.groups.none { it.uuid == inner.id || it.uuid == outer.id })
    }

    @Test
    fun deletedObjectsIsMadeAfterTheRootGroupWhenTheFileHasNoneWithTheFilesKindOfTime() {
        val editable = open("kdbx4-aes-aeskdf")
        val root = editable.root
        root.first("Root")!!.first("DeletedObjects")?.let(root.first("Root")!!::remove)
        // A file that kept KDBX 3's ISO times (the phone edits KDBX 4 only).
        for (stamp in top(root).first("Times")!!.elements) KdbxTime.parse(stamp.text)?.let { stamp.text = KdbxTime.iso(it) }
        val gone = made("Gone", id(top(root)), root)

        GroupLifecycle.deleteEmpty(gone, root, now)

        assertEquals(listOf("Group", "DeletedObjects"), root.first("Root")!!.elements.map { it.name })
        assertEquals(listOf(gone to KdbxTime.iso(now)), deleted(root), "a file's ISO times stay ISO")
        reread(editable)
    }

    @Test
    fun aRenameTrimsTheNameTouchesTheGroupAloneAndIsRefusedForAnEmptyNameOrOneTheParentHasInAnyCase() {
        val before = document("kdbx4-aes-aeskdf")
        val editable = open("kdbx4-aes-aeskdf")
        val root = editable.root
        val top = id(top(root))
        val web = web(root)
        val inner = made("Inner", web, root)
        val other = made("Other", top, root)
        made("Straße", top, root)
        made("Cafés", top, root)
        val webBefore = group(web, root).copy()
        val entries = group(web, root).all("Entry").map(XElement::copy)

        GroupLifecycle.rename(web, "  Web  ", root, now)

        assertEquals("Web", name(group(web, root)))
        assertEquals(now, time(group(web, root), "LastModificationTime"))
        assertEquals(time(webBefore, "LocationChanged"), time(group(web, root), "LocationChanged"))
        for ((was, kept) in entries.zip(group(web, root).all("Entry"))) {
            assertContentEquals(XmlTree.withoutSecrets(was), XmlTree.withoutSecrets(kept), "entries are not touched")
        }
        GroupLifecycle.rename(web, "WEB", root, now)
        assertEquals("WEB", name(group(web, root)), "its own name in another case")
        GroupLifecycle.rename(web, "Web", root, now)

        val unchanged = XmlTree.withoutSecrets(root)
        assertFailsWith<EditException.GroupNameTaken> { GroupLifecycle.rename(other, "web", root, now) }
        assertFailsWith<EditException.GroupNameTaken> { GroupLifecycle.rename(other, " STRASSE ", root, now) }
        assertFailsWith<EditException.GroupNameTaken>("the same name, its accent apart") { GroupLifecycle.rename(other, "CAFE\u0301S", root, now) }
        assertFailsWith<EditException.GroupNameTaken>("accents aside, as a path's levels") { GroupLifecycle.rename(other, "cafes", root, now) }
        assertFailsWith<EditException.InvalidName> { GroupLifecycle.rename(other, "  ", root, now) }
        assertFailsWith<EditException.GroupNotChangeable> { GroupLifecycle.rename(top, "Top", root, now) }
        assertFailsWith<EditException.GroupNotFound> { GroupLifecycle.rename("gone", "x", root, now) }
        assertContentEquals(unchanged, XmlTree.withoutSecrets(root), "nothing written")
        val (bin, inBin) = binned("Old", root)
        val withBin = XmlTree.withoutSecrets(root)
        assertFailsWith<EditException.GroupNotChangeable> { GroupLifecycle.rename(bin, "Bin", root, now) }
        assertFailsWith<EditException.GroupNotChangeable> { GroupLifecycle.rename(inBin, "Older", root, now) }
        assertContentEquals(withBin, XmlTree.withoutSecrets(root), "nothing written")

        val after = reread(editable)
        assertEquals(listOf("Web", "Inner"), after.groups.single { it.uuid == inner }.path)
        assertEquals(before.entries.map { it.copy(groupPath = listOf("Web")) }, after.entries)
    }

    @Test
    fun aNewEmptyGroupIsMadeInAnyGroupOutsideTheBinAfterItsGroupsWithARenamesNameRules() {
        val before = document("kdbx4-aes-aeskdf")
        val editable = open("kdbx4-aes-aeskdf")
        val root = editable.root
        val top = id(top(root))
        val web = web(root)
        val archive = newGroup(" Archive ")
        val mail = newGroup("Mail")

        GroupLifecycle.createNamed(archive, top, root, now)
        GroupLifecycle.createNamed(mail, web, root, now)

        assertEquals(listOf("Web & Mail", "Archive"), top(root).all("Group").map(::name))
        assertEquals(listOf("Entry", "Entry", "Group"), group(web, root).elements.map { it.name }.filter { it == "Entry" || it == "Group" })
        assertEquals("48", group(archive.id, root).first("IconID")?.text)
        assertEquals(now, time(group(archive.id, root), "CreationTime"))
        assertFailsWith<EditException.GroupNameTaken> { GroupLifecycle.createNamed(newGroup("archive"), top, root, now) }
        assertFailsWith<EditException.GroupNameTaken> { GroupLifecycle.createNamed(newGroup("ÄRCHIVE"), top, root, now) }
        assertFailsWith<EditException.InvalidName> { GroupLifecycle.createNamed(newGroup(" "), top, root, now) }
        assertFailsWith<EditException.GroupNotFound> { GroupLifecycle.createNamed(newGroup("x"), "gone", root, now) }
        val (bin, inBin) = binned("Old", root)
        assertFailsWith<EditException.GroupNotChangeable> { GroupLifecycle.createNamed(newGroup("x"), bin, root, now) }
        assertFailsWith<EditException.GroupNotChangeable> { GroupLifecycle.createNamed(newGroup("x"), inBin, root, now) }

        val after = reread(editable)
        assertEquals(listOf("Archive"), after.groups.single { it.uuid == archive.id }.path)
        assertEquals(listOf("Web & Mail", "Mail"), after.groups.single { it.uuid == mail.id }.path)
        assertEquals(before.entries, after.entries)
    }

    @Test
    fun aGroupMovesWithEverythingInItAfterTheTargetsGroupsRememberingWhereItWas() {
        val before = document("kdbx4-aes-aeskdf")
        val editable = open("kdbx4-aes-aeskdf")
        val root = editable.root
        val top = id(top(root))
        val web = web(root)
        val inner = made("Inner", web, root)
        val archive = made("Archive", top, root)
        made("Old", archive, root)
        val later = now.plusSeconds(60)

        GroupLifecycle.move(web, archive, root, later)

        val moved = group(web, root)
        assertEquals(listOf("Old", "Web & Mail"), group(archive, root).all("Group").map(::name), "after the target's groups")
        assertEquals(top, moved.first("PreviousParentGroup")?.text)
        assertEquals(later, time(moved, "LocationChanged"))
        assertEquals(later, time(top(root), "LastModificationTime"))
        assertEquals(later, time(group(archive, root), "LastModificationTime"))
        val after = reread(editable)
        assertEquals(listOf("Archive", "Web & Mail", "Inner"), after.groups.single { it.uuid == inner }.path)
        assertEquals(
            before.entries.map { it.copy(groupPath = listOf("Archive", "Web & Mail"), ancestorGroupUuids = listOf(top, archive, web)) },
            after.entries,
            "the entries go with it, untouched",
        )

        GroupLifecycle.move(web, top, root, later)

        assertEquals(archive, group(web, root).first("PreviousParentGroup")?.text, "the top level is a target")
        assertEquals(listOf("Archive", "Web & Mail"), top(root).all("Group").map(::name))
        assertEquals(before.entries, reread(editable).entries)
    }

    @Test
    fun aMoveIsRefusedIntoItselfOrBelowWhereItIsBesideANamesakeIntoTheBinAndForTheRootTheBinOrAGroupInIt() {
        val root = open("kdbx4-aes-aeskdf").root
        val top = id(top(root))
        val web = web(root)
        val inner = made("Inner", web, root)
        val archive = made("Archive", top, root)
        made("wéb & mail", archive, root)
        val (bin, inBin) = binned("Old", root)
        val unchanged = XmlTree.withoutSecrets(root)

        assertFailsWith<EditException.GroupNotChangeable>("into itself") { GroupLifecycle.move(web, web, root, now) }
        assertFailsWith<EditException.GroupNotChangeable>("below itself") { GroupLifecycle.move(web, inner, root, now) }
        assertFailsWith<EditException.GroupNotChangeable>("where it is") { GroupLifecycle.move(inner, web, root, now) }
        assertFailsWith<EditException.GroupNameTaken>("beside a namesake") { GroupLifecycle.move(web, archive, root, now) }
        assertFailsWith<EditException.GroupNotChangeable>("into the bin") { GroupLifecycle.move(archive, bin, root, now) }
        assertFailsWith<EditException.GroupNotChangeable> { GroupLifecycle.move(archive, inBin, root, now) }
        assertFailsWith<EditException.GroupNotChangeable>("the root group") { GroupLifecycle.move(top, archive, root, now) }
        assertFailsWith<EditException.GroupNotChangeable>("the bin") { GroupLifecycle.move(bin, archive, root, now) }
        assertFailsWith<EditException.GroupNotChangeable> { GroupLifecycle.move(inBin, archive, root, now) }
        assertFailsWith<EditException.GroupNotFound> { GroupLifecycle.move("gone", archive, root, now) }
        assertFailsWith<EditException.GroupNotFound> { GroupLifecycle.move(archive, "gone", root, now) }
        assertContentEquals(unchanged, XmlTree.withoutSecrets(root), "nothing written")
    }

    @Test
    fun anEmptyGroupIsDeletedForGoodWithADeletedObjectAndOnlyWhenEmptyNeverTheRootTheBinOrAGroupInIt() {
        val editable = open("kdbx4-aes-aeskdf")
        val root = editable.root
        val top = id(top(root))
        val web = web(root)
        val empty = made("Empty", web, root)
        val outer = newGroup("Outer")
        GroupLifecycle.create(listOf(outer, newGroup("Inner")), top, root, now)
        val later = now.plusSeconds(60)

        GroupLifecycle.deleteEmpty(empty, root, later)

        assertNull(EntryLifecycle.group(empty, root))
        assertEquals(listOf(empty to KdbxTime.base64(later)), deleted(root))
        assertEquals(later, time(group(web, root), "LastModificationTime"))
        assertFailsWith<EditException.GroupNotEmpty>("entries") { GroupLifecycle.deleteEmpty(web, root, now) }
        assertFailsWith<EditException.GroupNotEmpty>("a group") { GroupLifecycle.deleteEmpty(outer.id, root, now) }
        assertFailsWith<EditException.GroupNotChangeable> { GroupLifecycle.deleteEmpty(top, root, now) }
        assertFailsWith<EditException.GroupNotFound> { GroupLifecycle.deleteEmpty("gone", root, now) }
        val (bin, inBin) = binned("Old", root)
        assertFailsWith<EditException.GroupNotChangeable>("a group in the bin") { GroupLifecycle.deleteEmpty(inBin, root, now) }
        group(bin, root).remove(group(inBin, root))
        assertFailsWith<EditException.GroupNotChangeable>("the empty bin") { GroupLifecycle.deleteEmpty(bin, root, now) }
        assertEquals(1, deleted(root).size)

        assertTrue(reread(editable).groups.none { it.uuid == empty })
    }

    @Test
    fun keepassxcCliReadsTheChangedGroupsAndKeepsTheDeletedObjects() {
        if (!KeePassXcCli.isInstalled) return
        val before = document("kdbx4-aes-argon2d")
        val editable = open("kdbx4-aes-argon2d")
        val root = editable.root
        val top = id(top(root))
        val web = web(root)
        val one = before.entry("Entry <one> ü")
        val archive = newGroup("Archive")
        val empty = newGroup("Empty")
        val moved = newGroup("Moved")
        val deeper = newGroup("Deeper")
        val lonely = newGroup("Lonely")
        val stray = Entry.newId()
        for (edit in listOf(
            VaultEdit.CreateGroup(top, archive),
            VaultEdit.MoveGroup(web, archive.id),
            VaultEdit.RenameGroup(web, "Mail & Web"),
            VaultEdit.CreateGroup(top, empty),
            VaultEdit.DeleteEmptyGroup(empty.id),
            VaultEdit.Update(one.uuid, null, EntryChanges(group = GroupDestination(top, listOf(moved, deeper)))),
            VaultEdit.Create(stray, GroupDestination(top, listOf(lonely)), EntryChanges(title = "Stray")),
            VaultEdit.Update(stray, null, EntryChanges(group = GroupDestination(moved.id))),
        )) {
            editable.apply(edit, now)
        }
        val file = File(folder, "groups.kdbx").apply { writeBytes(EditingFixtures.write(editable)) }

        val listed = KeePassXcCli.run("ls", "-R", "-f", file.path)
        assertEquals(0, listed.code, listed.err)
        assertTrue("Archive/Mail & Web/Second renamed" in listed.text, listed.text)
        assertTrue("Moved/Deeper/Entry <one> ü" in listed.text, listed.text)
        assertTrue("Moved/Stray" in listed.text, listed.text)
        assertTrue("Empty/" !in listed.text && "Lonely/" !in listed.text, listed.text)
        val exported = KeePassXcCli.run("export", "-f", "xml", file.path)
        assertEquals(0, exported.code, exported.err)
        val tree = XmlTree.parse(exported.out)
        val records = tree.first("Root")?.first("DeletedObjects")?.all("DeletedObject").orEmpty().map { it.first("UUID")?.text }
        assertEquals(listOf(empty.id, lonely.id), records, "KeePassXC keeps them")
        assertEquals(top, EntryLifecycle.group(web, tree)?.last()?.first("PreviousParentGroup")?.text)
        assertEquals(web, EntryLifecycle.locate(one.uuid, tree)?.entry?.first("PreviousParentGroup")?.text)
    }
}
