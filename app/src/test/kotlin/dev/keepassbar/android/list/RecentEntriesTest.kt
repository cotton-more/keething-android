package dev.keepassbar.android.list

import dev.keepassbar.android.settings.InMemorySettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
