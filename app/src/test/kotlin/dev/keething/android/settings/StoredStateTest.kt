package dev.keething.android.settings

import dev.keething.android.generator.PasswordGeneratorState
import dev.keething.android.list.RecentEntries
import dev.keething.android.storage.FileDatabaseBackups
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.domain.biometric.BiometricPasswordStores
import dev.keething.domain.biometric.SeveralOpening
import dev.keething.domain.biometric.StoredPasswordOpening
import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.StoragePlace
import dev.keething.domain.time.Clock
import dev.keething.generator.PasswordKind
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.storage.SaveNotes
import java.io.File
import java.nio.file.Files
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import org.w3c.dom.Element

/**
 * What each release kept, read by this one (keething-spec app.upgrade-safety AC4): `src/test/resources/stored/<release>`
 * holds that release's `settings` SharedPreferences, a stopped save's note and a database's backup, and everything in
 * them is found again.
 * A release adds its own folder and a test here.
 */
class StoredStateTest {
    private val appStorage: File = Files.createTempDirectory("stored-state-test").toFile()

    @AfterTest
    fun deleteAppStorage() {
        appStorage.deleteRecursively()
    }

    // spec: app.upgrade-safety/AC4
    @Test
    fun release001sStateIsAllFoundAfterTheUpdate() {
        val store = settingsOf("0.0.1")

        SettingsVersion.migrate(store)
        val list = DatabaseList(store)
        val settings = AppSettings(store)
        val biometric = BiometricSetup(NoStores, list, store, FixedClock, Dispatchers.Unconfined)

        val folder = StoragePlace(StoragePlace.PRIMARY, "Sync/KeePassBar-test/F5")
        val personal = DatabaseId("7C1E2A4B-0D5F-4E3A-9B61-2F8C4D7E9A10")
        val keyed = DatabaseId("B3D9F0E2-6A71-4C88-8E25-51A0C6F3D274")
        assertEquals(listOf(personal, keyed), list.records.map { it.id })
        assertEquals(personal, list.currentId)
        assertFalse(list.setAside)
        with(list.record(personal)!!) {
            assertEquals(FileLocation.InFolder(GRANT, folder, "F5 Personal.kdbx"), file)
            assertTrue(useBiometric && fingerprintAsked && inWindow && checkedInChooser)
            assertEquals(Instant.ofEpochSecond(1_793_823_480), lastOpened)
        }
        with(list.record(keyed)!!) {
            assertEquals(FileLocation.InFolder(GRANT, folder, "hex64.key"), keyFile)
            assertTrue(!useBiometric && fingerprintAsked && !inWindow && !checkedInChooser)
        }

        assertEquals(15.minutes, settings.lockTimeout.value)
        assertEquals(7.days, settings.requestValidity.value)
        assertTrue(biometric.state.value.enabled)
        assertEquals(30, biometric.state.value.reminderDays)
        assertEquals("1793823480000", store.string(BiometricSetup.lastTypedKey(personal)))
        assertEquals(listOf("$personal:5A2B8C3D9E0F41A2B3C4D5E6F7A8B9C0"), RecentEntries(store).ids.value)
        with(PasswordGeneratorState(store).options) {
            assertEquals(PasswordKind.PASSPHRASE, kind)
            assertEquals(6, passphraseWords)
            assertEquals(".", separator)
        }
        assertEquals(SettingsVersion.CURRENT.toString(), store.string(SettingsVersion.KEY))

        val notes = SaveNotes(filesOf("0.0.1", "saves"))
        assertEquals(emptyList(), notes.unreadable())
        assertTrue(notes.unsettled(GRANT), "the stopped save is put right at the next read")

        // A backup kept before a save, in the database's folder, without the digests 0.0.1 did not write: listed with
        // its file's date and size, and its digest written at its first read.
        val folder001 = filesOf("0.0.1", "backups").resolve(personal.fileName)
        val name = "F5 Personal 2026-11-05T21-14-07.kdbx"
        val written = Instant.parse("2026-11-05T20:14:07Z")
        File(folder001, name).setLastModified(written.toEpochMilli())
        val backups = FileDatabaseBackups(folder001)
        assertEquals(listOf(FileDatabaseBackups.Backup(name, written, 8567, damaged = false)), backups.list())
        assertFalse(File(folder001, FileDatabaseBackups.DIGESTS).exists())
        assertEquals(8567, backups.read(name)?.size)
        assertEquals(
            "55ef20310f33ae0d0282591a60a59850246235f03b368feea3741a17d95331cf 8567 $name\n",
            File(folder001, FileDatabaseBackups.DIGESTS).readText(),
        )
    }

    /** The release's `settings.xml` as SharedPreferences reads it: every value of this app's is a string. */
    private fun settingsOf(release: String): InMemorySettingsStore {
        val xml = javaClass.getResourceAsStream("/stored/$release/shared_prefs/settings.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val store = InMemorySettingsStore()
        val strings = xml.documentElement.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val string = strings.item(i) as Element
            store.setString(string.getAttribute("name"), string.textContent)
        }
        return store
    }

    /** A copy of the release's folder under `files`: the notes are changed when read. */
    private fun filesOf(release: String, folder: String): File {
        val source = File(javaClass.getResource("/stored/$release/files/$folder")!!.toURI())
        return File(appStorage, folder).also { source.copyRecursively(it) }
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-11-07T10:00:00Z")

        override fun elapsed(): Duration = Duration.ZERO
    }

    /** Settings only: nothing here reaches the stored passwords. */
    private object NoStores : BiometricPasswordStores {
        override fun availability(): BiometricAvailability = error("not here")

        override fun hasPassword(database: String): Boolean = error("not here")

        override fun save(database: String, password: Secret) = error("not here")

        override suspend fun open(database: String, onRecognised: () -> Unit): StoredPasswordOpening = error("not here")

        override suspend fun openSeveral(databases: List<String>, onRecognised: () -> Unit, stillWanted: (String) -> Boolean): SeveralOpening =
            error("not here")

        override suspend fun confirm(onRecognised: () -> Unit): StoredPasswordOpening = error("not here")

        override fun delete(database: String) = error("not here")

        override fun deleteAll() = error("not here")
    }

    private companion object {
        const val GRANT = "content://com.android.externalstorage.documents/tree/primary%3ASync%2FKeePassBar-test%2FF5"
    }
}
