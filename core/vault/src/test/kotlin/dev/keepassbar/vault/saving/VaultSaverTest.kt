package dev.keepassbar.vault.saving

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.storage.DatabaseBackups
import dev.keepassbar.domain.storage.DatabaseRead
import dev.keepassbar.domain.storage.DatabaseStore
import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.EntryChanges
import dev.keepassbar.domain.vault.Vault
import dev.keepassbar.domain.vault.VaultEdit
import dev.keepassbar.kdbx.key.DatabaseKey
import dev.keepassbar.vault.opening.OpenFailure
import dev.keepassbar.vault.opening.OpenResult
import dev.keepassbar.vault.opening.VaultOpener
import dev.keepassbar.vault.session.FakeStore
import dev.keepassbar.vault.session.fixtureBytes
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The save path (SPEC §3.3; the Mac `KDBXWriteTests` and `LifecycleTests` saver expectations). */
class VaultSaverTest {
    private val now = Instant.ofEpochSecond(1_900_000_000)

    private class FakeBackups : DatabaseBackups {
        val kept = mutableListOf<ByteArray>()
        var fail = false

        override fun keep(database: ByteArray, fileName: String, at: Instant) {
            if (fail) throw IOException("no room")
            kept += database.copyOf()
        }
    }

    private val backups = FakeBackups()

    private fun key() = DatabaseKey.fromPassword(Secret.of("pw"))

    /** The vault as an unlock would show it, with the key that opened it (its derived key kept). */
    private fun unlock(store: FakeStore, key: DatabaseKey = key()): Vault =
        assertIs<OpenResult.Opened>(VaultOpener().open(store, key)).opened.vault

    private fun Vault.titled(title: String): Entry = (entries + recycleBin).single { it.title == title }

    private fun saved(result: SaveResult): Vault = assertIs<SaveResult.Saved>(result).opened.vault

    private fun failure(result: SaveResult): SaveFailure = assertIs<SaveResult.Failed>(result).failure

    @Test
    fun anEditLandsInTheFileTheOldFileIsBackedUpAndTheSavedVaultIsWhatIsOnDisk() {
        val original = fixtureBytes("kdbx4-chacha20-argon2id")
        val store = FakeStore(original)
        val key = key()
        val entry = unlock(store, key).titled("Second renamed")

        val vault = saved(VaultSaver(store, backups).save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(username = "new-user")), key, now))

        assertEquals("new-user", vault.titled("Second renamed").username)
        assertEquals(unlock(store), vault, "the saved vault is the file read back")
        assertEquals(1, store.replaced)
        assertEquals(1, backups.kept.size)
        assertContentEquals(original, backups.kept.single())
    }

    @Test
    fun aSaveDerivesNothingAndTheFileStillOpensWithThePassword() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val key = key()
        val entry = unlock(store, key).titled("Entry <one> ü")

        val result = VaultSaver(store, backups).save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "n")), key, now)

        assertNull(assertIs<SaveResult.Saved>(result).opened.keyDerivation)
        assertEquals("n", unlock(store, key()).titled("Entry <one> ü").notes, "a fresh key derives and opens it")
    }

    @Test
    fun refusedWhenTheEntryChangedOrVanishedElsewhereOrTheKeyNoLongerOpensItAndTheFileStays() {
        val original = fixtureBytes("kdbx4-aes-aeskdf")
        val store = FakeStore(original)
        val key = key()
        val entry = unlock(store, key).entries.first()
        val saver = VaultSaver(store, backups)

        assertEquals(SaveFailure.ChangedElsewhere, failure(saver.save(VaultEdit.Update(entry.id, Instant.EPOCH, EntryChanges(notes = "x")), key, now)))
        assertEquals(SaveFailure.EntryNotFound, failure(saver.save(VaultEdit.Update("gone", null, EntryChanges(notes = "x")), key, now)))
        assertEquals(
            SaveFailure.CannotOpen(OpenFailure.WrongPassword),
            failure(saver.save(VaultEdit.Update(entry.id, null, EntryChanges(notes = "x")), DatabaseKey.fromPassword(Secret.of("nope")), now)),
        )
        assertEquals(SaveFailure.CannotOpen(OpenFailure.Missing), failure(VaultSaver(FakeStore(null), backups).save(VaultEdit.Update(entry.id, null, EntryChanges()), key, now)))
        assertContentEquals(original, store.content)
        assertEquals(0, store.replaced)
        assertTrue(backups.kept.isEmpty(), "no backup without a save")
    }

    @Test
    fun createRecycleAndRestoreLandInTheFileWithABackupEach() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val key = key()
        val start = unlock(store, key)
        val group = start.groups.single { it.name == "Web & Mail" }
        val saver = VaultSaver(store, backups)
        val id = Entry.newId()

        val created = saved(saver.save(VaultEdit.Create(id, group.id, EntryChanges(title = "Fresh", password = Secret.of("pw1"))), key, now))
            .titled("Fresh")
        assertEquals(Secret.of("pw1"), created.password)
        assertEquals(group.id, created.groupId)

        assertEquals(SaveFailure.ChangedElsewhere, failure(saver.save(VaultEdit.MoveToRecycleBin(id, Instant.EPOCH), key, now)))
        val recycled = saved(saver.save(VaultEdit.MoveToRecycleBin(id, created.lastModified), key, now)).recycleBin.single { it.id == id }
        assertEquals(listOf("Recycle Bin"), recycled.groupPath)
        assertEquals(SaveFailure.WrongPlace, failure(saver.save(VaultEdit.MoveToRecycleBin(id, recycled.lastModified), key, now)))

        val back = saved(saver.save(VaultEdit.Restore(id, recycled.lastModified), key, now)).entries.single { it.id == id }
        assertEquals(group.id, back.groupId)
        assertEquals(SaveFailure.WrongPlace, failure(saver.save(VaultEdit.Restore(id, back.lastModified), key, now)))
        assertEquals(SaveFailure.GroupNotFound, failure(saver.save(VaultEdit.Create(Entry.newId(), "gone", EntryChanges(title = "x")), key, now)))

        val end = unlock(store)
        assertEquals(start.entries, end.entries.filter { it.id != id }, "the other entries are as they were")
        assertEquals(3, backups.kept.size, "one per save: create, recycle, restore")
    }

    @Test
    fun fieldTypesSavedWithAnEntryComeBackAndAreChecked() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val key = key()
        val root = unlock(store, key).groups.single { it.isRoot }
        val saver = VaultSaver(store, backups)
        val id = Entry.newId()
        fun field(name: String, value: String) = CustomField.plain(name, value)

        val created = saved(
            saver.save(
                VaultEdit.Create(id, root.id, EntryChanges(title = "Pass", customFields = listOf(field("Ablauf", "2027-10-31")), fieldTypes = mapOf("Ablauf" to "until"))),
                key, now,
            ),
        ).titled("Pass")
        assertEquals(mapOf("Ablauf" to "until"), created.fieldTypes)

        val changes = EntryChanges(customFields = listOf(field("Ablauf", "2027-10-31"), field("Nr", "1")), fieldTypes = mapOf("Ablauf" to "date", "Nr" to "text"))
        val edited = saved(saver.save(VaultEdit.Update(id, created.lastModified, changes), key, now)).titled("Pass")
        assertEquals(mapOf("Ablauf" to "date", "Nr" to "text"), edited.fieldTypes)

        val cleared = saved(saver.save(VaultEdit.Update(id, edited.lastModified, EntryChanges(fieldTypes = emptyMap())), key, now)).titled("Pass")
        assertEquals(emptyMap(), cleared.fieldTypes)
        val afterNotes = saved(saver.save(VaultEdit.Update(id, cleared.lastModified, EntryChanges(notes = "n")), key, now))
        assertEquals(unlock(store), afterNotes, "an edit without types leaves them out")
    }

    @Test
    fun aFileChangedDuringTheSaveIsReadAgainAndTheEditAppliedOnTopOfIt() {
        val key = key()
        // Another device changed another entry meanwhile.
        val elsewhere = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val other = unlock(elsewhere, key).titled("Second renamed")
        saved(VaultSaver(elsewhere, FakeBackups()).save(VaultEdit.Update(other.id, other.lastModified, EntryChanges(notes = "from the Mac")), key, now))
        val store = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val mine = unlock(store, key).titled("Entry <one> ü")
        store.beforeRead = { read -> if (read == 3) store.change(elsewhere.content) }

        val vault = saved(VaultSaver(store, backups).save(VaultEdit.Update(mine.id, mine.lastModified, EntryChanges(notes = "from the phone")), key, now))

        assertEquals("from the phone", vault.titled("Entry <one> ü").notes)
        assertEquals("from the Mac", vault.titled("Second renamed").notes, "the other change is kept")
        assertContentEquals(elsewhere.content, backups.kept.single(), "the backup is the file it replaced")
    }

    @Test
    fun aFileThatKeepsChangingIsGivenUpOn() {
        val key = key()
        val first = fixtureBytes("kdbx4-aes-aeskdf")
        val elsewhere = FakeStore(first)
        val entry = unlock(elsewhere, key).entries.first()
        saved(VaultSaver(elsewhere, FakeBackups()).save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "v2")), key, now))
        val second = elsewhere.content!!
        val store = FakeStore(first)
        unlock(store, key)
        // Every check finds the other version.
        store.beforeRead = { read -> store.change(if (read % 2 == 0) first else second, notify = false) }

        assertEquals(SaveFailure.FileKeepsChanging, failure(VaultSaver(store, backups).save(VaultEdit.Update(entry.id, null, EntryChanges(notes = "x")), key, now)))
        assertEquals(0, store.replaced)
        assertTrue(backups.kept.isEmpty())
    }

    @Test
    fun aFailedBackupOrWriteIsReportedAndABackupFailureWritesNothing() {
        val original = fixtureBytes("kdbx4-aes-aeskdf")
        val store = FakeStore(original)
        val key = key()
        val entry = unlock(store, key).entries.first()
        val saver = VaultSaver(store, backups)
        val edit = VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "x"))

        backups.fail = true
        assertIs<SaveFailure.WriteFailed>(failure(saver.save(edit, key, now)))
        assertContentEquals(original, store.content)

        backups.fail = false
        store.failNextReplace = true
        assertIs<SaveFailure.WriteFailed>(failure(saver.save(edit, key, now)))
        assertEquals(0, store.replaced)
    }

    @Test
    fun aFileThatCannotBeReadIsAFailureNotACrash() {
        val store = object : DatabaseStore by FakeStore(fixtureBytes("kdbx4-aes-aeskdf")) {
            override fun read(): DatabaseRead = throw IOException("I/O error")
        }

        assertEquals(
            SaveFailure.WriteFailed("I/O error"),
            failure(VaultSaver(store, backups).save(VaultEdit.Update("any", null, EntryChanges(notes = "x")), key(), now)),
        )
    }
}
