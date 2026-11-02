package dev.keething.android.databases

import dev.keething.android.storage.PathNewFile
import dev.keething.vault.creating.CreationNote
import dev.keething.domain.encryption.DatabaseEncryption
import dev.keething.domain.encryption.DatabaseEncryption.Cipher
import dev.keething.domain.encryption.DatabaseEncryption.KeyDerivation
import dev.keething.domain.encryption.MeasuredEncryption
import dev.keething.domain.secret.Secret
import dev.keething.vault.creating.CreateFailure
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** New database's files on real files: made with O_EXCL, noted while Create runs, only its own removed (plan AD7). */
class FileDatabaseMakingTest {
    private val folder: File = Files.createTempDirectory("file-database-making-test").toFile()
    private val shared = File(folder, "shared").apply { mkdirs() }
    private val noteFile = File(folder, "creating.note")
    private val making = FileDatabaseMaking({ PathNewFile(it, ownerOnly = false) }, CreationNote(noteFile))
    private val cheap = MeasuredEncryption(DatabaseEncryption(Cipher.AES256, KeyDerivation.ARGON2D, 1, 1, 0.1), units = 2, seconds = 0.01)

    @AfterTest
    fun deleteFolder() {
        folder.walkBottomUp().forEach { it.setWritable(true) }
        folder.deleteRecursively()
    }

    @Test
    fun aKeyFileAndADatabaseAreMadeNotedWhileCreatingRunsAndTheNoteGoesAtTheEnd() {
        val key = File(shared, "Family.keyx")
        val database = File(shared, "Family.kdbx")
        making.begin("Family")
        val fileKey = making.createKeyFile(key.path)
        val password = Secret.of("pw")
        making.create(database.path, "Family", password, fileKey, cheap).wipe()
        assertTrue(key.isFile && database.isFile)
        assertTrue(noteFile.readText().contains(database.path) && noteFile.readText().contains(key.path), "both noted while it runs")
        making.end(made = true)
        assertFalse(noteFile.exists())
        assertNull(CreationNote(noteFile).leftovers(), "nothing to clean up at the next start")
    }

    @Test
    fun aKeyFileMadeForADatabaseThatWasNotMadeIsRemovedAndOnlyThatOne() {
        val key = File(shared, "Family.keyx")
        making.begin("Family")
        making.createKeyFile(key.path).wipe()
        val database = File(shared, "Family.kdbx").apply { writeText("a sync put this here") }
        assertFailsWith<CreateFailure.Exists> { making.create(database.path, "Family", Secret.of("pw"), null, cheap) }
        making.removeKeyFile(key.path)
        assertFalse(key.exists(), "never used: it goes")
        assertEquals("a sync put this here", database.readText(), "never replaced")
        making.end(made = false)
        assertFalse(noteFile.exists(), "nothing of it left")
    }

    @Test
    fun aKeyFileAlreadyThereIsNeverReplacedNorRemoved() {
        val key = File(shared, "Family.keyx").apply { writeText("someone's own") }
        making.begin("Family")
        assertFailsWith<CreateFailure.Exists> { making.createKeyFile(key.path) }
        making.removeKeyFile(key.path)
        assertEquals("someone's own", key.readText(), "not this Create's: it stays")
        making.end(made = false)
    }

    @Test
    fun aKeyFileTheUserHasIsOnlyRead() {
        val key = File(shared, "mine.keyx").apply { writeText("any file is a key file") }
        making.begin("Family")
        making.readKeyFile(key.path).wipe()
        making.removeKeyFile(key.path)
        assertEquals("any file is a key file", key.readText())
        assertFailsWith<CreateFailure.KeyFileUnusable> { making.readKeyFile(File(shared, "gone.keyx").path) }
        making.end(made = false)
    }

    @Test
    fun aNewKeyFileThatCannotBeRemovedStaysNotedAndTheNextStartTakesIt() {
        val keys = File(shared, "Keys").apply { mkdirs() }
        val key = File(keys, "Family.keyx")
        making.begin("Family")
        making.createKeyFile(key.path).wipe()
        keys.setWritable(false)
        runCatching { making.removeKeyFile(key.path) }
        making.end(made = false)
        assertTrue(key.exists() && noteFile.exists(), "not removed: still noted")
        keys.setWritable(true)
        assertEquals(CreationNote.Leftover("Family", emptyList()), CreationNote(noteFile).leftovers())
        assertFalse(key.exists(), "the next start removes it")
    }

    @Test
    fun syncthingsMarkerInTheFolderOrAboveIt() {
        val files = PhoneNewDatabaseFiles(shared, writable = { true })
        val synced = File(shared, "Sync").apply { mkdirs() }
        File(synced, ".stfolder").mkdirs()
        val deep = File(synced, "KeePass/Keys").apply { mkdirs() }
        val apart = File(shared, "Documents").apply { mkdirs() }
        assertTrue(files.syncs(synced.path))
        assertTrue(files.syncs(deep.path), "a folder inside a synced one")
        assertFalse(files.syncs(apart.path))
        assertFalse(files.syncs(shared.path))
        assertTrue(files.exists(synced.path) && files.isFolder(synced.path) && !files.exists(File(shared, "none").path))
    }
}
