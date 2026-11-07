package dev.keething.vault.session

import dev.keething.vault.opening.DatabaseSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The room a save needs against the heap the app is given (keething-spec app.large-database AC3,
 * plans/android-large-database.md LD2): the Pixel 6's files and heap of §1, and a phone that gives less.
 */
class SaveMemoryTest {
    private fun mib(n: Long) = n shl 20

    /** `task large:database`'s files on the phone, most of each attachments: 41.4, 82.7 and 100.1 MiB. */
    private val large5000 = DatabaseSize(43_376_873, mib(40))
    private val large10000 = DatabaseSize(86_748_989, mib(80))
    private val large100 = DatabaseSize(104_982_261, mib(97))

    /** A file of [mib] MiB. */
    private fun file(mib: Long) = DatabaseSize(mib(mib), mib(mib) * 9 / 10)

    private val pixel6 = mib(512)

    @Test
    fun theNeedIsWhatTheOpenDatabasesHoldAndThreeAndAHalfTimesTheFileSavedOverIt() {
        // Measured at a save's peak: 366 MiB alone, 460 beside the 41.4 MiB file, the 100 MiB one's 506 beside it.
        // The estimate is above each.
        assertEquals(413, SaveMemory.need(large10000, listOf(large10000)) shr 20)
        assertEquals(466, SaveMemory.need(large10000, listOf(large10000, large5000)) shr 20)
        assertEquals(550, SaveMemory.need(large100, listOf(large100, large5000)) shr 20)
    }

    @Test
    fun onThePixel6TheFortyMiBFileSavesTheEightyIsNearTheLimitAndAHundredIsReadOnly() {
        assertEquals(SaveRoom.Enough, SaveMemory.judge(large5000, emptyList(), pixel6))
        assertEquals(SaveRoom.Near, SaveMemory.judge(large10000, emptyList(), pixel6))
        assertEquals(SaveRoom.TooLarge(alone = true), SaveMemory.judge(large100, emptyList(), pixel6))
    }

    @Test
    fun entriesDoNotCountBytesDo() {
        // A file of the same size with no attachments is judged the same: its XML is the cost then.
        assertEquals(SaveRoom.Near, SaveMemory.judge(large10000.copy(attachmentBytes = 0), emptyList(), pixel6))
    }

    @Test
    fun aDatabaseThatWouldFitAloneIsReadOnlyBesideAnotherAndSaysThatIsWhy() {
        val small = OpenSize(large5000, saves = true)
        assertEquals(SaveRoom.TooLarge(alone = false), SaveMemory.judge(large10000, listOf(small), pixel6))
    }

    @Test
    fun theOneOpenedLaterIsReadOnlyWhenItWouldLeaveAnOpenOneWithoutRoomToSave() {
        // The 41.4 MiB file's own save fits, but beside it the open 82.7 MiB one's would not: the later one gives way.
        val large = OpenSize(large10000, saves = true)
        assertEquals(SaveRoom.TooLarge(alone = false), SaveMemory.judge(large5000, listOf(large), pixel6))
    }

    @Test
    fun anOpenDatabaseThatDoesNotSaveLeavesTheLaterOneItsRoom() {
        // The 82.7 MiB one open read-only (or KDBX 3.1) holds its memory but never saves.
        val large = OpenSize(large10000, saves = false)
        assertEquals(SaveRoom.Enough, SaveMemory.judge(large5000, listOf(large), pixel6))
    }

    @Test
    fun aPhoneThatGivesLessMemoryWarnsSooner() {
        val withoutLargeHeap = mib(256)
        assertEquals(SaveRoom.Near, SaveMemory.judge(large5000, emptyList(), withoutLargeHeap))
        assertEquals(SaveRoom.TooLarge(alone = true), SaveMemory.judge(file(50), emptyList(), withoutLargeHeap))
    }

    @Test
    fun theLinesAreThreeQuartersAndNineTenthsOfTheHeap() {
        // need = 16 MiB + 4.8 × the file alone: 75 % of 512 MiB is reached at 76.7 MiB, 90 % at 92.7 MiB; beside the
        // 41.4 MiB file, 90 % at 81.5 MiB.
        assertEquals(SaveRoom.Enough, SaveMemory.judge(file(76), emptyList(), pixel6))
        assertEquals(SaveRoom.Near, SaveMemory.judge(file(77), emptyList(), pixel6))
        assertEquals(SaveRoom.Near, SaveMemory.judge(file(92), emptyList(), pixel6))
        assertEquals(SaveRoom.TooLarge(alone = true), SaveMemory.judge(file(93), emptyList(), pixel6))
        val small = listOf(OpenSize(large5000, saves = true))
        assertEquals(SaveRoom.Near, SaveMemory.judge(file(81), small, pixel6))
        assertEquals(SaveRoom.TooLarge(alone = false), SaveMemory.judge(file(82), small, pixel6))
    }
}
