package dev.keething.android.databases

import dev.keething.android.ui.english
import dev.keething.domain.vault.VaultEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A database's page, its name and colour (keething-spec databases.rename-recolour AC2–AC4). */
class NameAndColorFormTest {
    private val work = NameAndColorForm(fileName = "Work", fileColor = "#5856D6", databaseFile = "Work.kdbx")

    // spec: databases.rename-recolour/AC4
    @Test
    fun onlyWhatChangedIsWritten() {
        assertNull(work.edit("Work", 5), "as the file has it")
        assertNull(work.edit("  Work ", 5), "outer spaces are no change")
        assertEquals(VaultEdit.NameAndColor(name = "Office"), work.edit(" Office ", 5))
        assertEquals(VaultEdit.NameAndColor(color = "#2E9E4F"), work.edit("Work", 2))
        assertEquals(VaultEdit.NameAndColor(name = "Office", color = "#D93A3A"), work.edit("Office", 7))
    }

    // spec: databases.rename-recolour/AC2
    @Test
    fun anEmptyNameAndNoColourAreWrittenAsNone() {
        assertEquals(VaultEdit.NameAndColor(name = "", color = ""), work.edit("   ", null))
        assertEquals("Empty: it is called by its file name, Work.", work.nameNote("").english())
        assertEquals("“Passwords” counts as no name: it is called by its file name, Work.", work.nameNote("Passwords").english())
        assertEquals("Its file stays Work.kdbx.", work.nameNote("Office").english())
    }

    // spec: databases.rename-recolour/AC3
    @Test
    fun aFileColourOutOfThePaletteIsShownAsItsNearestAndKeptUntilAnotherIsChosen() {
        val yellow = NameAndColorForm(fileName = "Home", fileColor = "FFD700", databaseFile = "Home.kdbx")

        assertEquals(1, yellow.fileColorIndex, "yellow is shown as orange")
        assertNull(yellow.edit("Home", 1), "kept as it is")
        assertEquals("Its file's own colour, shown as the nearest of these; it stays as it is until another is chosen.", yellow.colorNote(1).english())
        assertEquals(VaultEdit.NameAndColor(color = "#0E9AA7"), yellow.edit("Home", 0))
        val none = NameAndColorForm(fileName = "", fileColor = null, databaseFile = "Home.kdbx")
        assertNull(none.fileColorIndex)
        assertNull(none.edit("", null))
        assertEquals(VaultEdit.NameAndColor(color = "#8E8E93"), none.edit("", 8))
    }
}
