package dev.keething.android.list

import dev.keething.android.settings.InMemorySettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RecentEntriesTest {
    private val store = InMemorySettingsStore()

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
    fun entriesGoneFromTheVaultAreDropped() {
        val recents = RecentEntries(store)
        listOf("a", "b", "c").forEach(recents::note)

        recents.keepOnly(setOf("a", "c", "x"))

        assertEquals(listOf("c", "a"), recents.ids.value)
        assertEquals(listOf("c", "a"), RecentEntries(store).ids.value)
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
    fun undoKeepsTheLimit() {
        val recents = RecentEntries(store)
        (1..8).forEach { recents.note("e$it") }

        val undo = assertNotNull(recents.remove("e1"))
        recents.note("e9")
        undo()

        assertEquals(listOf("e9", "e8", "e7", "e6", "e5", "e4", "e3", "e2"), recents.ids.value)
    }
}
