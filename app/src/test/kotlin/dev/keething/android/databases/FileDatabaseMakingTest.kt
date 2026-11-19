package dev.keething.android.databases

import dev.keething.domain.encryption.DatabaseEncryption
import dev.keething.domain.encryption.DatabaseEncryption.Cipher
import dev.keething.domain.encryption.DatabaseEncryption.KeyDerivation
import dev.keething.domain.encryption.MeasuredEncryption
import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.FakeGrantedFolder
import dev.keething.domain.storage.GrantedFile
import dev.keething.domain.storage.GrantedFolder
import dev.keething.domain.storage.StoragePlace
import dev.keething.vault.creating.CreateFailure
import dev.keething.vault.creating.CreationNote
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.Databases
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.storage.KeyFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * New database's files through a granted folder (keething-spec databases.saf AC11; plan AD7): never made over a file,
 * noted while Create runs, only its own removed; each named by its path on the phone, as the form names it.
 */
class FileDatabaseMakingTest {

    // spec: databases.saf/AC10
    @Test
    fun newDatabaseReachesOnlyTheListsFoldersWhoseGrantIsHeld() {
        val sync = StoragePlace("primary", "Sync")
        val docs = StoragePlace("primary", "Documents")
        val databases = Databases(
            listOf(
                DatabaseRecord(DatabaseId("a"), FileLocation.InFolder("grant-sync", sync, "Personal.kdbx")),
                DatabaseRecord(DatabaseId("b"), FileLocation.InFolder("grant-docs", docs, "Work.kdbx")),
            ),
            currentId = null,
        )

        assertEquals(listOf(GrantedPlace("grant-docs", docs)), databases.grantedFolders(held = setOf("grant-docs")), "Sync renamed away and back")
        assertEquals(2, databases.grantedFolders().size, "none known: every one")
    }
    private val sync = FakeGrantedFolder("grant-sync")
    private val documents = FakeGrantedFolder("grant-documents")
    private val notes: File = Files.createTempDirectory("file-database-making-test").toFile()
    private val noteFile = File(notes, "creating.note")
    private val folders: (String) -> GrantedFolder? = { grant -> listOf(sync, documents).firstOrNull { it.id == grant } }
    private val stick = object : GrantedFile {
        override val id: String = "grant-stick"

        override fun read(): ByteArray = "any file is a key file".encodeToByteArray()
    }
    private val places = NewDatabasePlaces(ROOT, listOf(GrantedPlace(sync.id, StoragePlace(StoragePlace.PRIMARY, "Sync"))))
    private val making = FileDatabaseMaking(places, folders, KeyFiles(folders) { if (it == stick.id) stick else null }, CreationNote(noteFile, folders))
    private val cheap = MeasuredEncryption(DatabaseEncryption(Cipher.AES256, KeyDerivation.ARGON2D, 1, 1, 0.1), units = 2, seconds = 0.01)

    @AfterTest
    fun deleteFolder() {
        notes.deleteRecursively()
    }

    @Test
    fun aKeyFileAndADatabaseAreMadeInTheGrantedFolderNotedWhileCreatingRunsAndTheNoteGoesAtTheEnd() {
        sync.makeFolder("Keys")
        making.begin("Family")
        val fileKey = making.createKeyFile("$ROOT/Sync/Keys/Family.keyx")
        making.create("$ROOT/Sync/Family.kdbx", "Family", Secret.of("pw"), fileKey, cheap).wipe()

        assertEquals(listOf("Family.kdbx"), sync.names())
        assertEquals(listOf("Family.keyx"), sync.names("Keys"))
        val noted = noteFile.readText()
        assertTrue(noted.contains("made-in grant-sync\tprimary\tSync\tFamily.kdbx") && noted.contains("Keys/Family.keyx"), "both noted by grant while it runs")
        making.end(made = true)
        assertFalse(noteFile.exists())
        assertNull(CreationNote(noteFile, folders).leftovers(), "nothing to clean up at the next start")
    }

    @Test
    fun aKeyFileMadeForADatabaseThatWasNotMadeIsRemovedAndOnlyThatOne() {
        making.begin("Family")
        making.createKeyFile("$ROOT/Sync/Family.keyx").wipe()
        sync.put("Family.kdbx", "a sync put this here".encodeToByteArray())
        assertFailsWith<CreateFailure.Exists> { making.create("$ROOT/Sync/Family.kdbx", "Family", Secret.of("pw"), null, cheap) }
        making.removeKeyFile("$ROOT/Sync/Family.keyx")
        assertEquals(listOf("Family.kdbx"), sync.names(), "the key file never used: it goes")
        assertEquals("a sync put this here", sync.bytesOf("Family.kdbx")?.decodeToString(), "never replaced")
        making.end(made = false)
        assertFalse(noteFile.exists(), "nothing of it left")
    }

    @Test
    fun aKeyFileAlreadyThereInAnyCaseIsNeverReplacedNorRemoved() {
        sync.put("family.KEYX", "someone's own".encodeToByteArray())
        making.begin("Family")
        assertFailsWith<CreateFailure.Exists> { making.createKeyFile("$ROOT/Sync/Family.keyx") }
        making.removeKeyFile("$ROOT/Sync/Family.keyx")
        assertEquals("someone's own", sync.bytesOf("family.KEYX")?.decodeToString(), "not this Create's: it stays")
        assertEquals(listOf("family.KEYX"), sync.names(), "nor a numbered one left beside it")
        making.end(made = false)
    }

    @Test
    fun aKeyFileTheUserHasIsOnlyReadThroughItsFolderOrItsOwnGrant() {
        sync.put("Keys/mine.keyx", "any file is a key file".encodeToByteArray())
        making.begin("Family")
        making.readKeyFile("$ROOT/Sync/Keys/mine.keyx").wipe()
        making.removeKeyFile("$ROOT/Sync/Keys/mine.keyx")
        assertEquals("any file is a key file", sync.bytesOf("Keys/mine.keyx")?.decodeToString())

        val alone = places.keyFileGranted(FileLocation.Granted(stick.id, StoragePlace("1234-5678", "keys/stick.keyx")))
        assertEquals("/storage/1234-5678/keys/stick.keyx", alone)
        making.readKeyFile(alone).wipe()

        assertFailsWith<CreateFailure.KeyFileUnusable> { making.readKeyFile("$ROOT/Sync/gone.keyx") }
        assertFailsWith<CreateFailure.KeyFileUnusable> { making.readKeyFile("$ROOT/Download/no grant.keyx") }
        making.end(made = false)
    }

    @Test
    fun nothingIsMadeWhereNoGrantReachesNorThroughAGrantGone() {
        making.begin("Family")
        assertFailsWith<CreateFailure.FolderNotFound> { making.create("$ROOT/Documents/Family.kdbx", "Family", Secret.of("pw"), null, cheap) }
        assertFailsWith<CreateFailure.FolderNotFound> { making.createKeyFile("$ROOT/Documents/Family.keyx") }

        val granted = places.folderGranted(GrantedPlace(documents.id, StoragePlace(StoragePlace.PRIMARY, "Documents")))
        assertEquals("$ROOT/Documents", granted)
        making.createKeyFile("$granted/Family.keyx").wipe()
        assertEquals(listOf("Family.keyx"), documents.names())
        assertEquals(listOf("grant-documents"), places.taken, "offered to the list once the form is done")
        making.end(made = true)
    }

    @Test
    fun aNewKeyFileThatCannotBeRemovedStaysNotedAndTheNextStartTakesIt() {
        sync.makeFolder("Keys")
        making.begin("Family")
        making.createKeyFile("$ROOT/Sync/Keys/Family.keyx").wipe()
        sync.beforeCall = { if (it.operation == FakeGrantedFolder.Operation.DELETE) throw IOException("the provider failed") }
        runCatching { making.removeKeyFile("$ROOT/Sync/Keys/Family.keyx") }
        making.end(made = false)
        assertTrue(sync.names("Keys").isNotEmpty() && noteFile.exists(), "not removed: still noted")

        sync.beforeCall = {}
        assertEquals(CreationNote.Leftover("Family", emptyList()), CreationNote(noteFile, folders).leftovers())
        assertEquals(emptyList(), sync.names("Keys"), "the next start removes it")
    }

    @Test
    fun theFormsFilesAreReadThroughTheGrantsOffTheMainThreadKeptUntilLookedAtAgainAndSyncthingsMarkerFoundInTheGrant() = runTest {
        sync.makeFolder(".stfolder")
        sync.makeFolder("KeePass/Keys")
        sync.put("KeePass/Personal.kdbx", byteArrayOf(1))
        places.folderGranted(GrantedPlace(documents.id, StoragePlace(StoragePlace.PRIMARY, "Documents")))
        val files = GrantedNewDatabaseFiles(places, folders, this, StandardTestDispatcher(testScheduler)) { it.grant == stick.id }
        var told = 0
        files.whenRead { told++ }

        // Asked while the form draws: nothing is read there; until it is, no file is there and a folder is.
        assertFalse(files.exists("$ROOT/Sync/KeePass/personal.KDBX"))
        assertTrue(files.isFolder("$ROOT/Sync/None"))
        assertEquals(emptyList(), sync.calls.filter { it.operation == FakeGrantedFolder.Operation.LIST })
        advanceUntilIdle()
        assertTrue(told > 0, "the form is told to ask again")

        fun everything() {
            files.exists("$ROOT/Sync/KeePass/personal.KDBX")
            files.exists("$ROOT/Sync/KeePass/Work.kdbx")
            files.isFolder("$ROOT/Sync")
            files.isFolder("$ROOT/Sync/KeePass/Keys")
            files.isFolder("$ROOT/Sync/None")
            files.syncs("$ROOT/Sync/KeePass/Keys")
            files.syncs("$ROOT/Documents")
        }
        everything()
        advanceUntilIdle()
        everything()
        advanceUntilIdle()

        assertTrue(files.isFolder("$ROOT/Sync") && files.isFolder("$ROOT/Sync/KeePass/Keys") && !files.isFolder("$ROOT/Sync/None"))
        assertTrue(files.exists("$ROOT/Sync/KeePass/personal.KDBX"), "case aside, as the storage takes names")
        assertFalse(files.exists("$ROOT/Sync/KeePass/Work.kdbx"))
        assertFalse(files.exists("$ROOT/Download/Personal.kdbx"), "no grant reaches it")
        assertTrue(files.syncs("$ROOT/Sync") && files.syncs("$ROOT/Sync/KeePass/Keys"), "the marker in the grant, above it")
        assertFalse(files.syncs("$ROOT/Documents"))

        sync.put("KeePass/Work.kdbx", byteArrayOf(1))
        assertFalse(files.exists("$ROOT/Sync/KeePass/Work.kdbx"), "as read, until looked at again")
        files.lookAgain()
        files.exists("$ROOT/Sync/KeePass/Work.kdbx")
        advanceUntilIdle()
        assertTrue(files.exists("$ROOT/Sync/KeePass/Work.kdbx"))

        val alone = places.keyFileGranted(FileLocation.Granted(stick.id, StoragePlace("1234-5678", "stick.keyx")))
        files.exists(alone)
        advanceUntilIdle()
        assertTrue(files.exists(alone), "a key file granted alone, as its provider says")
    }

    private companion object {
        const val ROOT = "/storage/emulated/0"
    }
}
