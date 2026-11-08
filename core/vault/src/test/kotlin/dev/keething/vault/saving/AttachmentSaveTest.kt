package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.AttachmentChange
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.OpenedVault
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.fixtureBytes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Attachments added, renamed and removed through the save path (keething-spec edit.attachments AC4–AC6; the Mac
 * `AttachmentEditingTests` "saver"). "Second renamed" has blob.bin (2000 bytes) and att.bin (3000 bytes).
 */
class AttachmentSaveTest {
    private val now = Instant.ofEpochSecond(1_900_000_000)

    private val backups = object : DatabaseBackups {
        override fun keep(database: ByteArray, fileName: String, at: Instant) {}

        override fun saved() {}

        override fun notSaved() {}
    }

    private fun key() = DatabaseKey.of(Secret.of("pw"))

    private fun open(store: FakeStore): OpenedVault = assertIs<OpenResult.Opened>(VaultOpener().open(store, key())).opened

    private fun Vault.titled(title: String): Entry = entries.single { it.title == title }

    private fun saved(result: SaveResult): OpenedVault = assertIs<SaveResult.Saved>(result).opened

    private fun OpenedVault.bytes(entryId: String, name: String): ByteArray? = attachments.bytes(entryId, name)?.useBytes(ByteArray::copyOf)

    private fun binaryCount(store: FakeStore): Int = KdbxReader.open(store.content!!, key()).binaries.size

    // spec: edit.attachments/AC6
    @Test
    fun theSameBytesAsAnotherAttachmentAddNoContent() {
        val store = FakeStore(fixtureBytes("kdbx4-chacha20-argon2id"))
        val start = open(store)
        val one = start.vault.titled("Entry <one> ü")
        val shared = start.bytes(start.vault.titled("Second renamed").id, "att.bin")!!
        val count = binaryCount(store)
        val keep = one.attachments.map { AttachmentChange.Keep(it.name, it.name) }

        val after = saved(
            VaultSaver(store, backups).save(
                VaultEdit.Update(one.id, one.lastModified, EntryChanges(attachments = keep + AttachmentChange.Add("copy.bin", Secret.adopt(shared.copyOf())))),
                key(),
                now,
            ),
        )

        assertEquals(count, binaryCount(store))
        assertEquals(one.attachments + Attachment("copy.bin", shared.size), after.vault.titled("Entry <one> ü").attachments)
        assertContentEquals(shared, after.bytes(one.id, "copy.bin"), "the saved vault opens it at once")
    }

    // spec: edit.attachments/AC5
    @Test
    fun aNewEntrysAttachmentAndAnEntrysRemovedOnesLandInTheFile() {
        val store = FakeStore(fixtureBytes("kdbx4-chacha20-argon2id"))
        val start = open(store)
        val group = start.vault.groups.single { it.name == "Web & Mail" }
        val id = Entry.newId()
        val saver = VaultSaver(store, backups)

        val created = saved(
            saver.save(VaultEdit.Create(id, GroupDestination(group.id), EntryChanges(title = "With file", attachments = listOf(AttachmentChange.Add("id_ed25519", Secret.of("key\n"))))), key(), now),
        )
        assertEquals(listOf(Attachment("id_ed25519", 4)), created.vault.entries.single { it.id == id }.attachments)
        assertContentEquals("key\n".encodeToByteArray(), open(store).bytes(id, "id_ed25519"))

        val second = created.vault.titled("Second renamed")
        val count = binaryCount(store)
        val emptied = saved(saver.save(VaultEdit.Update(second.id, second.lastModified, EntryChanges(attachments = emptyList())), key(), now))
        assertTrue(emptied.vault.titled("Second renamed").attachments.isEmpty())
        assertEquals(count, binaryCount(store), "its old version keeps them")
    }

    // spec: edit.attachments/AC5
    @Test
    fun aRenameKeepsTheBytesAndAnAttachmentToKeepThatIsGoneIsRefused() {
        val original = fixtureBytes("kdbx4-aes-aeskdf")
        val store = FakeStore(original)
        val start = open(store)
        val second = start.vault.titled("Second renamed")
        val blob = start.bytes(second.id, "blob.bin")!!
        val saver = VaultSaver(store, backups)

        val failed = saver.save(VaultEdit.Update(second.id, second.lastModified, EntryChanges(attachments = listOf(AttachmentChange.Keep("nope", "nope")))), key(), now)
        assertEquals(SaveFailure.VerificationFailed("an attachment to keep is gone"), assertIs<SaveResult.Failed>(failed).failure)
        assertContentEquals(original, store.content)

        val renamed = saved(
            saver.save(
                VaultEdit.Update(second.id, second.lastModified, EntryChanges(attachments = listOf(AttachmentChange.Keep("blob.bin", "scan 2.pdf")))),
                key(),
                now,
            ),
        )
        assertEquals(listOf(Attachment("scan 2.pdf", 2000)), renamed.vault.titled("Second renamed").attachments)
        assertContentEquals(blob, renamed.bytes(second.id, "scan 2.pdf"))
    }

    @Test
    fun theCheckRefusesAnAttachmentWhoseBytesAreNotTheOnesAsked() {
        val database = KdbxReader.open(fixtureBytes("kdbx4-aes-aeskdf"), key())
        val document = database.document
        val id = document.entries.single { it.attachments.any { a -> a.name == "att.bin" } }.uuid
        fun check(vararg changes: AttachmentChange) =
            SaveCheck.verifyAttachmentContents(VaultEdit.Update(id, null, EntryChanges(attachments = changes.toList())), document, database.binaries, document, database.binaries)

        check(AttachmentChange.Keep("att.bin", "att.bin"), AttachmentChange.Keep("blob.bin", "blob.bin"))
        assertEquals("an attachment's content differs", assertFailsWith<SaveCheck.Failed> { check(AttachmentChange.Add("att.bin", Secret.of("other"))) }.detail)
        assertEquals("an attachment's content differs", assertFailsWith<SaveCheck.Failed> { check(AttachmentChange.Keep("blob.bin", "att.bin")) }.detail)
        assertEquals("an attachment is missing", assertFailsWith<SaveCheck.Failed> { check(AttachmentChange.Keep("att.bin", "new.bin")) }.detail)
    }
}
