package dev.keething.domain.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The fake behaves as stage S0 measured the Pixel 6's storage provider (keething-spec features/databases/saf/plan.md §1). */
class FakeGrantedFolderTest {
    private val folder = FakeGrantedFolder()

    @Test
    fun aNameTakenGivesANumberedNameNeverAFileOverAnother() {
        folder.put("Sync/saf-s0-a.bin", byteArrayOf(1))
        folder.put("Sync/b.bin", byteArrayOf(2))

        assertEquals("saf-s0-a (1).bin", folder.rename("Sync/b.bin", "saf-s0-a.bin"))
        assertEquals("saf-s0-a (2).bin", folder.create("Sync", "saf-s0-a.bin"))
        folder.create("Sync", ".syncthing.keething-x.tmp")
        assertEquals(".syncthing.keething-x (1).tmp", folder.create("Sync", ".syncthing.keething-x.tmp"))
        assertContentEquals(byteArrayOf(1), folder.bytesOf("Sync/saf-s0-a.bin"))
    }

    @Test
    fun namesAreTakenRegardlessOfCaseAsStageS3MeasuredThem() {
        folder.put("Sync/probe-case.tmp", byteArrayOf(1))
        folder.put("Sync/probe-other.tmp", byteArrayOf(2))
        folder.put("Sync/probe-self.tmp", byteArrayOf(3))

        assertEquals("probe-CASE (1).tmp", folder.create("Sync", "probe-CASE.tmp"))
        assertEquals("probe-Case (2).tmp", folder.rename("Sync/probe-other.tmp", "probe-Case.tmp"))
        assertEquals("probe-SELF (1).tmp", folder.rename("Sync/probe-self.tmp", "probe-SELF.tmp"), "never a rename in case alone")
        assertEquals(folder.versionOf("Sync/probe-case.tmp"), folder.version("SYNC/PROBE-CASE.TMP"), "a path finds its file in any case")
        assertEquals(setOf("probe-case.tmp", "probe-CASE (1).tmp", "probe-Case (2).tmp", "probe-SELF (1).tmp"), folder.list("sync")?.map { it.name }?.toSet())
    }

    @Test
    fun aWriteOverALongerFileKeepsItsTail() {
        folder.put("Sync/a.bin", byteArrayOf(1, 2, 3, 4))

        folder.write("Sync/a.bin", byteArrayOf(9))

        assertContentEquals(byteArrayOf(9, 2, 3, 4), folder.bytesOf("Sync/a.bin"))
    }

    @Test
    fun aRenameKeepsTheFileItsIdentityAndItsTimeAndAPutIsANewFile() {
        folder.put("Sync/a.bin", byteArrayOf(1))
        val before = folder.versionOf("Sync/a.bin")

        folder.rename("Sync/a.bin", "b.bin")
        assertEquals(before, folder.version("Sync/b.bin"))
        assertNull(folder.version("Sync/a.bin"))

        folder.put("Sync/b.bin", byteArrayOf(1), before!!.modified)
        assertNotEquals(before, folder.version("Sync/b.bin"))
    }

    @Test
    fun aWatchEndsWithTheProvidersProcessWithoutAWord() {
        folder.makeFolder("Sync")
        var notices = 0
        val watch = folder.watch("Sync") { notices++ }

        folder.put("Sync/a.bin", byteArrayOf(1))
        folder.deliverNotices()
        folder.endWatches()
        folder.put("Sync/a.bin", byteArrayOf(2))
        folder.deliverNotices()

        assertEquals(1, notices)
        assertFalse(watch.isAlive())
    }
}
