package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.kdbx.KeePassXcCli
import dev.keething.kdbx.editing.EditingFixtures.document
import dev.keething.kdbx.editing.EditingFixtures.open
import dev.keething.kdbx.editing.EditingFixtures.reread
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XmlTree
import dev.keething.kdbx.xml.KdbxField
import dev.keething.kdbx.xml.KdbxTime
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** New entries, the recycle bin and restoring (SPEC §3.3; the Mac `LifecycleTests` and `GroupTests` expectations). */
class EntryLifecycleTest {
    private val folder: File = Files.createTempDirectory("kdbx-life").toFile()
    private val now = Instant.ofEpochSecond(1_900_000_000)

    @AfterTest
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    private fun groupId(name: String, fixture: String = "kdbx4-aes-aeskdf") = document(fixture).groups.single { it.name == name }.uuid

    @Test
    fun aNewEntryHasTheFieldsAskedAndKeePassXcsElementsAndNothingElseChanges() {
        val before = document("kdbx4-aes-aeskdf")
        val group = before.groups.single { it.name == "Web & Mail" }
        val editable = open("kdbx4-aes-aeskdf")
        val id = Entry.newId()

        EntryLifecycle.create(
            id, group.uuid,
            EntryChanges(
                title = "New & <shiny>", username = "me", password = Secret.of("pä ss"), url = "https://new.example",
                customFields = listOf(CustomField.protected("PIN", "1234")), tags = listOf("x", "y"),
            ),
            editable.root, now,
        )

        val element = assertNotNull(EntryLifecycle.locate(id, editable.root)).entry
        assertEquals(
            listOf("UUID", "IconID", "ForegroundColor", "BackgroundColor", "OverrideURL", "Tags", "Times",
                "String", "String", "String", "String", "String", "String", "AutoType", "History"),
            element.elements.map { it.name },
        )
        assertEquals("0", element.first("IconID")?.text, "the group has the plain folder icon")
        assertEquals(
            listOf("LastModificationTime", "CreationTime", "LastAccessTime", "ExpiryTime", "Expires", "UsageCount", "LocationChanged"),
            element.first("Times")?.elements?.map { it.name },
        )
        val after = reread(editable)
        val created = after.entries.single { it.uuid == id }
        assertEquals("x;y", created.tags)
        assertEquals(listOf("Web & Mail"), created.groupPath)
        assertEquals(group.uuid, created.ancestorGroupUuids.last())
        assertEquals(now, created.lastModified)
        assertEquals(now, created.created)
        assertEquals(now, created.expiryTime)
        assertEquals(false, created.expires)
        assertEquals(
            listOf(
                KdbxField("Notes", plain("")), KdbxField("Password", protected("pä ss")), KdbxField("Title", plain("New & <shiny>")),
                KdbxField("URL", plain("https://new.example")), KdbxField("UserName", plain("me")), KdbxField("PIN", protected("1234")),
            ),
            created.fields,
        )
        assertEquals(before.entries, after.entries.filter { it.uuid != id })
        assertEquals(before.groups, after.groups)
        assertFailsWith<EditException.GroupNotFound> { EntryLifecycle.create(Entry.newId(), "nope", EntryChanges(), editable.root, now) }
        assertFailsWith<EditException.EntryExists> { EntryLifecycle.create(id, group.uuid, EntryChanges(), editable.root, now) }
    }

    @Test
    fun aNewEntryTakesItsGroupsOwnIconAndCustomIcon() {
        val editable = open("kdbx4-aes-aeskdf")
        val group = assertNotNull(EntryLifecycle.group(groupId("Web & Mail"), editable.root)).last()
        group.first("IconID")!!.text = "1"

        val a = EntryLifecycle.create(Entry.newId(), groupId("Web & Mail"), EntryChanges(title = "A"), editable.root, now)
        assertEquals("1", a.first("IconID")?.text)
        assertNull(a.first("CustomIconUUID"))

        group.insertAfter(XElement("CustomIconUUID", "CUSTOM"), group.first("IconID"))
        val b = EntryLifecycle.create(Entry.newId(), groupId("Web & Mail"), EntryChanges(title = "B"), editable.root, now)
        assertEquals("0", b.first("IconID")?.text)
        assertEquals("CUSTOM", b.first("CustomIconUUID")?.text)
        assertEquals(listOf(a, b), group.all("Entry").takeLast(2), "added after the group's entries")
    }

    @Test
    fun theBinIsMadeAsKeePassXcDoesAndTheEntryRemembersItsGroup() {
        val before = document("kdbx4-chacha20-argon2id")
        val one = before.entry("Entry <one> ü")
        val editable = open("kdbx4-chacha20-argon2id")

        val binId = EntryLifecycle.moveToRecycleBin(one.uuid, editable.root, now)

        val meta = editable.root.first("Meta")!!
        assertEquals(binId, meta.first("RecycleBinUUID")?.text)
        assertEquals(now, meta.first("RecycleBinChanged")?.text?.let(KdbxTime::parse))
        val bin = assertNotNull(EntryLifecycle.group(binId, editable.root)).last()
        assertEquals("Recycle Bin", bin.first("Name")?.text)
        assertEquals("43", bin.first("IconID")?.text)
        assertEquals("false", bin.first("EnableSearching")?.text)
        assertEquals("false", bin.first("EnableAutoType")?.text)
        val entry = assertNotNull(EntryLifecycle.locate(one.uuid, editable.root)).entry
        assertEquals(one.ancestorGroupUuids.last(), entry.first("PreviousParentGroup")?.text)
        val names = entry.elements.map { it.name }
        assertEquals(names.indexOf("Times") + 1, names.indexOf("PreviousParentGroup"), "right after Times")
        assertEquals(now, entry.first("Times")?.first("LocationChanged")?.text?.let(KdbxTime::parse))

        val written = EditingFixtures.write(editable)
        assertEquals("4.1", KdbxReader.format(written).version)
        val after = KdbxReader.open(written, EditingFixtures.key).document
        val recycled = after.entries.single { it.uuid == one.uuid }
        assertEquals(listOf("Recycle Bin"), recycled.groupPath)
        assertEquals(binId, recycled.ancestorGroupUuids.last())
        assertEquals(one.copy(groupPath = recycled.groupPath, ancestorGroupUuids = recycled.ancestorGroupUuids, lastModified = now), recycled)
        assertEquals(before.groups + after.groups.filter { it.uuid == binId }, after.groups)

        // A second one goes into the same bin; the first can come back.
        val second = before.entry("Second renamed")
        assertEquals(binId, EntryLifecycle.moveToRecycleBin(second.uuid, editable.root, now))
        assertFailsWith<EditException.AlreadyInRecycleBin> { EntryLifecycle.moveToRecycleBin(second.uuid, editable.root, now) }
        assertEquals(one.ancestorGroupUuids.last(), EntryLifecycle.restore(one.uuid, editable.root, now).destinationId)
        assertEquals(binId, EntryLifecycle.locate(one.uuid, editable.root)?.entry?.first("PreviousParentGroup")?.text)
        assertFailsWith<EditException.NotInRecycleBin> { EntryLifecycle.restore(one.uuid, editable.root, now) }
        val restored = reread(editable)
        assertEquals(one.groupPath, restored.entries.single { it.uuid == one.uuid }.groupPath)
        assertEquals(listOf("Recycle Bin"), restored.entries.single { it.uuid == second.uuid }.groupPath)
    }

    @Test
    fun aRestoredEntryGoesToTheRootGroupWhenItsGroupIsGone() {
        val editable = open("kdbx4-aes-aeskdf")
        val one = document("kdbx4-aes-aeskdf").entries.first()
        EntryLifecycle.moveToRecycleBin(one.uuid, editable.root, now)
        EntryLifecycle.locate(one.uuid, editable.root)!!.entry.first("PreviousParentGroup")!!.text = "GONE"

        val restored = EntryLifecycle.restore(one.uuid, editable.root, now)

        assertEquals(EntryLifecycle.uuidText(editable.root.first("Root")!!.first("Group")!!), restored.destinationId)
        assertTrue(restored.createdGroupIds.isEmpty())
    }

    @Test
    fun entriesDeletedWithTheirGroupComeBackIntoItsGroupMadeAgainByNameOnce() {
        val editable = open("kdbx4-aes-aeskdf")
        val web = groupId("Web & Mail")
        val document = document("kdbx4-aes-aeskdf")
        val one = document.entry("Entry <one> ü")
        val second = document.entry("Second renamed")
        // The group with its entries goes into the bin, as KeePassXC deletes a group.
        val bin = EntryLifecycle.createRecycleBin(editable.root, now)
        val path = assertNotNull(EntryLifecycle.group(web, editable.root))
        val group = path.last()
        path[path.size - 2].remove(group)
        bin.append(group)
        group.insertAfter(XElement("PreviousParentGroup", EntryLifecycle.uuidText(path[path.size - 2])), group.first("Times"))

        val first = EntryLifecycle.restore(one.uuid, editable.root, now)

        assertEquals(listOf(first.destinationId), first.createdGroupIds)
        val made = assertNotNull(EntryLifecycle.group(first.destinationId, editable.root))
        assertEquals(listOf("Web & Mail"), made.drop(1).map { it.first("Name")?.text }, "made again by name, outside the bin")
        assertTrue(assertNotNull(EntryLifecycle.group(web, editable.root)).contains(bin), "the group itself stays in the bin")

        val next = EntryLifecycle.restore(second.uuid, editable.root, now)

        assertEquals(first.destinationId, next.destinationId, "into the group made for the first")
        assertTrue(next.createdGroupIds.isEmpty())
        val after = reread(editable)
        assertEquals(listOf("Web & Mail"), after.entries.single { it.uuid == one.uuid }.groupPath)
        assertEquals(listOf("Web & Mail"), after.entries.single { it.uuid == second.uuid }.groupPath)
    }

    @Test
    fun recyclingIsRefusedWhenTheBinIsTurnedOff() {
        val editable = open("kdbx4-aes-aeskdf")
        editable.root.first("Meta")!!.first("RecycleBinEnabled")!!.text = "False"
        val one = document("kdbx4-aes-aeskdf").entries.first()

        assertFailsWith<EditException.RecycleBinDisabled> { EntryLifecycle.moveToRecycleBin(one.uuid, editable.root, now) }
        assertFailsWith<EditException.EntryNotFound> { EntryLifecycle.moveToRecycleBin("gone", editable.root, now) }
        assertFailsWith<EditException.EntryNotFound> { EntryLifecycle.restore("gone", editable.root, now) }
    }

    @Test
    fun keepassxcCliReadsACreatedEntryAndTheBin() {
        if (!KeePassXcCli.isInstalled) return
        val before = document("kdbx4-aes-argon2d")
        val editable = open("kdbx4-aes-argon2d")
        EntryLifecycle.create(Entry.newId(), groupId("Web & Mail", "kdbx4-aes-argon2d"),
            EntryChanges(title = "Created", password = Secret.of("pässwörd")), editable.root, now)
        val second = before.entry("Second renamed")
        EntryLifecycle.moveToRecycleBin(second.uuid, editable.root, now)
        val file = File(folder, "life.kdbx").apply { writeBytes(EditingFixtures.write(editable)) }

        val shown = KeePassXcCli.run("show", "-s", "-a", "Password", file.path, "Web & Mail/Created")
        assertEquals(0, shown.code, shown.err)
        assertEquals("pässwörd\n", shown.text)
        val listed = KeePassXcCli.run("ls", "-R", "-f", file.path)
        assertTrue("Recycle Bin/Second renamed" in listed.text, listed.text)
        val exported = KeePassXcCli.run("export", "-f", "xml", file.path)
        assertEquals(0, exported.code, exported.err)
        val moved = assertNotNull(EntryLifecycle.locate(second.uuid, XmlTree.parse(exported.out))).entry
        assertEquals(second.ancestorGroupUuids.last(), moved.first("PreviousParentGroup")?.text, "KeePassXC keeps where it came from")
    }
}
