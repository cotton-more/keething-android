package dev.keething.android.storage

import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.StoragePlace
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.storage.FolderDatabaseStore
import dev.keething.vault.storage.SaveNotes
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import dev.keething.android.session.AndroidClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Which store a database of the list is read and saved through (keething-spec databases.saf AC1, AC2, AC13). */
class DatabaseStoresTest {
    private val notes = SaveNotes(Files.createTempDirectory("stores-test").toFile())

    private fun storeOf(file: FileLocation) = databaseStoreOf(file, folderOf = { null }, notes, AndroidClock)

    // spec: app.upgrade-safety/AC3
    @Test
    fun aDatabaseReachedThroughAGrantToItsFileAloneReadsAsNoAccessInsteadOfEndingTheApp() {
        val store = storeOf(FileLocation.Granted("grant-file", StoragePlace("primary", "Sync/Personal.kdbx")))

        assertEquals("Personal.kdbx", store.fileName)
        assertEquals(DatabaseRead.AccessDenied, store.read())
        assertFailsWith<AccessDeniedException> { store.replace(byteArrayOf(1)) }
    }

    @Test
    fun oneKeptByItsPathIsNeverReadAndOneInAFolderGoesThroughIt() {
        assertEquals(DatabaseRead.AccessDenied, storeOf(FileLocation.ByPath("/storage/emulated/0/Sync/Personal.kdbx")).read())
        assertIs<FolderDatabaseStore>(storeOf(FileLocation.InFolder("grant-sync", StoragePlace("primary", "Sync"), "Personal.kdbx")))
    }
}
