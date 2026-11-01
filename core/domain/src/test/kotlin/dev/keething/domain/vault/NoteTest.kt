package dev.keething.domain.vault

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a note is (keething-spec types.note AC1–AC3, AC5; the Mac `NoteTests` and `NoteEditorTests` domain
 * expectations): its kind, the rule for entries without one, the title it is given, its text as received.
 */
class NoteTest {
    // The kind (AC1)

    @Test
    fun theKindIsReadIgnoringCaseAndOuterSpacesAndOneThisVersionDoesNotKnowIsNoNote() {
        assertEquals(EntryType.NOTE, EntryType.of(" NOTE "))
        assertEquals(EntryType.NOTE, EntryType.of("\tnote\n"))
        assertNull(EntryType.of("notes"))
        assertNull(EntryType.of("checklist"))
        assertEquals("Keething.EntryType", EntryType.CUSTOM_DATA_KEY)
        assertEquals("note", EntryType.NOTE.stored)

        assertTrue(Entry("a", entryType = " NOTE ").isNote, "a stored note, though its Notes are empty")
        assertFalse(
            Entry("b", notes = "Dune, Arrival", entryType = "checklist").isNote,
            "a kind it does not know leaves the entry ordinary, though its fields alone would make a note",
        )
        assertTrue(Entry("c", password = Secret.of("p"), entryType = "Note").isNote)
    }

    // The rule (AC2) and staying a note (AC3)

    @Test
    fun notesWithABlankUsernamePasswordAndUrlMakeANoteOneCharacterOfAnyOfThemDoesNot() {
        val base = Entry("e", title = "Films", username = "  ", password = Secret.of(" "), url = "\t", notes = "Dune\nArrival")
        assertTrue(base.isNote, "white space only still makes a note")
        assertFalse(base.copy(username = "x").isNote)
        assertFalse(base.copy(password = Secret.of("x")).isNote)
        assertFalse(base.copy(url = "x").isNote)
        assertTrue(base.copy(password = Secret.of("　 ")).isNote, "a password of other spaces is blank too")
        assertFalse(base.copy(password = Secret.of("пароль")).isNote, "one not in ASCII is a password")
        assertFalse(base.copy(notes = " \n ").isNote, "blank Notes make no note")
        assertTrue(base.copy(attachments = listOf(Attachment("a.png", 1))).isNote, "an attachment is allowed")
        assertFalse(base.copy(customFields = listOf(CustomField.plain("Seen on", "feed"))).isNote, "a custom field is not")
        assertFalse(base.copy(totp = TotpConfig(Secret.of("12345678901234567890"))).isNote, "a one-time code is not")
        assertFalse(base.copy(hasCodeFields = true).isNote, "nor a code's field that makes no code")
    }

    @Test
    fun aMarkedNoteStaysOneWhateverIsAddedOneByTheRuleAloneStopsBeingOne() {
        val marked = Entry(
            "m", notes = "", url = "https://example.org", password = Secret.of("p"), entryType = "note",
            customFields = listOf(CustomField.plain("Card", "4111")), totp = TotpConfig(Secret.of("12345678901234567890")),
        )
        assertTrue(marked.isNote, "even with its text gone")
        val plain = Entry("p", notes = "n")
        assertTrue(plain.isNote)
        assertFalse(plain.copy(url = "https://example.org").isNote)
    }

    // The title (AC5)

    private val made: Instant = Instant.parse("2026-10-31T06:45:00Z")

    /** The phone's formatter is Android's (ICU's `yMMMdjmm` in the phone's region); here one that says what it got. */
    private val dates = NoteDates { "<$it>" }

    @Test
    fun theTitleIsTheFirstLineThatIsNotBlankTrimmed60CharactersThenAnEllipsis() {
        assertEquals("Names from the feed", NoteTitle.make("\n  \n  Names from the feed  \nDune", protected = false))
        assertEquals("one", NoteTitle.make("one\r\ntwo", protected = false))
        assertEquals("one", NoteTitle.make(" one two", protected = false), "Unicode's line separators too")
        val sixty = "a".repeat(60)
        assertEquals(sixty, NoteTitle.make(sixty, protected = false), "60 fit")
        assertEquals("$sixty…", NoteTitle.make(sixty + "b", protected = false))
        assertEquals("映画".repeat(30) + "…", NoteTitle.make("映画".repeat(31), protected = false))
        val family = "👨‍👩‍👧‍👦"
        assertEquals(
            "x".repeat(59) + family + "…", NoteTitle.make("x".repeat(59) + family + "tail", protected = false),
            "an emoji at character 60 is one character, never cut in two",
        )
        assertEquals(
            "é" + "z".repeat(59) + "…", NoteTitle.make("é" + "z".repeat(60), protected = false),
            "an accent with its letter",
        )
    }

    @Test
    fun aProtectedTextOrOneWithNoLineToTakeIsNoteAndWhenItWasMade() {
        assertEquals("Note", NoteTitle.make("Dune", protected = true))
        assertEquals("Note", NoteTitle.make(" \n\t\n", protected = false), "nothing to take")
        assertEquals("Note, <$made>", NoteTitle.make("Dune", protected = true, made = made, dates = dates))
        assertEquals("Note, <$made>", NoteTitle.make(" \n", protected = false, made = made, dates = dates), "nothing to take: dated too")
        assertEquals("Dune", NoteTitle.make("Dune", protected = false, made = made, dates = dates), "an unprotected line is the title, no date")
        assertEquals("Note", NoteTitle.make("Dune", protected = true, made = made), "no formatter, no date")
        assertEquals("Note", NoteTitle.make("Dune", protected = true, dates = dates), "no time, no date")
    }

    // The text as received (the clipboard, a quick note)

    @Test
    fun outerBlankLinesAreTrimmedLineBreaksAndTheFirstLinesIndentKept() {
        assertEquals("A\r\n\r\nB ", NoteText.trimmingOuterBlankLines("\r\n \r\nA\r\n\r\nB \r\n  \r\n"))
        assertEquals("  x", NoteText.trimmingOuterBlankLines("  x"))
        assertEquals("", NoteText.trimmingOuterBlankLines(" \n\t"))
        assertEquals("", NoteText.trimmingOuterBlankLines(""))
        assertEquals("a b", NoteText.trimmingOuterBlankLines("   a b 　"))
    }
}
