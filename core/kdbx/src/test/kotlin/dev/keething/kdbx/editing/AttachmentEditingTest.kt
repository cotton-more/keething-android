package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.AttachmentChange
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.KeePassXcCli
import dev.keething.kdbx.editing.EditingFixtures.document
import dev.keething.kdbx.editing.EditingFixtures.key
import dev.keething.kdbx.editing.EditingFixtures.open
import dev.keething.kdbx.reader.KdbxDatabase
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.tree.XElement
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Adding, renaming and removing attachments in the file (keething-spec edit.attachments AC5, AC6; the Mac
 * `AttachmentEditingTests`). "Second renamed" has blob.bin (2000 bytes) and att.bin (3000 bytes).
 */
class AttachmentEditingTest {
    private val folder: File = Files.createTempDirectory("kdbx-attachments").toFile()
    private val now = Instant.ofEpochSecond(1_900_000_000)

    @AfterTest
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    private fun secret(text: String) = Secret.of(text)

    private fun KdbxDatabase.bytes(entryId: String, name: String): ByteArray? =
        document.entries.single { it.uuid == entryId }.attachments.firstOrNull { it.name == name }?.ref
            ?.let { binaries[it].useBytes(ByteArray::copyOf) }

    private fun KdbxDatabase.names(entryId: String): List<String> =
        document.entries.single { it.uuid == entryId }.attachments.map { it.name }

    // spec: edit.attachments/AC6
    @Test
    fun theSameBytesAreReusedAndNewOnesAddedAtTheEndProtected() {
        val pool = AttachmentPool(listOf(secret("same"), secret("other")), listOf(0, 1))
        val new = secret("new")

        assertEquals(1, pool.add(secret("other")), "content already there is shared")
        assertEquals(2, pool.add(new))
        assertEquals(2, pool.add(secret("new")), "and so is one added before")
        assertEquals(listOf<Byte>(0, 1, AttachmentPool.PROTECTED_FLAG), pool.flags)
        new.wipe()
        assertEquals(secret("new"), pool.binaries[2], "the pool keeps a copy of its own")
    }

    // spec: edit.attachments/AC5
    @Test
    fun keptRenamedRemovedAndAddedInOrderAfterTheFieldsAndTheOldVersionKeepsTheOldOnes() {
        val editable = open("kdbx4-aes-aeskdf")
        val before = KdbxReader.open(EditingFixtures.write(editable), key)
        val id = before.document.entries.single { it.title() == "Second renamed" }.uuid
        val note = "a note\n"

        editable.apply(
            VaultEdit.Update(id, null, EntryChanges(attachments = listOf(AttachmentChange.Add("note.txt", secret(note)), AttachmentChange.Keep("att.bin", "renamed.bin")))),
            now,
        )

        val entry = assertNotNull(EntryEditing.entry(id, editable.root))
        val names = entry.elements.map { it.name }
        assertTrue(names.lastIndexOf("String") < names.indexOf("Binary"), "attachments after the fields")
        assertTrue(names.lastIndexOf("Binary") < names.indexOf("AutoType"), "and before AutoType")
        val file = File(folder, "edited.kdbx").apply { writeBytes(editable.write(key).file) }
        val after = KdbxReader.open(file.readBytes(), key)
        assertEquals(listOf("note.txt", "renamed.bin"), after.names(id))
        assertContentEquals(note.encodeToByteArray(), after.bytes(id, "note.txt"))
        assertContentEquals(before.bytes(id, "att.bin"), after.bytes(id, "renamed.bin"))

        val reopened = KdbxReader.openForEditing(file.readBytes(), key)
        val old = reopened.root.let { EntryEditing.entry(id, it) }!!.first("History")!!.all("Entry").last()
        val refs = old.all("Binary").associate { it.first("Key")!!.text to it.first("Value")!!.attribute("Ref")!!.toInt() }
        assertEquals(setOf("blob.bin", "att.bin"), refs.keys, "the old version keeps both, so blob.bin is not dropped")
        refs.forEach { (name, ref) -> assertContentEquals(before.bytes(id, name), reopened.binaries[ref].useBytes(ByteArray::copyOf)) }
        reopened.wipe()

        if (KeePassXcCli.isInstalled) {
            val exported = KeePassXcCli.run("attachment-export", "--stdout", file.path, "Web & Mail/Second renamed", "note.txt")
            assertEquals(0, exported.code, exported.err)
            assertEquals(note, exported.text, "KeePassXC reads the new attachment")
        }
    }

    // spec: edit.attachments/AC6
    @Test
    fun identicalContentIsStoredOnceAndOneNothingRefersToIsDropped() {
        val editable = open("kdbx4-aes-aeskdf")
        val before = KdbxReader.open(EditingFixtures.write(editable), key)
        val second = before.document.entries.single { it.title() == "Second renamed" }.uuid
        val one = before.document.entries.single { it.title() == "Entry <one> ü" }
        val shared = assertNotNull(before.bytes(second, "att.bin"))
        val group = document("kdbx4-aes-aeskdf").groups.single { it.name == "Web & Mail" }.uuid
        val made = Entry.newId()

        // The same bytes as att.bin, twice: no new binary. A new content added and then left by nothing.
        editable.apply(
            VaultEdit.Update(one.uuid, null, EntryChanges(attachments = one.attachments.map { AttachmentChange.Keep(it.name, it.name) } + AttachmentChange.Add("copy.bin", Secret.adopt(shared.copyOf())))),
            now,
        )
        editable.apply(VaultEdit.Create(made, GroupDestination(group), EntryChanges(title = "With file", attachments = listOf(AttachmentChange.Add("id_ed25519", secret("key\n"))))), now)
        editable.apply(VaultEdit.Update(made, null, EntryChanges(attachments = listOf(AttachmentChange.Add("other.txt", secret("other")))) ), now)
        // id_ed25519 stays in the new entry's history.
        val count = editable.binaries.size

        val after = KdbxReader.open(editable.write(key).file, key)
        assertContentEquals(shared, after.bytes(one.uuid, "copy.bin"))
        assertContentEquals("other".encodeToByteArray(), after.bytes(made, "other.txt"))
        assertEquals(before.binaries.size + 2, after.binaries.size, "copy.bin shares att.bin's; the two new ones are kept")
        assertEquals(before.binaries.size + 2, count, "the pool held no second copy of att.bin")
        val sharedRefs = after.document.entries.flatMap { it.attachments }.filter { it.name == "copy.bin" || it.name == "att.bin" }.map { it.ref }.toSet()
        assertEquals(1, sharedRefs.size, "one content for both names")
    }

    @Test
    fun aRemovedAttachmentNothingElseHoldsLeavesTheFile() {
        val editable = open("kdbx4-aes-aeskdf")
        val meta = editable.root.first("Meta")!!
        meta.first("HistoryMaxItems")?.let(meta::remove)
        meta.append(XElement("HistoryMaxItems", "0"))
        val group = document("kdbx4-aes-aeskdf").groups.single { it.name == "Web & Mail" }.uuid
        val id = Entry.newId()
        editable.apply(VaultEdit.Create(id, GroupDestination(group), EntryChanges(title = "Scan", attachments = listOf(AttachmentChange.Add("scan.pdf", secret("only here"))))), now)
        val withScan = KdbxReader.open(editable.write(key).file, key).binaries.size

        editable.apply(VaultEdit.Update(id, null, EntryChanges(attachments = emptyList())), now)

        val after = KdbxReader.open(editable.write(key).file, key)
        assertEquals(withScan - 1, after.binaries.size, "no history keeps it: dropped on the save")
        assertTrue(after.binaries.none { it == secret("only here") })
    }

    @Test
    fun anAttachmentToKeepThatIsGoneChangesNothing() {
        val editable = open("kdbx4-aes-aeskdf")
        val id = document("kdbx4-aes-aeskdf").entry("Second renamed").uuid
        val entry = assertNotNull(EntryEditing.entry(id, editable.root))
        val history = entry.first("History")?.all("Entry")?.size

        assertFailsWith<EditException.AttachmentNotFound> {
            editable.apply(VaultEdit.Update(id, null, EntryChanges(notes = "x", attachments = listOf(AttachmentChange.Keep("nope", "nope")))), now)
        }
        assertEquals(history, entry.first("History")?.all("Entry")?.size, "no version went into the history")
        assertEquals(setOf("blob.bin", "att.bin"), entry.all("Binary").map { it.first("Key")!!.text }.toSet())
    }

    @Test
    fun newAttachmentsNeedTheFilesAttachments() {
        val editable = open("kdbx4-aes-aeskdf")
        val id = document("kdbx4-aes-aeskdf").entry("Second renamed").uuid
        val entry = assertNotNull(EntryEditing.entry(id, editable.root))

        assertFailsWith<EditException.Malformed> {
            EntryEditing.apply(EntryChanges(attachments = listOf(AttachmentChange.Add("a", secret("a")))), entry, editable.root, now)
        }
        val renamed = EntryChanges(attachments = listOf(AttachmentChange.Keep("blob.bin", "blob.dat")))
        EntryEditing.apply(renamed, entry, editable.root, now)
        assertEquals(listOf("blob.dat"), entry.all("Binary").map { it.first("Key")!!.text })
    }

    @Test
    fun theChangesWipeTheirNewContent() {
        val content = secret("scan")
        val changes = EntryChanges(attachments = listOf(AttachmentChange.Add("scan.pdf", content)))

        changes.wipe()

        assertTrue(content.isWiped)
        assertTrue("Add(scan.pdf, 4 bytes)" in changes.toString(), "its size, never its content")
        assertSame(content, (changes.attachments!!.single() as AttachmentChange.Add).content)
    }

    private fun dev.keething.kdbx.xml.KdbxEntry.title(): String? =
        (fields.firstOrNull { it.key == "Title" }?.value as? dev.keething.domain.vault.FieldValue.Plain)?.text
}
