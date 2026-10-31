package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.TotpChange
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.editing.BrokenFiles
import dev.keething.kdbx.editing.CodeFiles
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.schema.SchemaCheck
import dev.keething.totp.TotpSetup
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.fixtureBytes
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

    private fun key() = DatabaseKey.of(Secret.of("pw"))

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
            failure(saver.save(VaultEdit.Update(entry.id, null, EntryChanges(notes = "x")), DatabaseKey.of(Secret.of("nope")), now)),
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

        val created = saved(saver.save(VaultEdit.Create(id, GroupDestination(group.id), EntryChanges(title = "Fresh", password = Secret.of("pw1"))), key, now))
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
        assertEquals(SaveFailure.GroupNotFound, failure(saver.save(VaultEdit.Create(Entry.newId(), GroupDestination("gone"), EntryChanges(title = "x")), key, now)))

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
                VaultEdit.Create(id, GroupDestination(root.id), EntryChanges(title = "Pass", customFields = listOf(field("Ablauf", "2027-10-31")), fieldTypes = mapOf("Ablauf" to "until"))),
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
    fun aNewEntryWithAOneTimeCodeIsSavedAndChecked() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val key = key()
        val root = unlock(store, key).groups.single { it.isRoot }
        val config = TotpConfig(Secret.of("12345678901234567890"), digits = 8, period = 60, algorithm = TotpConfig.Algorithm.SHA256)
        val uri = "otpauth://totp/Coded:me?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&period=60&digits=8&issuer=Coded&algorithm=SHA256"

        val created = saved(
            VaultSaver(store, backups).save(VaultEdit.Create(Entry.newId(), GroupDestination(root.id), EntryChanges(title = "Coded", totp = TotpChange.Set(config, uri))), key, now),
        ).titled("Coded")

        assertEquals(config, created.totp)
        assertEquals(config, unlock(store).titled("Coded").totp)
    }

    @Test
    fun oneTimeCodesInKeePasssOwnFieldsAreReadKeptAndTheDatabasesFormatIsWritten() {
        val key = key()
        val ids = unlock(FakeStore(fixtureBytes("kdbx4-aes-argon2d")), key).entries.map { it.id }
        assertTrue(ids.size >= 2, "two entries at least")
        val sha256 = "12345678901234567890123456789012"
        val hex = sha256.encodeToByteArray().joinToString("") { "%02x".format(it) }
        val store = FakeStore(
            CodeFiles.withCodeFields(
                fixtureBytes("kdbx4-aes-argon2d"), key, ids[0],
                listOf("TimeOtp-Secret-Hex" to hex, "TimeOtp-Length" to "8", "TimeOtp-Algorithm" to "HMAC-SHA-256"),
            ),
        )
        val saver = VaultSaver(store, backups)

        // AC6: KeePass's fields are the entry's code, and no custom fields.
        val keePass = unlock(store, key).entries.single { it.id == ids[0] }
        assertEquals(TotpConfig(Secret.of(sha256), digits = 8, algorithm = TotpConfig.Algorithm.SHA256), keePass.totp)
        assertTrue(keePass.customFields.none { it.name.startsWith("TimeOtp-") })

        // AC5: a save that does not change the code leaves its fields as they were.
        val before = CodeFiles.codeFields(store.content!!, key, ids[0])
        saved(saver.save(VaultEdit.Update(ids[0], keePass.lastModified, EntryChanges(title = "Renamed")), key, now))
        assertEquals(before, CodeFiles.codeFields(store.content!!, key, ids[0]))

        // AC7: a new code on an entry without one takes the database's format, KeePass's; the save's check reads it back.
        val second = unlock(store, key).entries.single { it.id == ids[1] }
        val code = TotpConfig(Secret.of("12345678901234567890"), period = 60)
        val link = TotpSetup.otpauthUri(code, second.title, second.username)
        val coded = saved(saver.save(VaultEdit.Update(ids[1], second.lastModified, EntryChanges(totp = TotpChange.Set(code, link))), key, now))
            .entries.single { it.id == ids[1] }
        assertEquals(code, coded.totp)
        assertEquals(
            listOf("TimeOtp-Secret-Base32=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ*", "TimeOtp-Length=6", "TimeOtp-Period=60", "TimeOtp-Algorithm=HMAC-SHA-1"),
            CodeFiles.codeFields(store.content!!, key, ids[1]),
        )

        // AC3, AC4: changed to what KeePass cannot hold (10 digits), it is one otp link, KeePass's fields gone.
        val long = TotpConfig(Secret.of("12345678901234567890"), digits = 10)
        val longLink = TotpSetup.otpauthUri(long, coded.title, coded.username)
        val relinked = saved(saver.save(VaultEdit.Update(ids[1], coded.lastModified, EntryChanges(totp = TotpChange.Set(long, longLink))), key, now))
        assertEquals(listOf("otp=$longLink*"), CodeFiles.codeFields(store.content!!, key, ids[1]))
        assertEquals(long, relinked.entries.single { it.id == ids[1] }.totp)
    }

    @Test
    fun aSaveThatWouldAddWhatKeePasssSchemaDoesNotAllowIsRefusedAndNothingIsReplaced() {
        // The root group's custom icon is not in the file: a new entry would take it and refer to an icon that is not there.
        SchemaCheck.allowViolations()
        val (broken, groupId) = BrokenFiles.rootGroupWithAMissingIcon(fixtureBytes("kdbx4-aes-argon2d"), key())
        val store = FakeStore(broken)
        val key = key()
        val saver = VaultSaver(store, backups)

        val refused = failure(saver.save(VaultEdit.Create(Entry.newId(), GroupDestination(groupId), EntryChanges(title = "New")), key, now))

        assertEquals(SaveFailure.BreaksFormat("an entry's CustomIconUUID: no such custom icon"), refused)
        assertContentEquals(broken, store.content)
        assertEquals(0, store.replaced)
        assertTrue(backups.kept.isEmpty(), "no backup without a save")

        // The violation the file already had is no reason to refuse another change.
        val entry = unlock(store, key).entries.first()
        val checked = SchemaCheck.checked
        assertEquals("n", saved(saver.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "n")), key, now)).titled(entry.title).notes)
        assertTrue(SchemaCheck.checked > checked, "the written file was checked against KDBX_XML.xsd (JUnit's extension auto-detection)")
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
