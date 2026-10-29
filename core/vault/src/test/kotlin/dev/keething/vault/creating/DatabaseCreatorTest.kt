package dev.keething.vault.creating

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.storage.KeyFileRead
import dev.keething.domain.storage.KeyFileSource
import dev.keething.domain.storage.NewFile
import dev.keething.domain.storage.NewFilePlace
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.KeyFile
import dev.keething.kdbx.reader.KdbxFormat
import dev.keething.kdbx.reader.KdbxFormat.Cipher
import dev.keething.kdbx.reader.KdbxFormat.Kdf
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.saving.SaveResult
import dev.keething.vault.saving.VaultSaver
import dev.keething.vault.session.FakeStore
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Making a new database and a new key file (keething-spec kdbx.new-file AC7–AC9, kdbx.new-key-file AC3, AC4; the
 * Mac's `NewDatabaseTests`, "The creator"). The file system is a fake: how the phone puts a file in place without
 * replacing one is the app's ([NewFile]).
 */
class DatabaseCreatorTest {
    private val now = Instant.ofEpochSecond(1_793_000_000)
    private val creator = DatabaseCreator(now = { now })

    /** A place in memory; [appears] puts a file there just before [create], as a sync would. */
    private class FakeNewFile(
        override val fileName: String = "Work.kdbx",
        var existing: ByteArray? = null,
        var folder: Boolean = true,
    ) : NewFile {
        var appears: ByteArray? = null
        var failWith: IOException? = null
        var created: ByteArray? = null
        var readOnly: Boolean? = null
        var creates = 0
        var removed = 0

        override fun check(): NewFilePlace = when {
            !folder -> NewFilePlace.NO_FOLDER
            existing != null -> NewFilePlace.TAKEN
            else -> NewFilePlace.FREE
        }

        override fun create(bytes: ByteArray, readOnly: Boolean) {
            creates++
            appears?.let { existing = it }
            failWith?.let { throw it }
            if (!folder) throw NoSuchFileException(fileName)
            if (existing != null) throw FileAlreadyExistsException(fileName)
            existing = bytes.copyOf()
            created = bytes.copyOf()
            this.readOnly = readOnly
        }

        override fun removeCreated() {
            removed++
            if (created != null) existing = null
            created = null
        }
    }

    private class FakeKeyFile(private val bytes: () -> ByteArray?) : KeyFileSource {
        override val fileName: String = "work.keyx"
        override fun read(): KeyFileRead = bytes()?.let { KeyFileRead.Found(it.copyOf()) } ?: KeyFileRead.Missing
    }

    private fun pw() = DatabaseKey.of(Secret.of("pw"))

    private fun create(target: NewFile, name: String? = "Work", cipher: Cipher = Cipher.AES256, kdf: Kdf = CHEAP_ARGON2D, key: DatabaseKey = pw()) =
        creator.create(target, name, key, cipher, kdf)

    @Test
    fun theFileIsMadeOpensWithItsNameAndNothingInItInTheFormatAskedFor() {
        for ((cipher, kdf) in listOf(Cipher.AES256 to CHEAP_ARGON2D, Cipher.CHACHA20 to CHEAP_ARGON2ID, Cipher.AES256 to CHEAP_AES)) {
            val target = FakeNewFile()

            create(target, cipher = cipher, kdf = kdf)

            val file = target.existing!!
            assertEquals(KdbxFormat("4.0", cipher, kdf, compressed = true), KdbxReader.format(file))
            val opened = KdbxReader.open(file, pw())
            assertEquals("Work", opened.document.meta.databaseName)
            assertEquals(listOf("Work"), opened.document.groups.map { it.name })
            assertTrue(opened.document.entries.isEmpty())
            assertEquals(false, target.readOnly, "a database stays writable")
        }
    }

    @Test
    fun theKeyComesBackDerivedForTheFirstUnlock() {
        val key = pw()
        val target = FakeNewFile()

        create(target, key = key)

        assertNull(KdbxReader.open(target.existing!!, key).keyDerivation, "nothing derived again")
    }

    @Test
    fun aDatabaseIsNeverReplacedAFileAlreadyThereStaysAsItWas() {
        val target = FakeNewFile("Personal.kdbx", existing = "mine".encodeToByteArray())

        val failure = assertFailsWith<CreateFailure.Exists> { create(target, name = "Personal") }

        assertEquals("Personal.kdbx", failure.name)
        assertEquals("mine", target.existing!!.decodeToString())
        assertEquals(0, target.creates, "said before anything is written")
    }

    @Test
    fun aFileThatAppearsWhileTheNewOneIsMadeIsNotReplacedEither() {
        val target = FakeNewFile("Race.kdbx").apply { appears = "synced in meanwhile".encodeToByteArray() }

        assertEquals("Race.kdbx", assertFailsWith<CreateFailure.Exists> { create(target, name = null) }.name)

        assertEquals("synced in meanwhile", target.existing!!.decodeToString())
    }

    @Test
    fun aFolderThatIsNotThereIsSaid() {
        assertFailsWith<CreateFailure.FolderNotFound> { create(FakeNewFile(folder = false)) }
        val gone = FakeNewFile().apply { failWith = NoSuchFileException("Work.kdbx") }
        assertFailsWith<CreateFailure.FolderNotFound> { create(gone) }
    }

    @Test
    fun aWriteThatFailsIsSaid() {
        val target = FakeNewFile().apply { failWith = IOException("No space left on device") }

        val failure = assertFailsWith<CreateFailure.WriteFailed> { create(target) }

        assertEquals("No space left on device", failure.why)
    }

    @Test
    fun twofishAndImpossibleArgon2ParametersAreSaidAsSuchNothingWritten() {
        val target = FakeNewFile()
        assertEquals("Twofish", assertFailsWith<CreateFailure.Unsupported> { create(target, cipher = Cipher.TWOFISH) }.what)
        assertFailsWith<CreateFailure.Unsupported> {
            create(target, kdf = Kdf.Argon2(Kdf.Argon2.Variant.D, 1, 4L shl 30, 1))
        }
        assertEquals(0, target.creates)
    }

    @Test
    fun aNameWithALineBreakIsWrittenWithoutItAndTheCheckAgrees() {
        val target = FakeNewFile()
        create(target, name = "Wo\nrk", kdf = CHEAP_AES)
        assertEquals("Work", KdbxReader.open(target.existing!!, pw()).document.meta.databaseName)
    }

    @Test
    fun withoutANameTheRootGroupTakesTheFileName() {
        val target = FakeNewFile("Family Shared.kdbx")
        create(target, name = "  ", kdf = CHEAP_AES)
        val opened = KdbxReader.open(target.existing!!, pw())
        assertEquals("", opened.document.meta.databaseName)
        assertEquals(listOf("Family Shared"), opened.document.groups.map { it.name })
    }

    @Test
    fun aFirstEntrySavedIntoTheNewFileTheBinMadeByTheFirstDelete() {
        val target = FakeNewFile("Fresh.kdbx")
        val key = pw()
        create(target, name = "Fresh", key = key)
        val store = FakeStore(target.existing)
        val opened = assertIs<OpenResult.Opened>(VaultOpener().open(store, key)).opened.vault
        val root = opened.groups.single()
        assertTrue(opened.entries.isEmpty())
        val saver = VaultSaver(store, DatabaseBackups { _, _, _ -> })
        val id = Entry.newId()

        val created = assertIs<SaveResult.Saved>(
            saver.save(VaultEdit.Create(id, root.id, EntryChanges(title = "GitHub", username = "me", password = Secret.of("s3cret"))), key, now),
        ).opened.vault
        val entry = created.entries.single()
        assertEquals("GitHub", entry.title)
        assertEquals(Secret.of("s3cret"), entry.password)
        assertTrue(created.recycleBin.isEmpty())

        val recycled = assertIs<SaveResult.Saved>(saver.save(VaultEdit.MoveToRecycleBin(id, entry.lastModified), key, now)).opened.vault
        assertTrue(recycled.entries.isEmpty())
        assertEquals(listOf("GitHub"), recycled.recycleBin.map { it.title })
    }

    // A new key file

    @Test
    fun aNewKeyFileIsWrittenReadOnlyReadBackAndItsKeyReturned() {
        val target = FakeNewFile("work.keyx")

        val key = creator.createKeyFile(target, FakeKeyFile { target.existing })

        assertEquals(true, target.readOnly)
        assertContentEquals(KeyFile.contents(key), target.existing)
        assertTrue(KeyFile.key(target.existing!!).hasSameBytes(key))
    }

    @Test
    fun aNewKeyFileIsNeverWrittenOverAFileAlreadyThere() {
        val target = FakeNewFile("work.keyx", existing = "the first".encodeToByteArray())

        assertEquals("work.keyx", assertFailsWith<CreateFailure.Exists> { creator.createKeyFile(target, FakeKeyFile { target.existing }) }.name)

        assertEquals("the first", target.existing!!.decodeToString())
        val appearing = FakeNewFile("work.keyx").apply { appears = "synced".encodeToByteArray() }
        assertFailsWith<CreateFailure.Exists> { creator.createKeyFile(appearing, FakeKeyFile { appearing.existing }) }
        assertEquals("synced", appearing.existing!!.decodeToString())
        assertEquals(0, appearing.removed, "nothing of another's removed")
        assertFailsWith<CreateFailure.FolderNotFound> { creator.createKeyFile(FakeNewFile(folder = false), FakeKeyFile { null }) }
    }

    @Test
    fun aKeyFileThatDoesNotReadBackAsWrittenIsRemovedAndSaid() {
        for (reread in listOf<(FakeNewFile) -> ByteArray?>({ null }, { "something else".encodeToByteArray() })) {
            val target = FakeNewFile("work.keyx")

            assertFailsWith<CreateFailure.WriteFailed> { creator.createKeyFile(target, FakeKeyFile { reread(target) }) }

            assertEquals(1, target.removed)
            assertNull(target.existing)
        }
    }

    @Test
    fun aKeyFileWhoseReadBackFailsInAnyWayIsRemovedAndTheBytesReadAreWiped() {
        val target = FakeNewFile("work.keyx")
        val throwing = object : KeyFileSource {
            override val fileName = "work.keyx"
            override fun read(): KeyFileRead = throw SecurityException("access revoked")
        }
        assertFailsWith<CreateFailure.WriteFailed> { creator.createKeyFile(target, throwing) }
        assertNull(target.existing)

        val handedOut = mutableListOf<ByteArray>()
        val notAKeyFile = FakeNewFile("work.keyx")
        val broken = object : KeyFileSource {
            override val fileName = "work.keyx"
            override fun read(): KeyFileRead =
                KeyFileRead.Found(KeyFile.contents(KeyFile.newKey()).decodeToString().replace("Hash=\"", "Hash=\"0").encodeToByteArray().also { handedOut += it })
        }
        assertFailsWith<CreateFailure.WriteFailed> { creator.createKeyFile(notAKeyFile, broken) }
        assertNull(notAKeyFile.existing)
        assertTrue(handedOut.single().all { it == 0.toByte() }, "the bytes read are wiped also when they are not a key file")
    }

    @Test
    fun aDatabaseWithTheNewKeyFileOpensWithItAloneOrWithItsPassword() {
        val keyTarget = FakeNewFile("work.keyx")
        val keyFile = creator.createKeyFile(keyTarget, FakeKeyFile { keyTarget.existing })
        val both = FakeNewFile("Both.kdbx")
        val only = FakeNewFile("Only.kdbx")

        create(both, key = DatabaseKey.of(Secret.of("pw"), keyFile))
        create(only, kdf = CHEAP_AES, key = DatabaseKey.of(null, keyFile))

        val reread = KeyFile.key(keyTarget.existing!!)
        KdbxReader.open(both.existing!!, DatabaseKey.of(Secret.of("pw"), reread))
        KdbxReader.open(only.existing!!, DatabaseKey.of(null, reread))
    }

    private companion object {
        val CHEAP_ARGON2D = Kdf.Argon2(Kdf.Argon2.Variant.D, 2, 1L shl 20, 2)
        val CHEAP_ARGON2ID = Kdf.Argon2(Kdf.Argon2.Variant.ID, 2, 1L shl 20, 2)
        val CHEAP_AES = Kdf.AesKdf(1000)
    }
}
