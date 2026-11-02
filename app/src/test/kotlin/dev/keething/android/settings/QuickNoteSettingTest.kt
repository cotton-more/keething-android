package dev.keething.android.settings

import dev.keething.android.databases.Reach
import dev.keething.android.databases.DatabaseLabel
import dev.keething.android.databases.DatabaseLine
import dev.keething.vault.databases.DatabaseId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings' "Quick notes go to" (SPEC §6.4; keething-spec app.quick-note AC1; the Mac's `QuickNoteTests`, "Where it
 * goes"): the database's id stored, removed when unset, read back; the choices with their dots; one gone falls back
 * and says so.
 */
class QuickNoteSettingTest {
    private fun line(name: String, colorIndex: Int, current: Boolean = false, addition: String? = null) = DatabaseLine(
        id = DatabaseId(name),
        label = DatabaseLabel(name, addition),
        fileName = "$name.kdbx",
        path = "/storage/emulated/0/Sync/$name.kdbx",
        folder = "Sync",
        status = null,
        reach = Reach.THERE,
        keyFile = null,
        isCurrent = current,
        lastOpened = null,
        modified = null,
        colorIndex = colorIndex,
    )

    private val personal = line("Personal", 2, current = true)
    private val work = line("Work", 0)
    private val lines = listOf(personal, work)

    @Test
    fun theDatabaseSetIsStoredAsItsIdAndReadBackUnsetIsAbsent() {
        val store = InMemorySettingsStore()
        val settings = AppSettings(store)
        assertNull(settings.quickNoteDatabase.value, "unset by default: the current database")

        settings.chooseQuickNoteDatabase(DatabaseId("A-1"))
        assertEquals(DatabaseId("A-1"), settings.quickNoteDatabase.value)
        assertEquals(mapOf("quickNoteDatabase" to "A-1"), store.snapshot(), "its id alone")
        assertEquals(DatabaseId("A-1"), AppSettings(store).quickNoteDatabase.value, "kept across a new process")

        settings.chooseQuickNoteDatabase(null)
        assertNull(settings.quickNoteDatabase.value)
        assertEquals(emptyMap(), store.snapshot(), "unset again: nothing stored")
        assertNull(AppSettings(store).quickNoteDatabase.value)

        store.setString("quickNoteDatabase", "")
        assertNull(AppSettings(store).quickNoteDatabase.value, "an empty value is none")
    }

    @Test
    fun currentDatabaseFirstThenTheListsWithTheirDots() {
        val destination = QuickNoteDestination(null, lines)
        assertEquals(listOf("Current database", "Personal", "Work"), destination.choices.map { it.title })
        assertEquals(listOf(null, personal.id, work.id), destination.choices.map { it.id })
        assertEquals(listOf(null, 2, 0), destination.choices.map { it.colorIndex }, "each database with its dot; Current database none")
        assertEquals(listOf("Personal now", "Personal.kdbx", "Work.kdbx"), destination.choices.map { it.detail })
        assertNull(destination.selected)
        assertEquals("Current database", destination.summary)
        assertNull(destination.note)
    }

    @Test
    fun aDatabaseSetIsTheOneSelectedTwoOfOneNameToldApart() {
        val set = QuickNoteDestination(work.id, lines)
        assertEquals(work.id, set.selected)
        assertEquals("Work", set.summary)
        assertNull(set.note)

        val twin = line("Work-2", 4, addition = "Work-2024.kdbx").copy(label = DatabaseLabel("Work", "Work-2024.kdbx"))
        val both = QuickNoteDestination(twin.id, lines + twin)
        assertEquals("Work · Work-2024.kdbx", both.choices.last().title, "named as the list names it")
        assertEquals("Work · Work-2024.kdbx", both.summary)
    }

    @Test
    fun oneNoLongerInTheListFallsBackToTheCurrentAndSaysSo() {
        val gone = QuickNoteDestination(DatabaseId("removed"), lines)
        assertNull(gone.selected, "falls back to the current one")
        assertEquals("Current database", gone.summary)
        assertEquals("The database chosen before is no longer in the list: quick notes go to the current one.", gone.note)
    }

    @Test
    fun whileTheListIsStillBeingReadNothingIsSaidAndNothingOffered() {
        val loading = QuickNoteDestination(work.id, null)
        assertNull(loading.note, "not \"no longer in the list\" before the list is known")
        assertTrue(loading.isLoading)
        assertEquals("", loading.summary, "nothing named yet")
        assertEquals(emptyList(), loading.choices, "no menu to choose from yet")
        assertFalse(QuickNoteDestination(work.id, lines).isLoading)
    }

    @Test
    fun noDatabaseAtAll() {
        val none = QuickNoteDestination(null, emptyList())
        assertEquals(listOf("Current database"), none.choices.map { it.title })
        assertNull(none.choices.single().detail, "no current one to name")
    }
}
