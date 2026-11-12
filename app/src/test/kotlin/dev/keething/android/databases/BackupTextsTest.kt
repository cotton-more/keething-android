package dev.keething.android.databases

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the database page's Backups section says (the canvas "Database backups"). */
class BackupTextsTest {
    @Test
    fun theLineSaysHowManyAndTheirSizeNoneYetOrOff() {
        assertEquals("7 kept, 8.1 MB: the file as it was before the first change of each unlock.", BackupTexts.lead("Family", on = true, 7, "8.1 MB"))
        assertEquals(
            "None yet: the first is kept before Family’s first change after an unlock, the file as it was.",
            BackupTexts.lead("Family", on = true, 0, "0 B"),
        )
        assertEquals("Off: nothing is copied before a change. The 7 kept stay until deleted.", BackupTexts.lead("Family", on = false, 7, "8.1 MB"))
        assertEquals("Off: nothing is copied before a change. The one kept stays until deleted.", BackupTexts.lead("Family", on = false, 1, "1 MB"))
        assertEquals("Off: nothing is copied before a change.", BackupTexts.lead("Family", on = false, 0, "0 B"))
    }

    // spec: databases.backups-window/AC2
    @Test
    fun fewerAsksNamingHowManyOfTheOldestGoAndMoreAsksNothing() {
        assertEquals(2, BackupTexts.gone(count = 7, keep = 5))
        assertEquals(0, BackupTexts.gone(count = 7, keep = 10), "more keeps what comes")
        assertEquals(0, BackupTexts.gone(count = 3, keep = 5), "fewer than are kept: nothing goes, nothing asked")
        assertEquals("Keep 5 backups?", BackupTexts.fewerTitle(5))
        assertEquals("The 2 oldest backups of Family are deleted from this phone now. They can’t be brought back.", BackupTexts.fewerText("Family", 2))
        assertEquals("The oldest backup of Family is deleted from this phone now. It can’t be brought back.", BackupTexts.fewerText("Family", 1))
    }

    @Test
    fun turningItOffAsksWhetherTheBackupsStayOrGo() {
        assertEquals("Stop backing up Family?", BackupTexts.offTitle("Family"))
        assertEquals("Family is no longer copied before its changes. Its 7 backups can stay here or go.", BackupTexts.offText("Family", 7))
        assertEquals("Family is no longer copied before its changes. Its backup can stay here or go.", BackupTexts.offText("Family", 1))
        assertEquals("Keep them" to "Delete them", BackupTexts.offKeep(7) to BackupTexts.offDelete(7))
        assertEquals("Keep it" to "Delete it", BackupTexts.offKeep(1) to BackupTexts.offDelete(1))
    }

    private val zone = ZoneId.of("Europe/Berlin")

    /** Thursday 12 November 2026, 15:10 in Berlin, as the canvas. */
    private val now = Instant.parse("2026-11-12T14:10:00Z")

    private fun berlin(time: String) = Instant.parse(time)

    // spec: databases.backups-window/AC1
    @Test
    fun aBackupSaysItsDateHowLongAgoAndItsSize() {
        assertEquals("Today, 14:03", BackupTexts.date(berlin("2026-11-12T13:03:00Z"), now, zone))
        assertEquals("Yesterday, 21:40", BackupTexts.date(berlin("2026-11-11T20:40:00Z"), now, zone))
        assertEquals("Mon 9 Nov, 08:15", BackupTexts.date(berlin("2026-11-09T07:15:00Z"), now, zone))
        assertEquals("Sun 9 Nov 2025, 08:15", BackupTexts.date(berlin("2025-11-09T07:15:00Z"), now, zone))

        assertEquals("just now", BackupTexts.ago(now.minusSeconds(30), now, zone))
        assertEquals("1 minute ago", BackupTexts.ago(now.minusSeconds(90), now, zone))
        assertEquals("1 hour ago", BackupTexts.ago(berlin("2026-11-12T13:03:00Z"), now, zone))
        assertEquals("17 hours ago", BackupTexts.ago(berlin("2026-11-11T20:40:00Z"), now, zone))
        assertEquals("3 days ago", BackupTexts.ago(berlin("2026-11-09T07:15:00Z"), now, zone))
        assertEquals("4 days ago", BackupTexts.ago(berlin("2026-11-08T18:02:00Z"), now, zone), "by the calendar")
        assertEquals("11 days ago", BackupTexts.ago(berlin("2026-11-01T09:20:00Z"), now, zone))
        assertEquals("3 weeks ago", BackupTexts.ago(berlin("2026-10-20T09:20:00Z"), now, zone))
        assertEquals("12 months ago", BackupTexts.ago(berlin("2025-11-09T07:15:00Z"), now, zone))
        assertEquals("2 years ago", BackupTexts.ago(berlin("2024-11-09T07:15:00Z"), now, zone))

        assertEquals("3 days ago · 1.2 MB", BackupTexts.detail("3 days ago", "1.2 MB", damaged = false))
        assertEquals("Show all 7" to "Show fewer", BackupTexts.more(7, expanded = false) to BackupTexts.more(7, expanded = true))
    }

    // spec: databases.backups-window/AC5
    @Test
    fun aDamagedBackupSaysItWouldNotOpen() {
        assertEquals("5 days ago · damaged: it would not open", BackupTexts.detail("5 days ago", "1.1 MB", damaged = true))
        assertEquals(
            "Its file is damaged: its header does not match its checksum, so it would not open. It cannot be restored.",
            BackupTexts.DAMAGED,
        )
    }

    // spec: databases.backups-window/AC4
    @Test
    fun deletingOneOrAllAsksOnceAndAllSaysTheOldKeyStillOpensThem() {
        assertEquals("Delete this backup?", BackupTexts.DELETE_TITLE)
        assertEquals("The backup of Mon 9 Nov, 08:15 is deleted from this phone. It can’t be brought back.", BackupTexts.deleteText("Mon 9 Nov, 08:15"))
        assertEquals("Deleted the backup of Mon 9 Nov, 08:15", BackupTexts.deleted("Mon 9 Nov, 08:15"))
        assertEquals("Delete all backups?", BackupTexts.deleteAllTitle(7))
        assertEquals("The 7 backups of Family are deleted from this phone. They can’t be brought back.", BackupTexts.deleteAllText("Family", 7))
        assertEquals("The backup of Family is deleted from this phone. It can’t be brought back.", BackupTexts.deleteAllText("Family", 1))
        assertEquals("Backups made before a master key change still open with the old one: this is how they go.", BackupTexts.DELETE_ALL_NOTE)
    }
}
