package dev.keething.android.databases

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
}
