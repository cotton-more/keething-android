package dev.keething.card

import dev.keething.domain.text.english
import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.NoteTitle
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A note on its card and in the list (keething-spec types.note AC3, AC6, AC7; the Mac's `NoteCardTests`). */
class NoteCardTest {
    private val note = EntryType.NOTE.stored
    private val secretWord = "Zanzibar"
    private val cardTypes = "Card number = card.number\nCVC = card.cvc"
    private val cardFields = listOf(field("Card number", "4111111111111111"), field("CVC", "123", protected = true), field("Hotline", "+49 30 1234"))

    private fun build(entry: Entry) = EntryCardBuilder.build(entry, now, Locale.ENGLISH)

    // The card (AC3, AC6)

    @Test
    fun theTextComesFirstThenWhatWasAddedAndNotesAreNotRepeated() {
        val entry = typed(Entry("n", title = "Films", notes = "Dune\nArrival", notesProtected = true, customFields = cardFields, entryType = note), cardTypes)
        val card = build(entry)

        assertEquals(listOf("note", "payment-card", "section-2"), card.sections.map { it.id })
        assertTrue(card.isNote)
        val text = card.sections.first().rows.single()
        assertEquals(FieldRow.Kind.NOTE, text.kind)
        assertEquals(RowIds.NOTE, text.id)
        assertEquals("Note", text.label.english())
        assertEquals("Dune\nArrival", text.storedText())
        assertTrue(text.isConcealed, "a protected text is hidden until shown")
        assertFalse(text.isProtected, "a string with a flag, not a secret (decision P4)")
        assertTrue(card.rows.none { it.kind == FieldRow.Kind.NOTES || it.kind == FieldRow.Kind.USERNAME || it.kind == FieldRow.Kind.PASSWORD })
        assertNotNull(card.paymentCard, "the kind's face stays, under the text")
    }

    @Test
    fun aSiteAddedToAMarkedNoteShowsUnderTheTextAnUnprotectedTextIsNotHidden() {
        val card = build(Entry("n", title = "Shop", url = "https://shop.example", notes = "socks", entryType = note))

        assertEquals(listOf("note", "credentials"), card.sections.map { it.id })
        assertEquals(listOf(FieldRow.Kind.URL), card.sections[1].rows.map { it.kind })
        assertFalse(card.rows[0].isConcealed)

        val withPassword = build(Entry("b", title = "Book club", password = Secret.of("door 4711"), notes = "Tuesdays", entryType = note))
        assertEquals(listOf("note", "credentials"), withPassword.sections.map { it.id }, "a password from KeePassXC shows and is kept")
        assertEquals(listOf(FieldRow.Kind.PASSWORD), withPassword.sections[1].rows.map { it.kind })
    }

    @Test
    fun anEntryReadAsANoteByItsFieldsAloneStopsBeingOneOnceItHasAUsername() {
        assertTrue(build(Entry("i", title = "Imported", notes = "Dune\nArrival")).isNote)
        val ordinary = build(Entry("o", title = "Mail", username = "me", notes = "pin 1234", notesProtected = true))
        assertFalse(ordinary.isNote)
        assertEquals(listOf("credentials", "notes"), ordinary.sections.map { it.id }, "an ordinary entry's Notes where they were")
    }

    // spec: types.note/AC6
    @Test
    fun anOrdinaryEntrysProtectedNotesAreConcealedUnprotectedOnesAreNot() {
        val protected = build(Entry("o", title = "Bank", username = "me", notes = "PIN 4711\nTAN list", notesProtected = true))
            .rows.single { it.kind == FieldRow.Kind.NOTES }
        assertTrue(protected.isConcealed, "protected in the file, concealed on the card")
        assertEquals("PIN 4711\nTAN list", protected.storedText())

        val plain = build(Entry("p", title = "Shop", username = "me", notes = "size 42")).rows.single { it.kind == FieldRow.Kind.NOTES }
        assertFalse(plain.isConcealed, "unprotected Notes as before")
    }

    // spec: types.note/AC6
    @Test
    fun copyGivesTheWholeTextWhileItIsStillHidden() {
        val text = "Dune\n\n  Arrival  \n"
        val row = build(Entry("n", title = "Films", notes = text, notesProtected = true, entryType = note)).rows.first()

        assertTrue(row.isCopyable)
        assertEquals(text, row.clipboardText())
        assertEquals("Note", row.noticeLabel.english())
    }

    // spec: types.note/AC6
    @Test
    fun aSelectedPartOfAShownTextIsCopiedThroughTheAppNothingWhileHidden() {
        val card = build(Entry("n", title = "Note", notes = "From the feed:\nPast Lives, Greta Lee", notesProtected = true, entryType = note))
        val hidden = RevealState()
        val shown = hidden.toggle(RowIds.NOTE)

        assertNull(card.selectionToCopy(RowIds.NOTE, "Past Lives", hidden), "hidden: nothing can be selected, nothing is copied")
        assertEquals(NoteSelection("Greta Lee", sensitive = true), card.selectionToCopy(RowIds.NOTE, "Greta Lee", shown))
        assertNull(card.selectionToCopy(RowIds.NOTE, "not in the text", shown), "only a part of the text")
        assertNull(card.selectionToCopy(RowIds.NOTE, "", shown))

        val plain = build(Entry("p", title = "Films", notes = "Dune\nArrival", entryType = note))
        assertEquals(NoteSelection("Arrival", sensitive = false), plain.selectionToCopy(RowIds.NOTE, "Arrival", RevealState()), "an unprotected note's: through the app, not concealed")

        val bank = build(Entry("o", title = "Bank", username = "me", notes = "PIN 4711\nTAN list", notesProtected = true))
        assertNull(bank.selectionToCopy(RowIds.NOTES, "4711", RevealState()))
        assertEquals(NoteSelection("4711", sensitive = true), bank.selectionToCopy(RowIds.NOTES, "4711", RevealState().toggle(RowIds.NOTES)))
        val shop = build(Entry("s", title = "Shop", username = "me", notes = "size 42"))
        assertNull(shop.selectionToCopy(RowIds.NOTES, "42", RevealState()), "unprotected Notes keep the system's selection")
        assertNull(bank.selectionToCopy(RowIds.USERNAME, "me", RevealState()), "only a text")
    }

    // The list (AC7)

    // spec: types.note/AC7
    @Test
    fun theLineIsTheFirstLineNotBlankAndNotTheTitleNothingWhenThereIsNone() {
        fun line(title: String, notes: String) = NoteLine.of(Entry("n", title = title, notes = notes))?.english()

        assertEquals("Dune", line("Films", "\n  \n  Dune  \nArrival"))
        assertEquals("Dune", line("Films", " Films \n\nDune"), "the title as the first line is skipped")
        assertNull(line("Films", "Films"))
        assertNull(line("Films", "  \n "))
        val long = "a".repeat(70)
        assertEquals("next", line(NoteTitle.make(long, protected = false), "$long\nnext"), "a title made from the first line counts as that line")
        assertEquals("Arrival", line("Films", "Dune\r\nArrival".replace("Dune", "Films")), "\\r\\n is one line break")
        assertEquals(NoteLine.PROTECTED, NoteLine.of(Entry("n", title = "Note", notes = secretWord, notesProtected = true)))
    }

    // spec: types.note/AC7
    @Test
    fun aNoteHasTheNoteFaceInPlaceOfAKindsFaceItsLineNeverTheUsernameOrHost() {
        val visa = typed(Entry("c", title = "Visa", customFields = cardFields, entryType = note), cardTypes)
        assertEquals(EntryFace.Note(line = null, protected = false), EntryFace.of(visa), "the note's icon wins over the card's face")
        assertEquals("", EntryFace.of(visa)!!.subtitle.english())

        val login = Entry("u", title = "Login", username = "octocat", url = "https://example.com", notes = "Films", entryType = note)
        assertEquals("Films", EntryFace.of(login)!!.subtitle.english(), "not octocat")

        val protected = EntryFace.of(Entry("p", title = "Note", notes = "$secretWord and more", notesProtected = true, entryType = note))
        assertEquals(EntryFace.Note(line = NoteLine.PROTECTED, protected = true), protected)
        assertFalse(protected.toString().contains(secretWord), "nothing of the text in the face")

        assertEquals("Dune", EntryFace.of(Entry("i", title = "Imported", notes = "Dune\nArrival"))!!.subtitle.english())
        assertNull(EntryFace.of(Entry("o", title = "Ordinary", username = "octocat", notes = "Films")))
    }
}
