package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.storage.DatabaseChangedException
import dev.keething.domain.storage.DatabaseReplacedException
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.editing.HostileFiles
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.fixtureBytes
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** keething-spec kdbx.hardening through the save path and the unlock: the review's recipes on the phone's saver. */
class SaveHardeningTest {
    private val now = Instant.ofEpochSecond(1_900_000_000)

    /** The backups folder as the saves leave it: a copy kept stays only once its save said it replaced the file. */
    private class Backups : DatabaseBackups {
        val folder = mutableListOf<ByteArray>()
        private var pending: ByteArray? = null

        override fun keep(database: ByteArray, fileName: String, at: Instant) {
            pending = database.copyOf().also(folder::add)
        }

        override fun saved() {
            pending = null
        }

        override fun notSaved() {
            pending?.let { kept -> folder.removeIf { it === kept } }
            pending = null
        }
    }

    private val backups = Backups()

    private fun key() = DatabaseKey.of(Secret.of("pw"))

    private fun unlock(store: DatabaseStore, key: DatabaseKey = key()): Vault = assertIs<OpenResult.Opened>(VaultOpener().open(store, key)).opened.vault

    private fun failure(result: SaveResult): SaveFailure = assertIs<SaveResult.Failed>(result).failure

    // spec: kdbx.hardening/AC2
    @Test
    fun aSaveWithAKeyALockWipedWritesNothing() {
        val original = fixtureBytes("kdbx4-aes-aeskdf")
        val store = FakeStore(original)
        val key = key()
        val entry = unlock(store, key).entries.first()
        key.wipe()

        val result = VaultSaver(store, backups).save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "x")), key, now)

        assertEquals(SaveFailure.Locked, failure(result))
        assertContentEquals(original, store.content)
        assertEquals(0, store.replaced)
        assertTrue(backups.folder.isEmpty())
    }

    // spec: kdbx.hardening/AC2
    @Test
    fun anUnlockOrAReloadWithAWipedKeyReadsNothingAndThrowsNothing() {
        val key = key().also(DatabaseKey::wipe)

        assertEquals(OpenFailure.Locked, assertIs<OpenResult.Failed>(VaultOpener().open(FakeStore(fixtureBytes("kdbx4-aes-aeskdf")), key)).failure)
    }

    // spec: kdbx.hardening/AC7
    @Test
    fun aPlainValueWithCharactersXmlCannotHoldIsSavedWithoutThemNotRefused() {
        // L6: on the Mac the save failed with "XML error 111"; KeePass 2.x and KeePassXC leave them out.
        val store = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val key = key()
        val entry = unlock(store, key).entries.first()
        val changes = EntryChanges(
            title = "Git\u000BHub\u0001",
            notes = "a\u001Bb\tc",
            notesProtected = false,
            customFields = listOf(CustomField.plain("Re\u0007gion", "eu￿"), CustomField("PIN", FieldValue.Protected(Secret.of("1\u00012")))),
            tags = listOf("wo\u0002rk", "\u0003"),
        )

        val saved = assertIs<SaveResult.Saved>(VaultSaver(store, backups).save(VaultEdit.Update(entry.id, entry.lastModified, changes), key, now)).opened.vault

        val after = saved.entries.single { it.id == entry.id }
        assertEquals("GitHub", after.title)
        assertEquals("ab\tc", after.notes)
        assertEquals(listOf(CustomField.plain("Region", "eu"), CustomField("PIN", FieldValue.Protected(Secret.of("1\u00012")))), after.customFields)
        assertEquals(listOf("work"), after.tags)
        assertEquals(after, unlock(store).entries.single { it.id == entry.id }, "the file holds what the save said")
    }

    // spec: kdbx.hardening/AC7
    @Test
    fun aNewEntryAndANewGroupWithSuchCharactersAreSavedToo() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val key = key()
        val top = unlock(store, key).groups.single { it.isRoot }
        val group = NewGroup(dev.keething.domain.vault.Entry.newId(), "Wo\u0001rk")
        val id = dev.keething.domain.vault.Entry.newId()

        val created = VaultSaver(store, backups).save(
            VaultEdit.Create(id, GroupDestination(top.id, listOf(group)), EntryChanges(title = "New\u0000", password = Secret.of("p\u0000w"))),
            key, now,
        )

        val vault = assertIs<SaveResult.Saved>(created).opened.vault
        val entry = vault.entries.single { it.id == id }
        assertEquals("New", entry.title)
        assertEquals("p\u0000w", entry.password.reveal(), "a protected value keeps every character")
        assertEquals(listOf("Work"), entry.groupPath)
        val renamed = VaultSaver(store, backups).save(VaultEdit.RenameGroup(group.id, "Ho\u001Fme"), key, now)
        assertEquals(listOf("Home"), assertIs<SaveResult.Saved>(renamed).opened.vault.groups.single { it.id == group.id }.path)
    }

    // spec: kdbx.hardening/AC8
    @Test
    fun aTimeADateCannotHoldOpensAsNoTimeAndTheEntryIsSaved() {
        // M6: on the Mac every unlock crashed.
        val key = key()
        val (file, id) = HostileFiles.withEntryTime(fixtureBytes("kdbx4-aes-aeskdf"), key, "AAAAAAAAAIA=")
        val store = FakeStore(file)

        val entry = unlock(store, key).entries.single { it.id == id }
        assertNull(entry.lastModified)

        val saved = VaultSaver(store, backups).save(VaultEdit.Update(id, entry.lastModified, EntryChanges(notes = "edited")), key, now)
        assertEquals("edited", assertIs<SaveResult.Saved>(saved).opened.vault.entries.single { it.id == id }.notes)
    }

    // spec: kdbx.hardening/AC8
    @Test
    fun theDeepestNestingTheAppReadsItSavesOnAStackAQuarterOfAnAndroidThreads() {
        // M7: depth 2,000 crashed the Mac's first save on a 512 KiB stack. Android gives a thread 1 MiB.
        val key = key()
        val (file, innermost) = HostileFiles.nested(fixtureBytes("kdbx4-aes-aeskdf"), key, HostileFiles.MAX_DEPTH)
        val store = FakeStore(file)
        val vault = unlock(store, key)
        val entry = vault.entries.first()
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<SaveResult?>()

        val thread = Thread(null, {
            try {
                val saver = VaultSaver(store, backups)
                saver.save(VaultEdit.RenameGroup(innermost, "Deepest"), key, now).also { assertIs<SaveResult.Saved>(it) }
                result.set(saver.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "deep")), key, now))
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }, "small stack", 256L * 1024)
        thread.start()
        thread.join()

        failure.get()?.let { throw it }
        assertIs<SaveResult.Saved>(result.get())
        assertEquals("Deepest", unlock(store, key).groups.single { it.id == innermost }.name)
    }

    // spec: kdbx.hardening/AC8
    @Test
    fun nestingDeeperThanTheAppWritesIsRefusedAtTheUnlockAsDamaged() {
        val key = key()
        val (file, _) = HostileFiles.nested(fixtureBytes("kdbx4-aes-aeskdf"), key, HostileFiles.MAX_DEPTH + 1)

        val failure = assertIs<OpenResult.Failed>(VaultOpener().open(FakeStore(file), key)).failure

        assertEquals(OpenFailure.Damaged("XML nested deeper than ${HostileFiles.MAX_DEPTH} levels"), failure)
    }

    // spec: kdbx.hardening/AC6
    @Test
    fun anUnlockOfAFileOutsideTheLimitsSaysUnsupportedAndNamesTheParameter() {
        val file = HostileFiles.withKdfParameters(fixtureBytes("kdbx4-aes-argon2d"), HostileFiles.argon2(memoryBytes = 4L shl 40))

        val failure = assertIs<OpenResult.Failed>(VaultOpener().open(FakeStore(file), key())).failure

        assertEquals(OpenFailure.Unsupported("key derivation Argon2 memory of 4 TiB (KeePass reads less than 2 GiB)", OpenFailure.Unsupported.Kind.KEY_DERIVATION), failure)
    }

    // AC9: a failed save and the backups

    /** A save that went through, then one whose replace fails so: the result, and the backups before and after it. */
    private fun saveFailing(replace: FakeStore.(ByteArray, FileVersion?) -> Unit): Triple<SaveResult, List<ByteArray>, List<ByteArray>> {
        val backups = Backups()
        val store = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val key = key()
        val entry = unlock(store, key).entries.first()
        val one = assertIs<SaveResult.Saved>(VaultSaver(store, backups).save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "one")), key, now))
        val loaded = one.opened.vault.entries.single { it.id == entry.id }.lastModified
        val before = backups.folder.toList()
        val failing = object : DatabaseStore by store {
            override fun replace(bytes: ByteArray, expected: FileVersion?) = replace(store, bytes, expected)
        }
        val result = VaultSaver(failing, backups).save(VaultEdit.Update(entry.id, loaded, EntryChanges(notes = "two")), key, now)
        return Triple(result, before, backups.folder.toList())
    }

    // spec: kdbx.hardening/AC9
    @Test
    fun aSaveThatFailsLeavesTheBackupsAsTheyWere() {
        val (failed, before, after) = saveFailing { _, _ -> throw IOException("disk full") }
        assertIs<SaveFailure.WriteFailed>(failure(failed))
        assertEquals(1, before.size)
        assertEquals(before, after, "none added, none pushed out")

        // Changed by a sync at the last moment, twice: read again each time, and each try's copy taken back.
        val (changing, beforeChanging, afterChanging) = saveFailing { _, _ -> throw DatabaseChangedException("a sync") }
        assertEquals(SaveFailure.FileKeepsChanging, failure(changing))
        assertEquals(beforeChanging, afterChanging)
    }

    // spec: kdbx.hardening/AC9
    @Test
    fun aSaveThatReplacedTheFileButCouldNotMakeSureOfItsTimeKeepsItsBackup() {
        val (saved, before, after) = saveFailing { bytes, expected ->
            replace(bytes, expected)
            throw DatabaseReplacedException("less than 2 s")
        }

        // Saved, the vault the file read back, and said with a warning (databases.saf AC6).
        val result = assertIs<SaveResult.Saved>(saved)
        assertTrue(result.mayNotSync)
        assertEquals("two", result.opened.vault.entries.first().notes)
        assertEquals(before.size + 1, after.size, "the replaced file's backup")

        // Gone right after the replace: nothing to be the vault, a failure.
        val (gone, _, _) = saveFailing { _, _ ->
            change(null, notify = false)
            throw DatabaseReplacedException("gone right after the save")
        }
        assertEquals(SaveFailure.WriteFailed("the new file is gone"), failure(gone))
    }
}
