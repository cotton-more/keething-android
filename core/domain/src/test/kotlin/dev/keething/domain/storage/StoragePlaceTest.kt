package dev.keething.domain.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A place on the phone's storage (keething-spec databases.saf): as the storage provider names it, case aside. */
class StoragePlaceTest {
    private val sync = StoragePlace(StoragePlace.PRIMARY, "Sync")

    @Test
    fun aPathOfBeforeIsAPlaceOnThePhonesStorageOrACard() {
        assertEquals(StoragePlace("primary", "Sync/KeePass/Personal.kdbx"), StoragePlace.ofPath("/storage/emulated/0/Sync/KeePass/Personal.kdbx"))
        assertEquals(StoragePlace("primary", "Sync/Personal.kdbx"), StoragePlace.ofPath("/storage/emulated/0//Sync/./x/../Personal.kdbx/"))
        assertEquals(StoragePlace("primary", "Personal.kdbx"), StoragePlace.ofPath("/sdcard/Personal.kdbx"))
        assertEquals(StoragePlace("primary", "Personal.kdbx"), StoragePlace.ofPath("/storage/self/primary/Personal.kdbx"))
        assertEquals(StoragePlace("primary", "Work.kdbx"), StoragePlace.ofPath("/storage/emulated/10/Work.kdbx"), "another user's storage")
        assertEquals(StoragePlace("1234-5678", "KeePass/Crypto.kdbx"), StoragePlace.ofPath("/storage/1234-5678/KeePass/Crypto.kdbx"))
        assertEquals(StoragePlace("primary", ""), StoragePlace.ofPath("/storage/emulated/0"))
        for (off in listOf("/a/Personal.kdbx", "relative/Personal.kdbx", "/storage", "/storage/emulated/legacy/x.kdbx", "/data/user/0/x.kdbx")) {
            assertNull(StoragePlace.ofPath(off), off)
        }
    }

    @Test
    fun aPlaceInsideAFolderIsItsPathThereCaseAside() {
        val file = StoragePlace("primary", "Sync/KeePass/Personal.kdbx")
        assertEquals("KeePass/Personal.kdbx", file.inside(sync))
        assertEquals("KeePass/Personal.kdbx", file.inside(StoragePlace("PRIMARY", "sync")), "the storage takes names regardless of case")
        assertEquals("Sync/KeePass/Personal.kdbx", file.inside(StoragePlace("primary", "")), "the top holds everything on it")
        assertEquals("", sync.inside(sync))
        assertNull(file.inside(StoragePlace("primary", "Syn")), "not a folder whose name starts like it")
        assertNull(file.inside(StoragePlace("1234-5678", "Sync")), "another volume")
        assertNull(sync.inside(StoragePlace("primary", "Sync/KeePass")))
    }

    @Test
    fun theSamePlaceCaseAsideItsNameItsFolderAndAPathInIt() {
        assertTrue(StoragePlace("primary", "Sync/P.kdbx").sameAs(StoragePlace("Primary", "sync/p.KDBX")))
        assertFalse(StoragePlace("primary", "Sync/P.kdbx").sameAs(StoragePlace("primary", "Sync/Q.kdbx")))
        assertEquals("P.kdbx", StoragePlace("primary", "Sync/P.kdbx").name)
        assertEquals(sync, StoragePlace("primary", "Sync/P.kdbx").parent)
        assertNull(StoragePlace("primary", "").parent)
        assertEquals(StoragePlace("primary", "Sync/KeePass/P.kdbx"), sync.resolve("KeePass/P.kdbx"))
        assertEquals(StoragePlace("primary", "P.kdbx"), StoragePlace("primary", "").resolve("P.kdbx"))
        assertEquals(sync, sync.resolve(""))
    }
}
