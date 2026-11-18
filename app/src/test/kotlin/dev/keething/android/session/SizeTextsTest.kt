package dev.keething.android.session

import dev.keething.android.editor.describe
import dev.keething.android.ui.english
import dev.keething.vault.opening.DatabaseSize
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.session.AttachmentQuestion
import dev.keething.vault.session.SaveRoom
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the app says of a database too large to save, or near that (keething-spec app.large-database AC3). */
class SizeTextsTest {
    private val mib = 1L shl 20
    private val attachments = DatabaseSize(87 * mib, 80 * mib)
    private val entries = DatabaseSize(30 * mib, 2 * mib)

    @Test
    fun readOnlySaysHowLargeItIsThatItsAttachmentsMakeItSoAndWhatToDo() {
        assertEquals(
            "This phone does not give Keething enough memory to save large.kdbx safely, so it is open to read only: you " +
                "can read and copy from it, not change it. It is 87\u00A0MiB; its attachments are 80\u00A0MiB. To make it " +
                "smaller, keep large files outside the database or split it into two databases. An attachment removed " +
                "stays in its entry's history until KeePassXC on a computer clears that.",
            SizeTexts.tooLarge("large.kdbx", attachments, alone = true).english(),
        )
    }

    @Test
    fun besideOthersItSaysToLockThem() {
        assertEquals(
            "With the other open databases beside it, this phone does not give Keething enough memory to save large.kdbx " +
                "safely, so it is open to read only: you can read and copy from it, not change it. It is 87\u00A0MiB; its " +
                "attachments are 80\u00A0MiB. Lock the others to change it: it becomes changeable once a save fits.",
            SizeTexts.tooLarge("large.kdbx", attachments, alone = false).english(),
        )
        assertEquals(
            "“Work” is open to read only: with the other databases open, too large for this phone to save safely. Lock " +
                "them to change it.",
            SizeTexts.refused("Work", SaveRoom.TooLarge(alone = false)).english(),
        )
        assertEquals("“Work” is open to read only: too large for this phone to save safely.", SizeTexts.refused("Work", SaveRoom.TooLarge(alone = true)).english())
    }

    @Test
    fun changeableAgainOnceTheOthersLockedAndNearTheLimitToKeepThemLocked() {
        assertEquals(
            "With the other databases locked, this phone has the memory to save large.kdbx: you can change it again.",
            SizeTexts.changeableAgain("large.kdbx", near = false).english(),
        )
        assertEquals(
            "With the other databases locked, this phone has the memory to save large.kdbx: you can change it again. A " +
                "save takes most of that memory, so keep the others locked while you change it.",
            SizeTexts.changeableAgain("large.kdbx", near = true).english(),
        )
    }

    @Test
    fun aDatabaseLargeForItsEntriesIsToldToSplitNotToMoveAttachments() {
        assertEquals(
            "A save of notes.kdbx takes most of the memory this phone gives Keething. It saves now, but not much larger. " +
                "It is 30\u00A0MiB. To make it smaller, split it into two databases, or remove old history in KeePassXC " +
                "on a computer.",
            SizeTexts.nearLimit("notes.kdbx", entries).english(),
        )
    }

    @Test
    fun aSaveRefusedOrOutOfMemorySaysNothingWasSavedAndWhatToDo() {
        val file = File("large.kdbx")
        assertEquals(
            "This database is open to read only: too large for this phone to save safely. Nothing was saved.",
            SaveFailure.TooLarge(alone = true).describe(file).english(),
        )
        assertEquals(
            "This database is open to read only: too large for this phone to save safely. Nothing was saved. Lock the " +
                "other databases to change it.",
            SaveFailure.TooLarge(alone = false).describe(file).english(),
        )
        assertEquals(
            "Not enough memory to write the database. Nothing was saved. Lock the other open databases and try again; if " +
                "it still fails, keep large files outside the database or split it into two.",
            SaveFailure.OutOfMemory.describe(file).english(),
        )
    }

    // spec: app.large-database/AC4
    @Test
    fun aLargeAttachmentIsAskedAboutWithWhatItDoesAndTheSameAdvice() {
        val advice = " Keep large files outside the database, or split it into two databases."
        assertEquals(
            "scan.mov is 42 MB. Every unlock and every save of Family.kdbx reads and writes all of it: large files make it " +
                "slower and take memory." + advice,
            SizeTexts.largeAttachment("scan.mov", "42 MB", AttachmentQuestion(40 * mib, large = true, room = null), "Family.kdbx").english(),
        )
        assertEquals(
            "a.pdf is 2.1 MB. With it, a save of Family.kdbx takes most of the memory this phone gives Keething: it still " +
                "saves, but not much larger." + advice,
            SizeTexts.largeAttachment("a.pdf", "2.1 MB", AttachmentQuestion(2 * mib, large = false, room = SaveRoom.Near), "Family.kdbx").english(),
        )
        assertEquals(
            "a.pdf is 2.1 MB. With it, this phone does not give Keething enough memory to save Family.kdbx safely beside " +
                "the other open databases: lock them before you save." + advice,
            SizeTexts.largeAttachment("a.pdf", "2.1 MB", AttachmentQuestion(2 * mib, large = false, room = SaveRoom.TooLarge(alone = false)), "Family.kdbx").english(),
        )
    }
}
