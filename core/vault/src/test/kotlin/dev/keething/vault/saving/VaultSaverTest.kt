package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.storage.DatabaseChangedException
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseReading
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.TotpChange
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import dev.keething.kdbx.editing.BrokenFiles
import dev.keething.kdbx.editing.CodeFiles
import dev.keething.kdbx.editing.HostileFiles
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.schema.SchemaCheck
import dev.keething.totp.TotpSetup
import dev.keething.vault.opening.HistoryResult
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.fixtureBytes
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

/** The save path (SPEC §3.3; the Mac `KDBXWriteTests` and `LifecycleTests` saver expectations). */
class VaultSaverTest {
    private val now = Instant.ofEpochSecond(1_900_000_000)

    private class FakeBackups : DatabaseBackups {
        val kept = mutableListOf<ByteArray>()
        var fail = false

        /** Runs while a backup is written (the how-manieth), the last thing before the replace: another device changing the file then. */
        var whileKeeping: (Int) -> Unit = {}
        private var keeps = 0

        /** The last one kept is the running save's until it says how it went. */
        private var pending = false

        override fun keep(database: ByteArray, fileName: String, at: Instant) {
            if (fail) throw IOException("no room")
            kept += database.copyOf()
            pending = true
            whileKeeping(++keeps)
        }

        override fun saved() {
            pending = false
        }

        // As the app's: the running save's copy goes.
        override fun notSaved() {
            if (pending) kept.removeAt(kept.lastIndex)
            pending = false
        }

        var isOn = true
        override val on: Boolean get() = isOn

        // As the app's: a copy of the same bytes, by its SHA-256.
        override fun holds(sha256: ByteArray): Boolean = kept.any { MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(it), sha256) }
    }

    private val backups = FakeBackups()

    private fun key() = DatabaseKey.of(Secret.of("pw"))

    /** The vault as an unlock would show it, with the key that opened it (its derived key kept). */
    private fun unlock(store: FakeStore, key: DatabaseKey = key()): Vault =
        assertIs<OpenResult.Opened>(VaultOpener().open(store, key)).opened.vault

    private fun Vault.titled(title: String): Entry = (entries + recycleBin).single { it.title == title }

    private fun saved(result: SaveResult): Vault = assertIs<SaveResult.Saved>(result).opened.vault

    private fun failure(result: SaveResult): SaveFailure = assertIs<SaveResult.Failed>(result).failure

    private fun deletedBy(result: SaveResult): List<VaultGroup> = assertIs<SaveResult.Saved>(result).deletedGroups

    /** The file's `DeletedObjects` records, in order. */
    private fun deletedObjects(store: FakeStore): List<String> {
        val editable = KdbxReader.openForEditing(store.content!!, key())
        try {
            val xml = editable.xmlWithoutSecrets()
            val records = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream()).getElementsByTagName("DeletedObject")
            return (0 until records.length).map { (records.item(it) as Element).getElementsByTagName("UUID").item(0).textContent }
        } finally {
            editable.wipe()
        }
    }

    /** How many old versions the entry's history holds. */
    private fun historySize(store: FakeStore, id: String): Int =
        assertIs<HistoryResult.Read>(VaultOpener().history(store, key(), id)).versions.history.size

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
    fun aTimeOnOnlyOneSideIsChangedElsewhereAndATimeIsComparedToTheSecond() {
        val key = key()
        // The first entry's time written as one no date holds: read as no time (kdbx.hardening AC8).
        val (file, timeless) = HostileFiles.withEntryTime(fixtureBytes("kdbx4-aes-aeskdf"), key, "AAAAAAAAAIA=")
        val store = FakeStore(file)
        val timed = unlock(store, key).entries.first { it.id != timeless }
        val loaded = assertNotNull(timed.lastModified)
        val saver = VaultSaver(store, backups)

        assertEquals(SaveFailure.ChangedElsewhere, failure(saver.save(VaultEdit.Update(timeless, loaded, EntryChanges(notes = "x")), key, now)), "no time in the file")
        assertEquals(SaveFailure.ChangedElsewhere, failure(saver.save(VaultEdit.Update(timed.id, null, EntryChanges(notes = "x")), key, now)), "no time loaded")
        assertEquals(SaveFailure.ChangedElsewhere, failure(saver.save(VaultEdit.Update(timed.id, loaded.plusSeconds(1), EntryChanges(notes = "x")), key, now)))
        assertContentEquals(file, store.content)

        saved(saver.save(VaultEdit.Update(timeless, null, EntryChanges(notes = "neither")), key, now))
        saved(saver.save(VaultEdit.Update(timed.id, loaded.plusMillis(999), EntryChanges(notes = "same second")), key, now))
    }

    @Test
    fun aChangeToAnEntryWhoseIdIsNotUniqueIsRefusedSayingSo() {
        SchemaCheck.allowEntryUuidTwice()
        val key = key()
        val (file, id) = HostileFiles.withEntryTwice(fixtureBytes("kdbx4-aes-aeskdf"), key)
        val store = FakeStore(file)
        val vault = unlock(store, key)
        val twins = vault.entries.filter { it.id == id }
        assertEquals(2, twins.size, "the file opens with both")
        val saver = VaultSaver(store, backups)

        for (twin in twins) {
            assertEquals(SaveFailure.DuplicateId, failure(saver.save(VaultEdit.Update(id, twin.lastModified, EntryChanges(notes = "x")), key, now)))
            assertEquals(SaveFailure.DuplicateId, failure(saver.save(VaultEdit.MoveToRecycleBin(id, twin.lastModified), key, now)))
        }
        assertContentEquals(file, store.content)

        val other = vault.entries.first { it.id != id }
        saved(saver.save(VaultEdit.Update(other.id, other.lastModified, EntryChanges(notes = "x")), key, now))
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
        assertEquals(1, backups.kept.size, "one a session: the file as it was before the first save")
        assertContentEquals(fixtureBytes("kdbx4-aes-argon2d"), backups.kept.single())
    }

    // spec: edit.delete-for-good/AC1
    @Test
    fun anEntryIsDeletedForGoodOnlyFromTheBinAndOnlyAsLoaded() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val key = key()
        val start = unlock(store, key)
        val saver = VaultSaver(store, backups)
        val entry = start.entries.first()

        assertEquals(SaveFailure.WrongPlace, failure(saver.save(VaultEdit.DeleteForGood(entry.id, entry.lastModified), key, now)))
        val recycled = saved(saver.save(VaultEdit.MoveToRecycleBin(entry.id, entry.lastModified), key, now)).recycleBin.single { it.id == entry.id }
        assertEquals(SaveFailure.ChangedElsewhere, failure(saver.save(VaultEdit.DeleteForGood(entry.id, Instant.EPOCH), key, now)))
        val gone = saved(saver.save(VaultEdit.DeleteForGood(entry.id, recycled.lastModified), key, now))
        assertTrue(gone.entries.none { it.id == entry.id } && gone.recycleBin.none { it.id == entry.id })
        assertEquals(SaveFailure.EntryNotFound, failure(saver.save(VaultEdit.DeleteForGood(entry.id, recycled.lastModified), key, now)))

        val end = unlock(store)
        assertEquals(start.entries.filter { it.id != entry.id }, end.entries, "the other entries are as they were")
        assertEquals(1, backups.kept.size, "one a session: the file as it was before the first save")
    }

    /** Saves a new note titled [title] into [store] with [saver]. */
    private fun note(saver: VaultSaver, store: FakeStore, title: String, key: DatabaseKey = key()): SaveResult {
        val root = unlock(store, key).groups.single { it.isRoot }
        return saver.save(VaultEdit.Create(Entry.newId(), GroupDestination(root.id), EntryChanges(title = title)), key, now)
    }

    // spec: databases.backups/AC1
    @Test
    fun aSessionBacksUpTheFileOnceBeforeItsFirstSaveAndAgainAfterALock() {
        val original = fixtureBytes("kdbx4-aes-argon2d")
        val store = FakeStore(original)
        val saver = VaultSaver(store, backups)

        saved(note(saver, store, "one"))
        val first = store.content!!
        saved(note(saver, store, "two"))
        saved(note(saver, store, "three"))
        assertEquals(1, backups.kept.size, "the file the session wrote is not backed up")
        assertContentEquals(original, backups.kept.single(), "the file as it was before the session's changes")

        saver.forget()
        val wrote = store.content!!
        saved(note(saver, store, "four"))
        assertEquals(2, backups.kept.size, "the next session backs up its first save's file")
        assertContentEquals(wrote, backups.kept.last())
        assertTrue(!first.contentEquals(wrote))
    }

    @Test
    fun aFileChangedElsewhereBetweenTwoSavesIsBackedUpBeforeItIsReplaced() {
        val key = key()
        val elsewhere = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        saved(note(VaultSaver(elsewhere, FakeBackups()), elsewhere, "from the Mac", key))
        val store = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val saver = VaultSaver(store, backups)
        saved(note(saver, store, "first", key))

        store.change(elsewhere.content)
        saved(note(saver, store, "second", key))

        assertEquals(2, backups.kept.size)
        assertContentEquals(elsewhere.content, backups.kept.last(), "Syncthing's version, copied before it was replaced")
    }

    @Test
    fun aSaveReadingTheFileAgainAfterItChangedBacksUpTheNewFileNotTheOld() {
        val key = key()
        val elsewhere = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        saved(note(VaultSaver(elsewhere, FakeBackups()), elsewhere, "from the Mac", key))
        val store = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        // Changed while the first attempt's copy was written: that copy goes, the second attempt copies what came.
        backups.whileKeeping = { kept -> if (kept == 1) store.change(elsewhere.content) }

        saved(note(VaultSaver(store, backups), store, "mine", key))

        assertEquals(1, backups.kept.size)
        assertContentEquals(elsewhere.content, backups.kept.single())
    }

    @Test
    fun aSaveThatDidNotReplaceTheFileLeavesTheSessionsOwnFileAsItWas() {
        val original = fixtureBytes("kdbx4-aes-argon2d")
        val store = FakeStore(original)
        val saver = VaultSaver(store, backups)

        // The first save fails at the replace: its copy goes, and the file is still not the session's.
        store.failNextReplace = true
        failure(note(saver, store, "lost"))
        assertTrue(backups.kept.isEmpty())
        saved(note(saver, store, "one"))
        assertContentEquals(original, backups.kept.single())

        // A later one fails: the file is still the one the session wrote, so the next save copies nothing.
        store.failNextReplace = true
        failure(note(saver, store, "lost again"))
        saved(note(saver, store, "two"))
        assertEquals(1, backups.kept.size)
    }

    @Test
    fun aSaveTheLockOvertookLeavesNothingForTheNextSession() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val saver = VaultSaver(store, backups)
        // The lock comes while the save runs; the save still replaces the file.
        backups.whileKeeping = { kept -> if (kept == 1) saver.forget() }
        saved(note(saver, store, "one"))
        val wrote = store.content!!

        saved(note(saver, store, "two"))

        assertEquals(2, backups.kept.size, "the next session's first save copies the file")
        assertContentEquals(wrote, backups.kept.last())
    }

    // spec: databases.backups/AC1
    @Test
    fun aFileWhoseBytesABackupHasAlreadyOrWithBackupsOffIsNotCopied() {
        val original = fixtureBytes("kdbx4-aes-argon2d")
        val store = FakeStore(original)
        saved(note(VaultSaver(store, backups), store, "one"))
        assertEquals(1, backups.kept.size)
        // Back to the bytes a backup has (a restore's undo, another device going back): not copied again.
        store.change(original)
        saved(note(VaultSaver(store, backups), store, "two"))
        assertEquals(1, backups.kept.size)

        val off = FakeBackups().apply { isOn = false }
        val other = FakeStore(original)
        saved(note(VaultSaver(other, off), other, "off"))
        assertTrue(off.kept.isEmpty(), "off: no backup before a save")
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

    // spec: app.codes-view/AC5
    @Test
    fun aRemovedCodeIsPutBackWithItsFieldsAsTheyWere() {
        val key = key()
        val id = unlock(FakeStore(fixtureBytes("kdbx4-aes-argon2d")), key).entries.first().id
        val hex = "12345678901234567890".encodeToByteArray().joinToString("") { "%02x".format(it) }
        val store = FakeStore(
            CodeFiles.withCodeFields(
                fixtureBytes("kdbx4-aes-argon2d"), key, id,
                listOf("TimeOtp-Secret-Hex" to hex, "TimeOtp-Period" to "60", "TimeOtp-Algorithm" to "HMAC-SHA-1"),
            ),
        )
        val saver = VaultSaver(store, backups)
        val before = unlock(store, key).entries.single { it.id == id }
        val fields = CodeFiles.codeFields(store.content!!, key, id)
        assertEquals(listOf("TimeOtp-Secret-Hex", "TimeOtp-Period", "TimeOtp-Algorithm"), before.codeFields.map { it.name }, "the entry keeps its code's fields as read")

        val removed = saved(saver.save(VaultEdit.Update(id, before.lastModified, EntryChanges(totp = TotpChange.Remove)), key, now)).entries.single { it.id == id }
        assertNull(removed.totp)
        assertEquals(emptyList(), removed.codeFields)
        assertEquals(emptyList(), CodeFiles.codeFields(store.content!!, key, id))

        // Not written again in the database's format: the same fields, the same encoding, no Length added.
        val back = saved(saver.save(VaultEdit.Update(id, removed.lastModified, EntryChanges(totp = TotpChange.Fields(before.codeFields))), key, now))
            .entries.single { it.id == id }
        assertEquals(fields, CodeFiles.codeFields(store.content!!, key, id))
        assertEquals(before.totp, back.totp)
        assertEquals(before.codeFields, back.codeFields)
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
    fun aFileASyncReplacedWhileTheStoreWasReplacingItIsReadAgainAndTheEditAppliedOnTopOfIt() {
        val key = key()
        val elsewhere = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val other = unlock(elsewhere, key).titled("Second renamed")
        saved(VaultSaver(elsewhere, FakeBackups()).save(VaultEdit.Update(other.id, other.lastModified, EntryChanges(notes = "from the Mac")), key, now))
        val fake = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val mine = unlock(fake, key).titled("Entry <one> ü")
        // The store found the file changed just before its rename (keething-spec databases.saf AC4, AC6): nothing replaced.
        var replaces = 0
        val store = object : DatabaseStore by fake {
            override fun replace(bytes: ByteArray, expected: FileVersion?) {
                if (++replaces == 1) {
                    fake.change(elsewhere.content)
                    throw DatabaseChangedException("a sync replaced it")
                }
                fake.replace(bytes)
            }
        }

        val vault = saved(VaultSaver(store, backups).save(VaultEdit.Update(mine.id, mine.lastModified, EntryChanges(notes = "from the phone")), key, now))

        assertEquals("from the phone", vault.titled("Entry <one> ü").notes)
        assertEquals("from the Mac", vault.titled("Second renamed").notes, "the other change is kept")
        assertEquals(1, fake.replaced)
    }

    @Test
    fun aFileChangedWhileItWasBackedUpIsNotReplacedButReadAgainAndTheEditAppliedOnTopOfIt() {
        val key = key()
        val elsewhere = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val other = unlock(elsewhere, key).titled("Second renamed")
        saved(VaultSaver(elsewhere, FakeBackups()).save(VaultEdit.Update(other.id, other.lastModified, EntryChanges(notes = "from the Mac")), key, now))
        val store = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val mine = unlock(store, key).titled("Entry <one> ü")
        // After the saver's last look at the file, before the store replaces it (keething-spec databases.saf AC4).
        backups.whileKeeping = { kept -> if (kept == 1) store.change(elsewhere.content) }

        val vault = saved(VaultSaver(store, backups).save(VaultEdit.Update(mine.id, mine.lastModified, EntryChanges(notes = "from the phone")), key, now))

        assertEquals("from the phone", vault.titled("Entry <one> ü").notes)
        assertEquals("from the Mac", vault.titled("Second renamed").notes, "the other change is kept")
    }

    @Test
    fun aFileThatKeepsChangingIsGivenUpOn() {
        val key = key()
        val first = fixtureBytes("kdbx4-aes-aeskdf")
        val elsewhere = FakeStore(first)
        val (changed, entry) = unlock(elsewhere, key).entries
        saved(VaultSaver(elsewhere, FakeBackups()).save(VaultEdit.Update(changed.id, changed.lastModified, EntryChanges(notes = "v2")), key, now))
        val second = elsewhere.content!!
        val store = FakeStore(first)
        unlock(store, key)
        // Every check finds the other version; the entry saved is the same in both.
        store.beforeRead = { read -> store.change(if (read % 2 == 0) first else second, notify = false) }

        assertEquals(
            SaveFailure.FileKeepsChanging,
            failure(VaultSaver(store, backups).save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "x")), key, now)),
        )
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

            override fun <T> read(reading: (InputStream) -> T): DatabaseReading<T> = throw IOException("I/O error")
        }

        assertEquals(
            SaveFailure.WriteFailed("I/O error"),
            failure(VaultSaver(store, backups).save(VaultEdit.Update("any", null, EntryChanges(notes = "x")), key(), now)),
        )
    }

    // The groups (keething-spec groups.manage AC8, groups.entry-group AC2–AC7, groups.new-groups AC3–AC5; the Mac's
    // GroupChangeTests and GroupTests saver expectations)

    @Test
    fun aRenamedGroupIsSavedTheGroupsAndEntriesBelowItTakeTheNewPathAndNothingElseChanges() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val key = key()
        val saver = VaultSaver(store, backups)
        val start = unlock(store, key)
        val web = start.groups.single { it.name == "Web & Mail" }
        val top = start.groups.single { it.isRoot }
        val inner = NewGroup(Entry.newId(), "Inner")
        val other = NewGroup(Entry.newId(), "Other")
        saved(saver.save(VaultEdit.Create(Entry.newId(), GroupDestination(web.id, listOf(inner)), EntryChanges(title = "Deep")), key, now))
        val before = saved(saver.save(VaultEdit.Create(Entry.newId(), GroupDestination(top.id, listOf(other)), EntryChanges(title = "O")), key, now))

        val result = saver.save(VaultEdit.RenameGroup(web.id, "  Web  "), key, now)

        val after = saved(result)
        assertEquals(VaultGroup(web.id, "Web", listOf("Web")), after.groups.single { it.id == web.id })
        assertEquals(listOf("Web", "Inner"), after.groups.single { it.id == inner.id }.path)
        assertEquals(before.groups.size, after.groups.size)
        assertEquals(before.entries.size, after.entries.size)
        for (entry in before.entries) {
            val expected = if (entry.groupPath.firstOrNull() == "Web & Mail") entry.copy(groupPath = listOf("Web") + entry.groupPath.drop(1)) else entry
            assertEquals(expected, after.entries.single { it.id == entry.id }, "entries are not touched, only their path")
        }
        assertEquals(unlock(store), after, "the saved vault is the file read back")
        assertEquals(emptyList(), deletedBy(result))
        assertEquals("WEB", saved(saver.save(VaultEdit.RenameGroup(web.id, "WEB"), key, now)).groups.single { it.id == web.id }.name, "its own name in another case")

        val file = store.content
        assertEquals(SaveFailure.GroupNameTaken, failure(saver.save(VaultEdit.RenameGroup(other.id, "wéb"), key, now)), "case and accents aside")
        assertEquals(SaveFailure.InvalidName, failure(saver.save(VaultEdit.RenameGroup(other.id, "  "), key, now)))
        assertEquals(SaveFailure.GroupNotChangeable, failure(saver.save(VaultEdit.RenameGroup(top.id, "Top"), key, now)))
        assertEquals(SaveFailure.GroupNotFound, failure(saver.save(VaultEdit.RenameGroup("gone", "x"), key, now)))
        assertContentEquals(file, store.content, "a refusal writes nothing")
        val entry = unlock(store, key).entries.first()
        val binId = saved(saver.save(VaultEdit.MoveToRecycleBin(entry.id, entry.lastModified), key, now)).recycleBin.single { it.id == entry.id }.groupId
        assertEquals(SaveFailure.GroupNotChangeable, failure(saver.save(VaultEdit.RenameGroup(binId, "Bin"), key, now)), "the recycle bin")
    }

    @Test
    fun aNewGroupAMovedGroupAndAnEmptyGroupDeletedAreSavedAndChecked() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val key = key()
        val saver = VaultSaver(store, backups)
        val start = unlock(store, key)
        val web = start.groups.single { it.name == "Web & Mail" }
        val top = start.groups.single { it.isRoot }

        val archive = NewGroup(Entry.newId(), " Archive ")
        var vault = saved(saver.save(VaultEdit.CreateGroup(top.id, archive), key, now))
        assertTrue(VaultGroup(archive.id, "Archive", listOf("Archive")) in vault.groups)
        assertEquals(start.entries, vault.entries)
        assertEquals(SaveFailure.GroupNameTaken, failure(saver.save(VaultEdit.CreateGroup(top.id, NewGroup(Entry.newId(), "ARCHIVE")), key, now)))
        assertEquals(SaveFailure.InvalidName, failure(saver.save(VaultEdit.CreateGroup(top.id, NewGroup(Entry.newId(), " ")), key, now)))

        // Web & Mail, with a group and entries in it, into Archive.
        val inner = NewGroup(Entry.newId(), "Inner")
        val before = saved(saver.save(VaultEdit.Create(Entry.newId(), GroupDestination(web.id, listOf(inner)), EntryChanges(title = "Deep")), key, now))
        vault = saved(saver.save(VaultEdit.MoveGroup(web.id, archive.id), key, now))
        assertEquals(listOf("Archive", "Web & Mail"), vault.groups.single { it.id == web.id }.path)
        assertEquals(listOf("Archive", "Web & Mail", "Inner"), vault.groups.single { it.id == inner.id }.path)
        for (entry in before.entries) {
            val expected = if (entry.groupPath.firstOrNull() == "Web & Mail") entry.copy(groupPath = listOf("Archive") + entry.groupPath) else entry
            assertEquals(expected, vault.entries.single { it.id == entry.id })
        }

        assertEquals(SaveFailure.GroupNotChangeable, failure(saver.save(VaultEdit.MoveGroup(archive.id, inner.id), key, now)), "into a group inside it")
        assertEquals(SaveFailure.GroupNotChangeable, failure(saver.save(VaultEdit.MoveGroup(web.id, archive.id), key, now)), "where it is")
        assertEquals(SaveFailure.GroupNotChangeable, failure(saver.save(VaultEdit.MoveGroup(top.id, archive.id), key, now)), "the root group")
        val namesake = NewGroup(Entry.newId(), "web & mail")
        saved(saver.save(VaultEdit.CreateGroup(top.id, namesake), key, now))
        assertEquals(SaveFailure.GroupNameTaken, failure(saver.save(VaultEdit.MoveGroup(namesake.id, archive.id), key, now)))

        // Only an empty group is deleted this way.
        assertEquals(SaveFailure.GroupNotEmpty, failure(saver.save(VaultEdit.DeleteEmptyGroup(archive.id), key, now)))
        assertEquals(SaveFailure.GroupNotChangeable, failure(saver.save(VaultEdit.DeleteEmptyGroup(top.id), key, now)))
        val deleted = saver.save(VaultEdit.DeleteEmptyGroup(namesake.id), key, now)
        assertTrue(saved(deleted).groups.none { it.id == namesake.id })
        assertEquals(listOf(VaultGroup(namesake.id, "web & mail", listOf("web & mail"))), deletedBy(deleted))
        assertEquals(namesake.id, deletedObjects(store).last(), "for good, with a DeletedObject")

        // Into the bin no group is moved.
        val entry = unlock(store, key).entries.first()
        val binId = saved(saver.save(VaultEdit.MoveToRecycleBin(entry.id, entry.lastModified), key, now)).recycleBin.single { it.id == entry.id }.groupId
        assertEquals(SaveFailure.GroupNotChangeable, failure(saver.save(VaultEdit.MoveGroup(archive.id, binId), key, now)))
    }

    @Test
    fun aNewEntryInNewGroupsAndAnEntryMovedWithAnEditAndAlone() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val key = key()
        val saver = VaultSaver(store, backups)
        val start = unlock(store, key)
        val web = start.groups.single { it.name == "Web & Mail" }

        val id = Entry.newId()
        val servers = NewGroup(Entry.newId(), "Servers")
        val prod = NewGroup(Entry.newId(), "Prod")
        var vault = saved(saver.save(VaultEdit.Create(id, GroupDestination(web.id, listOf(servers, prod)), EntryChanges(title = "Box")), key, now))
        var created = vault.entries.single { it.id == id }
        assertEquals(prod.id, created.groupId)
        assertEquals(listOf("Web & Mail", "Servers", "Prod"), created.groupPath)
        assertEquals(start.groups.size + 2, vault.groups.size)

        // Moved and edited at once: one history snapshot for the edit, none for the move; Prod is left empty.
        val both = saver.save(VaultEdit.Update(id, created.lastModified, EntryChanges(username = "root", group = GroupDestination(servers.id))), key, now)
        vault = saved(both)
        created = vault.entries.single { it.id == id }
        assertEquals(servers.id, created.groupId)
        assertEquals("root", created.username)
        assertEquals(1, historySize(store, id))
        assertEquals(listOf(VaultGroup(prod.id, "Prod", listOf("Web & Mail", "Servers", "Prod"))), deletedBy(both))

        // Only moved, into a new group at the top level: no snapshot; Servers is left empty.
        val root = vault.groups.single { it.isRoot }
        val archive = NewGroup(Entry.newId(), "Archive")
        val alone = saver.save(VaultEdit.Update(id, created.lastModified, EntryChanges(group = GroupDestination(root.id, listOf(archive)))), key, now)
        vault = saved(alone)
        created = vault.entries.single { it.id == id }
        assertEquals(listOf("Archive"), created.groupPath)
        assertEquals(1, historySize(store, id))
        assertEquals(listOf(servers.id), deletedBy(alone).map { it.id })
        assertEquals(start.entries, vault.entries.filter { it.id != id }, "the other entries as they were")
        assertEquals(start.groups.size + 1, vault.groups.size)

        assertEquals(SaveFailure.GroupNotFound, failure(saver.save(VaultEdit.Update(id, created.lastModified, EntryChanges(group = GroupDestination("gone"))), key, now)))
    }

    @Test
    fun aMoveThatLeavesItsGroupEmptyDeletesItAndEachParentItEmptiesAndTheResultNamesThem() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val key = key()
        val saver = VaultSaver(store, backups)
        val start = unlock(store, key)
        val web = start.groups.single { it.name == "Web & Mail" }
        val top = start.groups.single { it.isRoot }
        // Old › Deep holds A alone; Keep holds B and the group Kept, which holds C.
        val old = NewGroup(Entry.newId(), "Old")
        val deep = NewGroup(Entry.newId(), "Deep")
        val keep = NewGroup(Entry.newId(), "Keep")
        val kept = NewGroup(Entry.newId(), "Kept")
        val (a, b, c) = List(3) { Entry.newId() }
        saved(saver.save(VaultEdit.Create(a, GroupDestination(top.id, listOf(old, deep)), EntryChanges(title = "A")), key, now))
        saved(saver.save(VaultEdit.Create(b, GroupDestination(top.id, listOf(keep)), EntryChanges(title = "B")), key, now))
        saved(saver.save(VaultEdit.Create(c, GroupDestination(keep.id, listOf(kept)), EntryChanges(title = "C")), key, now))
        fun move(id: String, destination: GroupDestination): SaveResult {
            val entry = unlock(store, key).entries.single { it.id == id }
            return saver.save(VaultEdit.Update(id, entry.lastModified, EntryChanges(group = destination)), key, now)
        }

        val before = unlock(store, key)
        val moved = move(a, GroupDestination(web.id))
        var after = saved(moved)
        assertEquals(
            listOf(VaultGroup(deep.id, "Deep", listOf("Old", "Deep")), VaultGroup(old.id, "Old", listOf("Old"))), deletedBy(moved),
            "innermost first, as they were",
        )
        assertTrue(after.groups.none { it.id == old.id || it.id == deep.id })
        assertEquals(before.groups.size - 2, after.groups.size)
        assertEquals(listOf("Web & Mail"), after.entries.single { it.id == a }.groupPath)
        assertEquals(listOf(deep.id, old.id), deletedObjects(store).takeLast(2), "for good, the innermost first")

        // Kept goes; Keep still holds B.
        assertEquals(listOf(kept.id), deletedBy(move(c, GroupDestination(top.id))).map { it.id })
        after = unlock(store, key)
        assertTrue(after.groups.none { it.id == kept.id })
        assertTrue(after.groups.any { it.id == keep.id })

        // B into a new group inside Keep: Keep is not left empty. Then back up into Keep: the new group goes, not Keep.
        val sub = NewGroup(Entry.newId(), "Sub")
        assertEquals(emptyList(), deletedBy(move(b, GroupDestination(keep.id, listOf(sub)))))
        assertTrue(unlock(store, key).groups.any { it.id == keep.id })
        assertEquals(listOf(sub.id), deletedBy(move(b, GroupDestination(keep.id))).map { it.id })
        after = unlock(store, key)
        assertEquals(listOf("Keep"), after.entries.single { it.id == b }.groupPath)

        // An edit without a move deletes no group.
        val records = deletedObjects(store).size
        val entry = after.entries.single { it.id == b }
        val edited = saver.save(VaultEdit.Update(b, entry.lastModified, EntryChanges(title = "B2")), key, now)
        assertEquals(emptyList(), deletedBy(edited))
        assertEquals(after.groups, saved(edited).groups)
        assertEquals(records, deletedObjects(store).size)
    }

    @Test
    fun aGroupChangeRefusedBecauseTheFileChangedMeanwhileSaysWhyAndWritesNothing() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val key = key()
        val saver = VaultSaver(store, backups)
        val top = unlock(store, key).groups.single { it.isRoot }
        val spare = NewGroup(Entry.newId(), "Spare")
        val gone = NewGroup(Entry.newId(), "Gone")
        saved(saver.save(VaultEdit.CreateGroup(top.id, spare), key, now))
        saved(saver.save(VaultEdit.CreateGroup(top.id, gone), key, now))
        // What the phone shows from here on; meanwhile another app makes "Mail" at the top level, puts an entry into Spare
        // and deletes Gone.
        val shown = unlock(store, key)
        val elsewhere = VaultSaver(store, FakeBackups())
        saved(elsewhere.save(VaultEdit.CreateGroup(top.id, NewGroup(Entry.newId(), "Mail")), key, now))
        saved(elsewhere.save(VaultEdit.Create(Entry.newId(), GroupDestination(spare.id), EntryChanges(title = "Filed")), key, now))
        saved(elsewhere.save(VaultEdit.DeleteEmptyGroup(gone.id), key, now))
        assertTrue(shown.groups.none { it.name == "Mail" })
        val file = store.content
        val replaced = store.replaced

        assertEquals(SaveFailure.GroupNameTaken, failure(saver.save(VaultEdit.CreateGroup(top.id, NewGroup(Entry.newId(), "mail")), key, now)))
        assertEquals(SaveFailure.GroupNameTaken, failure(saver.save(VaultEdit.RenameGroup(spare.id, "Mail"), key, now)))
        assertEquals(SaveFailure.GroupNotEmpty, failure(saver.save(VaultEdit.DeleteEmptyGroup(spare.id), key, now)))
        assertEquals(SaveFailure.GroupNotFound, failure(saver.save(VaultEdit.RenameGroup(gone.id, "Back"), key, now)))
        assertEquals(SaveFailure.GroupNotFound, failure(saver.save(VaultEdit.MoveGroup(spare.id, gone.id), key, now)))
        assertEquals(SaveFailure.GroupNotFound, failure(saver.save(VaultEdit.CreateGroup(gone.id, NewGroup(Entry.newId(), "In")), key, now)))
        assertContentEquals(file, store.content)
        assertEquals(replaced, store.replaced)
    }

    @Test
    fun aMovedEntrysNewLevelAnotherAppMadeMeanwhileIsThatAppsGroupAndOnlyTheRestIsMade() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val key = key()
        val start = unlock(store, key)
        val web = start.groups.single { it.name == "Web & Mail" }
        val entry = start.entries.first()
        // Meanwhile another app makes Web & Mail › Servers.
        val theirs = NewGroup(Entry.newId(), "Servers")
        saved(VaultSaver(store, FakeBackups()).save(VaultEdit.CreateGroup(web.id, theirs), key, now))
        val servers = NewGroup(Entry.newId(), "servers")
        val prod = NewGroup(Entry.newId(), "Prod")

        val result = VaultSaver(store, backups).save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(group = GroupDestination(web.id, listOf(servers, prod)))), key, now)

        val vault = saved(result)
        val moved = vault.entries.single { it.id == entry.id }
        assertEquals(prod.id, moved.groupId)
        assertEquals(listOf("Web & Mail", "Servers", "Prod"), moved.groupPath)
        assertEquals(listOf(theirs.id), vault.groups.filter { it.path == listOf("Web & Mail", "Servers") }.map { it.id }, "theirs, not made twice")
        assertEquals(emptyList(), deletedBy(result), "Web & Mail still holds the other entry")
    }

    // spec: databases.rename-recolour/AC1
    @Test
    fun aNameAndColourLandInTheFileBackedUpWithEverythingElseAsItWas() {
        val original = fixtureBytes("kdbx4-aes-argon2d")
        val store = FakeStore(original)
        val key = key()
        val before = unlock(store, key)

        val vault = saved(VaultSaver(store, backups).save(VaultEdit.NameAndColor(name = " Family ", color = "#2E9E4F"), key, now))

        assertEquals("Family", vault.name)
        assertEquals("#2E9E4F", vault.color)
        assertEquals(before.copy(name = "Family", color = "#2E9E4F"), vault, "nothing else changed")
        assertEquals(unlock(store), vault, "the saved vault is the file read back")
        assertContentEquals(original, backups.kept.single())
    }

    // spec: databases.rename-recolour/AC4
    @Test
    fun aNameAndColourGoOntoTheFileAsItIsKeepingWhatChangedElsewhere() {
        val key = key()
        val elsewhere = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val other = unlock(elsewhere, key).titled("Entry <one> ü")
        saved(VaultSaver(elsewhere, FakeBackups()).save(VaultEdit.Update(other.id, other.lastModified, EntryChanges(notes = "from the Mac")), key, now))
        saved(VaultSaver(elsewhere, FakeBackups()).save(VaultEdit.NameAndColor(name = "Theirs"), key, now))
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        unlock(store, key)
        store.change(elsewhere.content)

        val vault = saved(VaultSaver(store, backups).save(VaultEdit.NameAndColor(color = "#D93A3A"), key, now))

        assertEquals("from the Mac", vault.titled("Entry <one> ü").notes, "the entry changed elsewhere is kept")
        assertEquals("Theirs", vault.name, "the name changed elsewhere is kept: only the colour was asked")
        assertEquals("#D93A3A", vault.color)
    }
}
