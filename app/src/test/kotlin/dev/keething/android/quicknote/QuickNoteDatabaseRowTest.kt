package dev.keething.android.quicknote

import dev.keething.android.databases.DatabaseLabel
import dev.keething.android.databases.DatabaseMark
import dev.keething.android.ui.english
import dev.keething.vault.databases.DatabaseId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickNoteDatabaseRowTest {
    private val personal = DatabaseMark(DatabaseId("P"), DatabaseLabel("Personal"), 0)
    private val family = DatabaseMark(DatabaseId("F"), DatabaseLabel("Family", "Sync/KeePass"), 1)

    @Test
    fun theEditorNamesTheSettingsDatabaseWithTwoOrMoreInTheListAndWhereItIsChosen() {
        assertEquals(family, QuickNoteDatabaseRow.shown(listOf(personal, family), family.id))
        val row = family
        assertEquals("Settings › Quick notes", QuickNoteDatabaseRow.where().english())
        assertEquals("Saved into Family · Sync/KeePass, chosen in Settings › Quick notes", QuickNoteDatabaseRow.said(row).english())
        assertNull(QuickNoteDatabaseRow.shown(listOf(family), family.id), "one database: nothing to tell apart")
        assertNull(QuickNoteDatabaseRow.shown(listOf(personal), family.id), "no longer in the list")
    }
}
