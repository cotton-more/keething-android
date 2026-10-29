package dev.keething.android.storage

import dev.keething.domain.storage.KeyFileRead
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PathKeyFileSourceTest {
    private val folder: File = Files.createTempDirectory("key-file-test").toFile()

    @AfterTest
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun aKeyFileIsReadAtItsPathAndOneNotThereIsSaidSo() {
        val file = File(folder, "work.keyx").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val source = PathKeyFileSource(file)

        assertEquals("work.keyx", source.fileName)
        assertContentEquals(byteArrayOf(1, 2, 3), assertIs<KeyFileRead.Found>(source.read()).bytes)
        file.delete()
        assertEquals(KeyFileRead.Missing, source.read())
    }

    @Test
    fun aPickedDocumentIsItsPathOnThePhonesStorage() {
        val primary = File("/storage/emulated/0")

        assertEquals(File("/storage/emulated/0/Sync/KeePassBar-test/Personal.kdbx"), FolderPaths.pathOf("com.android.externalstorage.documents", "primary:Sync/KeePassBar-test/Personal.kdbx", primary))
        assertEquals(File("/storage/emulated/0"), FolderPaths.pathOf("com.android.externalstorage.documents", "primary:", primary))
        assertEquals(File("/storage/1234-5678/Keys/a.keyx"), FolderPaths.pathOf("com.android.externalstorage.documents", "1234-5678:Keys/a.keyx", primary))
        assertEquals(File("/storage/emulated/0/Download/a.keyx"), FolderPaths.pathOf("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download/a.keyx", primary))
        assertEquals(File("/storage/emulated/0/Download/a.keyx"), FolderPaths.pathOf("com.android.providers.downloads.documents", "raw:/sdcard/Download/a.keyx", primary))
        assertEquals(File("/storage/emulated/0/Download/a.keyx"), FolderPaths.pathOf("com.android.providers.downloads.documents", "raw:/storage/self/primary/Download/a.keyx", primary))
        assertEquals(File("/storage/emulated/0/Documents/Keys/a.keyx"), FolderPaths.pathOf("com.android.externalstorage.documents", "home:Keys/a.keyx", primary))
        assertNull(FolderPaths.pathOf("com.android.providers.downloads.documents", "msf:1234", primary), "a media item has no path")
        assertNull(FolderPaths.pathOf("com.google.android.apps.docs.storage", "doc=abc", primary))
        assertNull(FolderPaths.pathOf("com.android.externalstorage.documents", "nothing", primary))
    }
}
