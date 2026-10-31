package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.Fixtures
import dev.keething.kdbx.editing.EditingFixtures.document
import dev.keething.kdbx.editing.EditingFixtures.key
import dev.keething.kdbx.editing.EditingFixtures.open
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.descendants
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The editable database as the save path uses it: one edit applied, the file written. */
class EditableDatabaseTest {
    private val now = Instant.ofEpochSecond(1_900_000_000)

    @Test
    fun anUpdateChangesTheEntryInPlace() {
        val editable = open("kdbx4-aes-aeskdf")
        val id = document("kdbx4-aes-aeskdf").entry("Second renamed").uuid

        val applied = editable.apply(VaultEdit.Update(id, null, EntryChanges(username = "new-user")), now)

        assertEquals(AppliedEdit(expectedGroupId = null), applied)
        val written = KdbxReader.open(editable.write(key).file, key).document
        assertEquals(plain("new-user"), written.entries.single { it.uuid == id }.field("UserName"))
    }

    @Test
    fun aNewEntryIsExpectedInItsGroupWithTheGroupsCustomIcon() {
        val editable = open("kdbx4-aes-aeskdf")
        val group = document("kdbx4-aes-aeskdf").groups.single { it.name == "Web & Mail" }.uuid
        val icon = "AAECAwQFBgcICQoLDA0ODw=="
        val meta = editable.root.first("Meta")!!
        val icons = meta.first("CustomIcons") ?: XElement("CustomIcons").also(meta::append)
        icons.append(XElement("Icon").apply { append(XElement("UUID", icon)); append(XElement("Data", "iVBORw0KGgo=")) })
        EntryLifecycle.group(group, editable.root)!!.last().apply { insertAfter(XElement("CustomIconUUID", icon), first("IconID")) }
        val id = Entry.newId()

        val applied = editable.apply(VaultEdit.Create(id, GroupDestination(group), EntryChanges(title = "New", password = Secret.of("pw1"))), now)

        assertEquals(AppliedEdit(expectedGroupId = group, customIconId = icon, destination = GroupDestination(group)), applied)
        val written = KdbxReader.open(editable.write(key).file, key).document
        assertEquals(protected("pw1"), written.entries.single { it.uuid == id }.field("Password"))
    }

    @Test
    fun recyclingAndRestoringNameWhereTheEntryShouldBe() {
        val editable = open("kdbx4-aes-aeskdf")
        val one = document("kdbx4-aes-aeskdf").entry("Entry <one> ü")

        val recycled = editable.apply(VaultEdit.MoveToRecycleBin(one.uuid, one.lastModified), now)
        val restored = editable.apply(VaultEdit.Restore(one.uuid, now), now)

        assertEquals(EntryLifecycle.recycleBinUuid(editable.root), recycled.expectedGroupId)
        assertEquals(AppliedEdit(expectedGroupId = one.ancestorGroupUuids.last()), restored)
    }

    @Test
    fun editsOfWhatIsNotThereAreRefused() {
        val editable = open("kdbx4-aes-aeskdf")

        assertFailsWith<EditException.EntryNotFound> { editable.apply(VaultEdit.Update("gone", null, EntryChanges(notes = "x")), now) }
        assertFailsWith<EditException.GroupNotFound> { editable.apply(VaultEdit.Create(Entry.newId(), GroupDestination("gone"), EntryChanges()), now) }
    }

    private fun top(editable: EditableDatabase): String = EntryLifecycle.uuidText(EntryLifecycle.topGroup(editable.root)!!)

    private fun history(editable: EditableDatabase, id: String): Int =
        EntryLifecycle.locate(id, editable.root)!!.entry.first("History")?.all("Entry")?.size ?: 0

    private fun deleted(editable: EditableDatabase): List<String?> =
        editable.root.first("Root")?.first("DeletedObjects")?.all("DeletedObject").orEmpty().map { it.first("UUID")?.text }

    private fun newGroup(name: String) = NewGroup(Entry.newId(), name)

    @Test
    fun anEditAndAMoveMakeOneHistorySnapshotAMoveAloneNone() {
        val before = document("kdbx4-aes-aeskdf")
        val one = before.entry("Entry <one> ü")
        val web = one.ancestorGroupUuids.last()
        val editable = open("kdbx4-aes-aeskdf")
        val snapshots = history(editable, one.uuid)
        val archive = newGroup("Archive")

        val edited = editable.apply(
            VaultEdit.Update(one.uuid, one.lastModified, EntryChanges(username = "moved", group = GroupDestination(top(editable), listOf(archive)))),
            now,
        )

        assertEquals(
            AppliedEdit(expectedGroupId = archive.id, createdGroupIds = listOf(archive.id), destination = GroupDestination(top(editable), listOf(archive))),
            edited,
        )
        assertEquals(snapshots + 1, history(editable, one.uuid), "one snapshot, for the edit")

        val moved = editable.apply(VaultEdit.Update(one.uuid, now, EntryChanges(group = GroupDestination(web))), now)

        assertEquals(AppliedEdit(expectedGroupId = web, destination = GroupDestination(web), deletedGroupIds = listOf(archive.id)), moved)
        assertEquals(snapshots + 1, history(editable, one.uuid), "none for a move")
        val after = KdbxReader.open(editable.write(key).file, key).document
        assertEquals(before.groups, after.groups, "Archive, left empty, went")
        assertEquals(plain("moved"), after.entries.single { it.uuid == one.uuid }.field("UserName"))
        assertEquals(listOf("Web & Mail"), after.entries.single { it.uuid == one.uuid }.groupPath)
    }

    @Test
    fun aMoveDeletesTheGroupsItLeavesEmptyInnermostFirstNeverTheOneItGoesIntoOrAbove() {
        val editable = open("kdbx4-aes-aeskdf")
        val top = top(editable)
        val web = document("kdbx4-aes-aeskdf").groups.single { it.name == "Web & Mail" }.uuid
        val old = newGroup("Old")
        val deep = newGroup("Deep")
        val keep = newGroup("Keep")
        val kept = newGroup("Kept")
        val sub = newGroup("Sub")
        val a = Entry.newId()
        val b = Entry.newId()
        val c = Entry.newId()
        // Old › Deep holds A alone; Keep holds B and the group Kept, which holds C.
        val created = editable.apply(VaultEdit.Create(a, GroupDestination(top, listOf(old, deep)), EntryChanges(title = "A")), now)
        assertEquals(AppliedEdit(expectedGroupId = deep.id, createdGroupIds = listOf(old.id, deep.id), destination = GroupDestination(top, listOf(old, deep))), created)
        editable.apply(VaultEdit.Create(b, GroupDestination(top, listOf(keep)), EntryChanges(title = "B")), now)
        editable.apply(VaultEdit.Create(c, GroupDestination(keep.id, listOf(kept)), EntryChanges(title = "C")), now)
        fun move(id: String, destination: GroupDestination) = editable.apply(VaultEdit.Update(id, null, EntryChanges(group = destination)), now).deletedGroupIds

        assertEquals(listOf(deep.id, old.id), move(a, GroupDestination(web)))
        assertEquals(listOf(deep.id, old.id), deleted(editable))
        assertEquals(listOf(kept.id), move(c, GroupDestination(top)), "Keep still holds B")
        assertEquals(emptyList(), move(b, GroupDestination(keep.id, listOf(sub))), "B goes below Keep")
        assertEquals(listOf(sub.id), move(b, GroupDestination(keep.id)), "not Keep, which it goes into")
        assertEquals(emptyList(), move(b, GroupDestination(keep.id)), "where it is")

        val unmoved = editable.apply(VaultEdit.Update(b, null, EntryChanges(title = "B2")), now)

        assertEquals(AppliedEdit(expectedGroupId = null), unmoved)
        assertEquals(listOf(deep.id, old.id, kept.id, sub.id), deleted(editable), "an edit without a move deletes nothing")
        val after = KdbxReader.open(editable.write(key).file, key).document
        assertEquals(listOf("Web & Mail"), after.entries.single { it.uuid == a }.groupPath)
        assertEquals(listOf("Keep"), after.entries.single { it.uuid == b }.groupPath)
        assertEquals(emptyList(), after.entries.single { it.uuid == c }.groupPath)
        assertEquals(listOf(listOf(), listOf("Web & Mail"), listOf("Keep")), after.groups.map { it.path })
    }

    @Test
    fun newLevelsMeetTheFileAsItIsALevelMadeElsewhereSinceTheReadIsTaken() {
        val editable = open("kdbx4-aes-aeskdf")
        val top = top(editable)
        // Another app made Inbox after the editor was opened.
        val inbox = newGroup("Inbox")
        GroupLifecycle.create(listOf(inbox), top, editable.root, now)
        val notes = newGroup("Notes")
        val id = Entry.newId()

        val applied = editable.apply(VaultEdit.Create(id, GroupDestination(top, listOf(newGroup("inbox"), notes)), EntryChanges(title = "N")), now)

        assertEquals(AppliedEdit(expectedGroupId = notes.id, createdGroupIds = listOf(notes.id), destination = GroupDestination(inbox.id, listOf(notes))), applied)
        val after = KdbxReader.open(editable.write(key).file, key).document
        assertEquals(listOf("Inbox", "Notes"), after.entries.single { it.uuid == id }.groupPath)
        assertEquals(1, after.groups.count { it.name.equals("inbox", ignoreCase = true) })
    }

    @Test
    fun aGroupsEditsSayWhatTheyMadeAndDeletedAndTheirRefusalsComeThrough() {
        val before = document("kdbx4-aes-aeskdf")
        val editable = open("kdbx4-aes-aeskdf")
        val top = top(editable)
        val web = before.groups.single { it.name == "Web & Mail" }.uuid
        val archive = newGroup(" Archive ")
        val empty = newGroup("Empty")

        assertEquals(AppliedEdit(expectedGroupId = null, createdGroupIds = listOf(archive.id)), editable.apply(VaultEdit.CreateGroup(top, archive), now))
        assertEquals(AppliedEdit(expectedGroupId = null), editable.apply(VaultEdit.MoveGroup(web, archive.id), now))
        assertEquals(AppliedEdit(expectedGroupId = null), editable.apply(VaultEdit.RenameGroup(web, "Mail"), now))
        editable.apply(VaultEdit.CreateGroup(top, empty), now)
        assertEquals(AppliedEdit(expectedGroupId = null, deletedGroupIds = listOf(empty.id)), editable.apply(VaultEdit.DeleteEmptyGroup(empty.id), now))

        assertFailsWith<EditException.GroupNameTaken> { editable.apply(VaultEdit.CreateGroup(top, newGroup("ARCHIVE")), now) }
        assertFailsWith<EditException.InvalidName> { editable.apply(VaultEdit.RenameGroup(web, " "), now) }
        assertFailsWith<EditException.GroupNotChangeable> { editable.apply(VaultEdit.MoveGroup(archive.id, web), now) }
        assertFailsWith<EditException.GroupNotEmpty> { editable.apply(VaultEdit.DeleteEmptyGroup(archive.id), now) }
        val after = KdbxReader.open(editable.write(key).file, key).document
        assertEquals(listOf(listOf(), listOf("Archive"), listOf("Archive", "Mail")), after.groups.map { it.path })
        assertEquals(before.entries.map { it.copy(groupPath = listOf("Archive", "Mail"), ancestorGroupUuids = listOf(top, archive.id, web)) }, after.entries)
    }

    @Test
    fun writingDropsAttachmentsNothingRefersToAndKeepsTheOthersRight() {
        val editable = open("kdbx4-aes-aeskdf")
        val before = KdbxReader.open(Fixtures.bytes("kdbx4-aes-aeskdf.kdbx"), key)
        val second = before.document.entry("Second renamed")
        val att = before.binaries[second.attachments.single { it.name == "att.bin" }.ref!!]
        // Every reference to blob.bin goes, in the entry and its history.
        editable.root.descendants().filter { it.name == "Binary" && it.first("Key")?.text == "blob.bin" }.forEach { binary ->
            editable.root.descendants().first { binary in it.elements }.remove(binary)
        }

        val written = editable.write(key)

        val after = KdbxReader.open(written.file, key)
        assertEquals(before.binaries.size - 1, after.binaries.size)
        assertEquals(written.binaries, after.binaries)
        val kept = after.document.entries.single { it.uuid == second.uuid }.attachments.single()
        assertEquals("att.bin", kept.name)
        assertEquals(att, after.binaries[kept.ref!!])
    }

    @Test
    fun wipingReachesTheTreeTheDocumentAndTheAttachments() {
        val editable = open("kdbx4-aes-aeskdf")
        val tree = editable.root.descendants().mapNotNull { it.secret }
        val document = editable.document.entries.flatMap { it.fields }.mapNotNull { (it.value as? FieldValue.Protected)?.secret }

        editable.wipe()

        assertTrue(tree.isNotEmpty() && tree.all { it.isWiped })
        assertTrue(document.isNotEmpty() && document.all { it.isWiped })
        assertTrue(editable.binaries.all { it.isWiped })
    }
}
