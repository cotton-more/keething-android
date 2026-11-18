package dev.keething.android.databases

import dev.keething.android.ui.english
import dev.keething.android.ui.uiPlain
import dev.keething.vault.saving.SaveFailure
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the database page's Backups section says (the canvas "Database backups"). */
class BackupTextsTest {
    @Test
    fun theLineSaysHowManyAndTheirSizeNoneYetOrOff() {
        assertEquals("7 kept, 8.1 MB: the file as it was before the first change of each unlock.", BackupTexts.lead("Family", on = true, 7, "8.1 MB").english())
        assertEquals(
            "None yet: the first is kept before Family’s first change after an unlock, the file as it was.",
            BackupTexts.lead("Family", on = true, 0, "0 B").english(),
        )
        assertEquals("Off: nothing is copied before a change. The 7 kept stay until deleted.", BackupTexts.lead("Family", on = false, 7, "8.1 MB").english())
        assertEquals("Off: nothing is copied before a change. The one kept stays until deleted.", BackupTexts.lead("Family", on = false, 1, "1 MB").english())
        assertEquals("Off: nothing is copied before a change.", BackupTexts.lead("Family", on = false, 0, "0 B").english())
    }

    // spec: databases.backups-window/AC2
    @Test
    fun fewerAsksNamingHowManyOfTheOldestGoAndMoreAsksNothing() {
        assertEquals(2, BackupTexts.gone(count = 7, keep = 5))
        assertEquals(0, BackupTexts.gone(count = 7, keep = 10), "more keeps what comes")
        assertEquals(0, BackupTexts.gone(count = 3, keep = 5), "fewer than are kept: nothing goes, nothing asked")
        assertEquals("Keep 5 backups?", BackupTexts.fewerTitle(5).english())
        assertEquals("The 2 oldest backups of Family are deleted from this phone now. They can’t be brought back.", BackupTexts.fewerText("Family", 2).english())
        assertEquals("The oldest backup of Family is deleted from this phone now. It can’t be brought back.", BackupTexts.fewerText("Family", 1).english())
    }

    @Test
    fun turningItOffAsksWhetherTheBackupsStayOrGo() {
        assertEquals("Stop backing up Family?", BackupTexts.offTitle("Family").english())
        assertEquals("Family is no longer copied before its changes. Its 7 backups can stay here or go.", BackupTexts.offText("Family", 7).english())
        assertEquals("Family is no longer copied before its changes. Its backup can stay here or go.", BackupTexts.offText("Family", 1).english())
        assertEquals("Keep them" to "Delete them", BackupTexts.offKeep(7).english() to BackupTexts.offDelete(7).english())
        assertEquals("Keep it" to "Delete it", BackupTexts.offKeep(1).english() to BackupTexts.offDelete(1).english())
    }

    private val zone = ZoneId.of("Europe/Berlin")

    /** Thursday 12 November 2026, 15:10 in Berlin, as the canvas. */
    private val now = Instant.parse("2026-11-12T14:10:00Z")

    private fun berlin(time: String) = Instant.parse(time)

    // spec: databases.backups-window/AC1
    @Test
    fun aBackupSaysItsDateHowLongAgoAndItsSize() {
        assertEquals("Today, 14:03", BackupTexts.date(berlin("2026-11-12T13:03:00Z"), now, zone).english())
        assertEquals("Yesterday, 21:40", BackupTexts.date(berlin("2026-11-11T20:40:00Z"), now, zone).english())
        assertEquals("Mon 9 Nov, 08:15", BackupTexts.date(berlin("2026-11-09T07:15:00Z"), now, zone).english())
        assertEquals("Sun 9 Nov 2025, 08:15", BackupTexts.date(berlin("2025-11-09T07:15:00Z"), now, zone).english())

        assertEquals("just now", BackupTexts.ago(now.minusSeconds(30), now, zone).english())
        assertEquals("1 minute ago", BackupTexts.ago(now.minusSeconds(90), now, zone).english())
        assertEquals("1 hour ago", BackupTexts.ago(berlin("2026-11-12T13:03:00Z"), now, zone).english())
        assertEquals("17 hours ago", BackupTexts.ago(berlin("2026-11-11T20:40:00Z"), now, zone).english())
        assertEquals("3 days ago", BackupTexts.ago(berlin("2026-11-09T07:15:00Z"), now, zone).english())
        assertEquals("4 days ago", BackupTexts.ago(berlin("2026-11-08T18:02:00Z"), now, zone).english(), "by the calendar")
        assertEquals("11 days ago", BackupTexts.ago(berlin("2026-11-01T09:20:00Z"), now, zone).english())
        assertEquals("3 weeks ago", BackupTexts.ago(berlin("2026-10-20T09:20:00Z"), now, zone).english())
        assertEquals("12 months ago", BackupTexts.ago(berlin("2025-11-09T07:15:00Z"), now, zone).english())
        assertEquals("2 years ago", BackupTexts.ago(berlin("2024-11-09T07:15:00Z"), now, zone).english())

        assertEquals("3 days ago · 1.2 MB", BackupTexts.detail(uiPlain("3 days ago"), "1.2 MB", damaged = false).english())
        assertEquals("Show all 7" to "Show fewer", BackupTexts.more(7, expanded = false).english() to BackupTexts.more(7, expanded = true).english())
    }

    // spec: databases.backups-window/AC5
    @Test
    fun aDamagedBackupSaysItWouldNotOpen() {
        assertEquals("5 days ago · damaged: it would not open", BackupTexts.detail(uiPlain("5 days ago"), "1.1 MB", damaged = true).english())
        assertEquals(
            "Its file is damaged: its header does not match its checksum, so it would not open. It cannot be restored.",
            BackupTexts.DAMAGED.english(),
        )
    }

    // spec: databases.backups-window/AC4
    @Test
    fun deletingOneOrAllAsksOnceAndAllSaysTheOldKeyStillOpensThem() {
        assertEquals("Delete this backup?", BackupTexts.DELETE_TITLE.english())
        assertEquals("The backup of Mon 9 Nov, 08:15 is deleted from this phone. It can’t be brought back.", BackupTexts.deleteText(uiPlain("Mon 9 Nov, 08:15")).english())
        assertEquals("Deleted the backup of Mon 9 Nov, 08:15", BackupTexts.deleted(uiPlain("Mon 9 Nov, 08:15")).english())
        assertEquals("Delete all backups?", BackupTexts.deleteAllTitle(7).english())
        assertEquals("The 7 backups of Family are deleted from this phone. They can’t be brought back.", BackupTexts.deleteAllText("Family", 7).english())
        assertEquals("The backup of Family is deleted from this phone. It can’t be brought back.", BackupTexts.deleteAllText("Family", 1).english())
        assertEquals("Backups made before a master key change still open with the old one: this is how they go.", BackupTexts.DELETE_ALL_NOTE.english())
    }

    // spec: databases.backups-window/AC6
    @Test
    fun theRestoreAsksNamingTheDateAndThatTheChangesSinceLeaveEveryDevice() {
        assertEquals("Restore this backup?", BackupTexts.RESTORE_TITLE.english())
        assertEquals(
            "Family becomes as it was on Mon 9 Nov, 08:15. Changes made since leave the database, here and on every device it syncs with.",
            BackupTexts.restoreText("Family", uiPlain("Mon 9 Nov, 08:15")).english(),
        )
        assertEquals("The file as it is now is kept as a backup first, so the restore can be undone.", BackupTexts.RESTORE_NOTE.english())
        assertEquals(
            "Family becomes as it was today, 14:03. Changes made since leave the database, here and on every device it syncs with.",
            BackupTexts.restoreText("Family", BackupTexts.date(berlin("2026-11-12T13:03:00Z"), now, zone)).english(),
        )
        assertEquals("Family becomes as it was then", BackupTexts.restoreLine("Family").english())
        assertEquals("Restoring the backup of Mon 9 Nov, 08:15…", BackupTexts.restoring(uiPlain("Mon 9 Nov, 08:15")).english())
    }

    // spec: databases.backups-window/AC7
    @Test
    fun doneItSaysWhichBackupAndAnOlderKeyLocksSayingSo() {
        assertEquals("Restored the backup of Mon 9 Nov, 08:15", BackupTexts.restored(uiPlain("Mon 9 Nov, 08:15")).english())
        assertEquals("Back to Family as it was before the restore", BackupTexts.undone("Family").english())
        assertEquals(
            "Restored. This backup opens with the master key Family had on Sun 1 Nov, 10:20, so Family is locked: unlock it with that password. Its fingerprint stays off until then.",
            BackupTexts.olderKey("Family", uiPlain("Sun 1 Nov, 10:20")).english(),
        )
        assertTrue(BackupTexts.olderKey("Family", BackupTexts.date(berlin("2026-11-11T20:40:00Z"), now, zone)).english().contains("the master key Family had yesterday, 21:40, so"))
    }

    // spec: databases.backups-window/AC8
    @Test
    fun aFailedRestoreSaysWhyAndThatNothingChanged() {
        val file = java.io.File("/storage/emulated/0/Sync/KeePass/Family.kdbx")
        assertEquals(
            "Not restored: Family.kdbx could not be written (Keething has no access to its folder any more). Nothing was changed, and no backup was added.",
            BackupTexts.notRestored(file, SaveFailure.WriteFailed("Keething has no access to its folder any more")).english(),
        )
        assertEquals(
            "Not restored. That backup is not there any more. Nothing was changed, and no backup was added.",
            BackupTexts.notRestored(file, SaveFailure.BackupGone).english(),
        )
    }

    // spec: databases.backups-window/AC9
    @Test
    fun aReadOnlyDatabaseSaysItsBackupsAreNotRestored() {
        assertEquals("Family is read only on this phone: its backups are listed and deleted, not restored.", BackupTexts.readOnly("Family").english())
    }
}
