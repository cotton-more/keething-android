package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.vault.opening.EntryVersions
import dev.keething.vault.opening.HistoryResult
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.OpenedVault
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.fixtureBytes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An old version restored through the save path (keething-spec edit.history-restore; the Mac `HistoryTests`, "restoring
 * a version through the saver"). "Second renamed" has blob.bin and att.bin and a history.
 */
class HistoryRestoreSaveTest {
    private val now = Instant.ofEpochSecond(1_950_000_000)

    private val backups = object : DatabaseBackups {
        override fun keep(database: ByteArray, fileName: String, at: Instant) {}

        override fun saved() {}

        override fun notSaved() {}
    }

    private fun key() = DatabaseKey.of(Secret.of("pw"))

    private fun open(store: FakeStore): OpenedVault = assertIs<OpenResult.Opened>(VaultOpener().open(store, key())).opened

    private fun history(store: FakeStore, id: String): EntryVersions =
        assertIs<HistoryResult.Read>(VaultOpener().history(store, key(), id)).versions

    private fun Vault.titled(title: String): Entry = entries.single { it.title == title }

    private fun saved(result: SaveResult): OpenedVault = assertIs<SaveResult.Saved>(result).opened

    private fun failure(result: SaveResult): SaveFailure = assertIs<SaveResult.Failed>(result).failure

    private fun restore(versions: EntryVersions, index: Int) =
        VaultEdit.RestoreVersion(versions.current.id, versions.current.lastModified, index, versions.history[index].lastModified)

    // spec: edit.history-restore/AC3
    @Test
    fun theVersionComesBackWithItsAttachmentsAndNothingElseChanges() {
        val store = FakeStore(fixtureBytes("kdbx4-chacha20-argon2id"))
        val start = open(store)
        val second = start.vault.titled("Second renamed")
        val bytes = second.attachments.associate { it.name to start.attachments.bytes(second.id, it.name)!!.useBytes(ByteArray::copyOf) }
        val saver = VaultSaver(store, backups)
        // A version without the attachments, so restoring the one before brings them back.
        saved(saver.save(VaultEdit.Update(second.id, second.lastModified, EntryChanges(title = "Changed", password = Secret.of("new"), attachments = emptyList())), key(), now))
        val versions = history(store, second.id)
        val previous = versions.history.last()
        assertEquals("Second renamed", previous.title)
        assertTrue(versions.current.attachments.isEmpty())

        val after = saved(saver.save(restore(versions, versions.history.lastIndex), key(), now.plusSeconds(60)))

        val restored = after.vault.entries.single { it.id == second.id }
        assertEquals(previous.copy(groupPath = restored.groupPath, groupId = restored.groupId, lastModified = now.plusSeconds(60)), restored)
        assertEquals(second.created, restored.created)
        for ((name, content) in bytes) assertContentEquals(content, after.attachments.bytes(second.id, name)?.useBytes(ByteArray::copyOf), "$name holds its old bytes")
        assertEquals(start.vault.entries.filter { it.id != second.id }, after.vault.entries.filter { it.id != second.id })
    }

    // spec: edit.history-restore/AC4
    @Test
    fun theReplacedVersionGoesIntoTheHistoryAndRestoringItUndoesTheRestore() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val second = open(store).vault.titled("Second renamed")
        val saver = VaultSaver(store, backups)
        val before = history(store, second.id)

        saved(saver.save(restore(before, 0), key(), now))
        val after = history(store, second.id)
        assertEquals(before.history.size + 1, after.history.size)
        assertEquals(before.current.copy(lastModified = null), after.history.last().copy(lastModified = null), "the version it replaced is the newest kept")

        val undone = saved(saver.save(restore(after, after.history.lastIndex), key(), now.plusSeconds(60))).vault.titled("Second renamed")
        assertEquals(second.copy(lastModified = now.plusSeconds(60)), undone)
    }

    @Test
    fun aVersionGoneAnEntryChangedSinceAndAnEntryInTheRecycleBinAreRefused() {
        val original = fixtureBytes("kdbx4-aes-aeskdf")
        val store = FakeStore(original)
        val second = open(store).vault.titled("Second renamed")
        val saver = VaultSaver(store, backups)
        val versions = history(store, second.id)

        assertEquals(SaveFailure.VersionGone, failure(saver.save(VaultEdit.RestoreVersion(second.id, second.lastModified, versions.history.size, null), key(), now)))
        val other = restore(versions, 0).copy(versionModified = Instant.ofEpochSecond(1))
        assertEquals(SaveFailure.VersionGone, failure(saver.save(other, key(), now)), "another version in its place")
        assertEquals(SaveFailure.ChangedElsewhere, failure(saver.save(restore(versions, 0).copy(loadedModified = Instant.ofEpochSecond(1)), key(), now)))
        assertContentEquals(original, store.content)

        saved(saver.save(VaultEdit.MoveToRecycleBin(second.id, second.lastModified), key(), now))
        val inBin = history(store, second.id)
        assertEquals(SaveFailure.WrongPlace, failure(saver.save(restore(inBin, 0), key(), now)))
    }
}
