package dev.keething.search

import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryType
import kotlin.test.Test
import kotlin.test.assertEquals

/** A protected text is not searched, its title and tags are (keething-spec types.note AC9; the Mac's `NoteCardTests`). */
class NoteSearchTest {
    private val secretWord = "Zanzibar"
    private val note = EntryType.NOTE.stored
    private val entries = listOf(
        Entry("p", title = "Note", notes = "$secretWord trip", notesProtected = true, tags = listOf("travel"), entryType = note),
        Entry("u", title = "Films", notes = "Arrival by Villeneuve", entryType = note),
        Entry("k", title = "Bank", username = "me", notes = "PIN $secretWord", notesProtected = true),
    )

    private fun hits(query: String) = EntrySearch.filter(EntrySearch.index(entries, englishCollator), query).map { it.id }

    // spec: types.note/AC9
    @Test
    fun aProtectedTextIsNotSearchedItsTitleAndTagsAreAnUnprotectedTextIs() {
        assertEquals(emptyList(), hits(secretWord), "nor any entry's protected Notes")
        assertEquals(emptyList(), hits(secretWord.lowercase()))
        assertEquals(emptyList(), hits("trip"))
        assertEquals(listOf("p"), hits("note"))
        assertEquals(listOf("p"), hits("travel"))
        assertEquals(listOf("u"), hits("villeneuve"))
    }
}
