package dev.keething.card.history

import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A note's history: its text masked when either version is protected; protection and the kind as lines (the Mac's `NoteCardTests`). */
class NoteHistoryTest {
    private val secretWord = "Zanzibar"
    private val old = Entry("n", title = "Films", notes = "Dune")
    private val marked = old.copy(entryType = EntryType.NOTE.stored, notesProtected = true)

    @Test
    fun theMarkAndProtectionAloneAreLinesNothingOfTheTextShown() {
        val changes = EntryVersionDiff.changes(old, marked)

        assertEquals(listOf("Notes", "Kind"), changes.map { it.field })
        assertEquals("not protected" to "protected", changes[0].old to changes[0].new)
        assertFalse(changes[0].isProtected)
        assertEquals(VersionChange.Kind.ADDED, changes[1].kind)
        assertEquals("note", changes[1].new)
    }

    @Test
    fun theTextIsMaskedWhenEitherVersionIsProtected() {
        val edited = marked.copy(notes = secretWord, notesProtected = false)
        val changes = EntryVersionDiff.changes(marked, edited)

        assertEquals(listOf("Notes"), changes.map { it.field }, "the text changed: no protection line")
        assertTrue(changes[0].isProtected, "protected before: masked")
        assertTrue(EntryVersionDiff.changes(edited, marked).single().isProtected, "protected after: masked")
        assertFalse(EntryVersionDiff.changes(old, old.copy(notes = "Arrival")).single().isProtected)
    }

    @Test
    fun theMarkTakenOffIsARemovedLine() {
        val unmarked = marked.copy(entryType = null)

        assertEquals(listOf(VersionChange.Kind.REMOVED), EntryVersionDiff.changes(marked, unmarked).map { it.kind })
        assertEquals(emptyList(), EntryVersionDiff.changes(marked, marked.copy(notes = "")).filter { it.field == "Notes" && !it.isProtected })
    }
}
