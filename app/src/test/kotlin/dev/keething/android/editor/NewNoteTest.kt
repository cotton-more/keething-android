package dev.keething.android.editor

import dev.keething.android.clipboard.ClipOwner
import dev.keething.android.clipboard.SystemClipboard
import dev.keething.android.list.NewChoice
import dev.keething.android.navigation.NavigationState
import dev.keething.android.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * New note and New note from clipboard (SPEC §6.4; keething-spec types.note AC4, app.quick-note AC6, AC7; the Mac's
 * `NoteEditorTests`): the `+` menu's three, the clipboard read once and left as it was, its text only in the editor's
 * screen.
 */
class NewNoteTest {
    private class FakeSystemClipboard(var text: String?) : SystemClipboard {
        var writes = 0

        override fun put(text: String, sensitive: Boolean, token: String) {
            writes++
        }

        override fun owner(): ClipOwner = ClipOwner.Other

        override fun clear() {
            writes++
        }

        override fun text(): String? = text
    }

    @Test
    fun thePlusMenuOffersANewEntryANoteAndANoteFromTheClipboard() {
        assertEquals(listOf("New entry", "New note", "New note from clipboard"), NewChoice.entries.map { it.title })
        assertEquals("Opens it with the copied text", NewChoice.NOTE_FROM_CLIPBOARD.subtitle)
    }

    @Test
    fun theClipboardsTextWithOuterBlankLinesTrimmedTheClipboardLeftAsItWas() {
        val clipboard = FakeSystemClipboard("\n  \n  Dune\n\nArrival  \n\n")
        assertEquals("  Dune\n\nArrival  ", NoteFromClipboard.read(clipboard))
        assertEquals(0, clipboard.writes, "the clipboard is not written nor cleared")
        assertEquals("\n  \n  Dune\n\nArrival  \n\n", clipboard.text)
    }

    @Test
    fun noTextOrABlankOneIsNone() {
        assertNull(NoteFromClipboard.read(FakeSystemClipboard(null)), "nothing, or not text (a picture), or the app not in front")
        assertNull(NoteFromClipboard.read(FakeSystemClipboard(" \n\t\n")), "blank is no text")
        assertEquals("No text on the clipboard", NoteFromClipboard.NO_TEXT)
    }

    @Test
    fun theTextGoesWithTheEditorsScreenNeverIntoItsName() {
        val start = NewNote("Bank PIN 4711")
        val screen = Screen.Editor(entryId = null, database = "P", note = start)
        assertFalse("4711" in screen.toString(), "a screen's name is a key of the saved state")
        assertFalse(screen == Screen.Editor(entryId = null, database = "P", note = NewNote("Bank PIN 4711")), "two notes are two editors")
        assertEquals("", NewNote.EMPTY.text)

        val navigation = NavigationState()
        navigation.open(screen)
        navigation.lockScreen(1, anyOpen = false)
        assertTrue(navigation.backStack.none { it is Screen.Editor }, "a lock takes the editor and the text with it")
    }
}
