package dev.keething.android.list

import dev.keething.android.settings.InMemorySettingsStore
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import dev.keething.vault.databases.StoredEntryIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Recent by refs, 8 kept per database (plan AD9; keething-spec search.recent AC7; the Mac's D6 `RecentEntries`). */
class RecentEntriesTest {
    private val store = InMemorySettingsStore()
    private val personal = DatabaseId("P")
    private val work = DatabaseId("W")

    private fun p(id: String) = EntryRef(personal, id).text
    private fun w(id: String) = EntryRef(work, id).text

    @Test
    fun aUsedEntryMovesToTheFrontAndTheListKeepsTheLimit() {
        val recents = RecentEntries(store)

        (1..10).forEach { recents.note("e$it") }
        recents.note("e5")

        assertEquals(listOf("e5", "e10", "e9", "e8", "e7", "e6", "e4", "e3"), recents.ids.value)
    }

    @Test
    fun theIdsSurviveARestart() {
        RecentEntries(store).apply {
            note("a")
            note("b")
        }

        assertEquals(listOf("b", "a"), RecentEntries(store).ids.value)
    }

    @Test
    fun entriesGoneFromTheirOpenDatabaseAreDroppedALockedDatabaseKeepsItsOwn() {
        val recents = RecentEntries(store)
        listOf(p("a"), w("x"), p("b"), p("c")).forEach(recents::note)

        recents.keep(StoredEntryIds(open = listOf(personal), refs = listOf(EntryRef(personal, "a"), EntryRef(personal, "c"))))

        assertEquals(listOf(p("c"), w("x"), p("a")), recents.ids.value, "Work is not open: its entry stays, hidden")
        assertEquals(listOf(p("c"), w("x"), p("a")), RecentEntries(store).ids.value)
    }

    @Test
    fun idsStoredBeforeSeveralDatabasesBecomeRefsOfTheFirstOpenDatabaseHoldingThem() {
        store.setString("recentEntries", "tv\ngone\nbank")
        val recents = RecentEntries(store)

        recents.keep(StoredEntryIds(listOf(personal, work), listOf(EntryRef(personal, "tv"), EntryRef(personal, "bank"), EntryRef(work, "bank"))))

        assertEquals(listOf(p("tv"), p("bank")), recents.ids.value)
        assertEquals(p("tv") + "\n" + p("bank"), store.string("recentEntries"))
    }

    @Test
    fun eightAreKeptPerDatabaseSoALockedOnesAreNotPushedOut() {
        val recents = RecentEntries(store)
        listOf(w("same"), w("wbank")).forEach(recents::note)
        (0 until 9).forEach { recents.note(p("p$it")) }

        assertEquals((8 downTo 1).map { p("p$it") } + listOf(w("wbank"), w("same")), recents.ids.value)
        assertEquals(10, RecentEntries(store).ids.value.size)
    }

    @Test
    fun aRemovedEntryLeavesTheListAndUndoPutsItBackInItsPlace() {
        val recents = RecentEntries(store)
        listOf("a", "b", "c").forEach(recents::note)

        val undo = assertNotNull(recents.remove("b"))

        assertNull(recents.remove("b"), "not on the list any more: nothing to undo")
        assertEquals(listOf("c", "a"), recents.ids.value)
        assertEquals(listOf("c", "a"), RecentEntries(store).ids.value)
        undo()
        assertEquals(listOf("c", "b", "a"), recents.ids.value)
        assertEquals(listOf("c", "b", "a"), RecentEntries(store).ids.value)
    }

    @Test
    fun undoAfterTheEntryWasUsedAgainChangesNothing() {
        val recents = RecentEntries(store)
        listOf("a", "b", "c").forEach(recents::note)

        val undo = assertNotNull(recents.remove("a"))
        recents.note("a")
        undo()

        assertEquals(listOf("a", "c", "b"), recents.ids.value)
    }

    @Test
    fun undoKeepsTheLimitOfItsDatabase() {
        val recents = RecentEntries(store)
        (1..8).forEach { recents.note(p("e$it")) }
        recents.note(w("x"))

        val undo = assertNotNull(recents.remove(p("e1")))
        recents.note(p("e9"))
        undo()

        assertEquals(listOf(p("e9"), w("x"), p("e8"), p("e7"), p("e6"), p("e5"), p("e4"), p("e3"), p("e2")), recents.ids.value)
    }

    @Test
    fun undoKeepsTheLimit() {
        val recents = RecentEntries(store)
        (1..8).forEach { recents.note("e$it") }

        val undo = assertNotNull(recents.remove("e1"))
        recents.note("e9")
        undo()

        assertEquals(listOf("e9", "e8", "e7", "e6", "e5", "e4", "e3", "e2"), recents.ids.value)
    }
}
