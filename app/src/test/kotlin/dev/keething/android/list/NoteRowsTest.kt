package dev.keething.android.list

import dev.keething.android.ui.english
import dev.keething.card.EntryFace
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.Vault
import java.text.Collator
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** A note's row in the list: the note icon, its own line, nothing of a protected text (keething-spec types.note AC7). */
class NoteRowsTest {
    private val secretWord = "Zanzibar"
    private val note = EntryType.NOTE.stored

    private val entries = listOf(
        Entry(
            "c", title = "Visa", entryType = note,
            customFields = listOf(CustomField.plain("Card number", "4111111111111111")), fieldTypes = mapOf("Card number" to "card.number"),
        ),
        Entry("u", title = "Login", username = "octocat", url = "https://example.com", notes = "Films", entryType = note),
        Entry("p", title = "Protected", notes = "$secretWord and more", notesProtected = true, entryType = note),
        Entry("i", title = "Imported", notes = "Dune\nArrival"),
        Entry("o", title = "Ordinary", username = "octocat", notes = "Films"),
    )
    private val listing = VaultListing(Vault(entries = entries), Instant.parse("2026-11-01T08:00:00Z"), Collator.getInstance(Locale.ENGLISH), unnamedCode = UNNAMED_CODE)

    private fun row(id: String) = listing.sections("", recentIds = emptyList()).flatMap { it.rows }.single { it.id == id }

    // spec: types.note/AC7
    @Test
    fun aNoteShowsTheNoteIconAndItsOwnLineNeverTheUsernameOrHost() {
        assertIs<EntryFace.Note>(row("c").face, "the note's icon wins over the card's face")
        assertEquals("", row("c").subtitle.english())
        assertEquals("Films", row("u").subtitle.english(), "not octocat · example.com")
        assertEquals("Protected note", row("p").subtitle.english())
        assertEquals("Dune", row("i").subtitle.english())
        assertIs<EntryFace.Note>(row("i").face, "notes only: a note at once")
        assertEquals("octocat", row("o").subtitle.english())
        assertEquals(null, row("o").face)
    }

    // spec: types.note/AC7
    @Test
    fun aProtectedNotesRowHoldsNothingOfItsText() {
        assertFalse(row("p").toString().contains(secretWord))
        assertFalse(row("p").toString().contains("more"))
    }
}
