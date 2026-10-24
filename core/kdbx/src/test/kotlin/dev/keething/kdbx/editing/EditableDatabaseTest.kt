package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
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
        EntryLifecycle.group(group, editable.root)!!.last().apply { insertAfter(XElement("CustomIconUUID", "ICON"), first("IconID")) }
        val id = Entry.newId()

        val applied = editable.apply(VaultEdit.Create(id, group, EntryChanges(title = "New", password = Secret.of("pw1"))), now)

        assertEquals(AppliedEdit(expectedGroupId = group, customIconId = "ICON"), applied)
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
        assertFailsWith<EditException.GroupNotFound> { editable.apply(VaultEdit.Create(Entry.newId(), "gone", EntryChanges()), now) }
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
