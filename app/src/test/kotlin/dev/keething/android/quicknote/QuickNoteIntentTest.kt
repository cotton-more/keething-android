package dev.keething.android.quicknote

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a share or the selection menu hands over to Keething note (SPEC §6.4; keething-spec app.quick-note AC3; the
 * shapes seen in plans/android-notes.md AQ0).
 */
class QuickNoteIntentTest {
    private fun share(text: CharSequence?, subject: CharSequence? = null): String? =
        QuickNoteIntent.text(Intent.ACTION_SEND, mapOf(Intent.EXTRA_TEXT to text, Intent.EXTRA_SUBJECT to subject)::get)

    @Test
    fun aShareGivesItsTextAndTheSelectionMenuTheSelectedText() {
        assertEquals("Perfect Days (2023)\nWim Wenders", share("Perfect Days (2023)\nWim Wenders"))
        assertEquals("Dune", share(StringBuilder("Dune")), "a text with spans, as a string")
        assertEquals("  Arrival \n", QuickNoteIntent.text(Intent.ACTION_PROCESS_TEXT, mapOf(Intent.EXTRA_PROCESS_TEXT to "  Arrival \n")::get), "as it came: the run trims it")
        assertEquals(
            "Dune",
            QuickNoteIntent.text(Intent.ACTION_PROCESS_TEXT, mapOf(Intent.EXTRA_PROCESS_TEXT to "Dune", Intent.EXTRA_TEXT to "other")::get),
            "the selection menu's own extra only",
        )
    }

    @Test
    fun noTextHandedOverIsNone() {
        assertNull(share(null), "a share without a text (the relaunch after an app update, AQ0)")
        assertNull(QuickNoteIntent.text(Intent.ACTION_PROCESS_TEXT, emptyMap<String, CharSequence>()::get))
        assertNull(QuickNoteIntent.text(null, mapOf(Intent.EXTRA_TEXT to "Dune")::get), "no action")
        assertNull(QuickNoteIntent.text(Intent.ACTION_VIEW, mapOf(Intent.EXTRA_TEXT to "Dune")::get), "another action")
        assertEquals(" \n ", share(" \n "), "a blank text as it came: the run says there is none")
    }

    @Test
    fun aSubjectIsTheFirstLineOnlyOverALoneHttpAddress() {
        val page = "https://films.example.org/festival-2026"
        assertEquals("Ten films from the festival\n$page", share(page, "Ten films from the festival"), "a page shared from Chrome")
        assertEquals("Ten films\n\n $page \n", share("\n $page \n", " Ten films "), "the address with blank lines and spaces around it is still alone")
        assertEquals("Ten films\nHTTP://films.example.org/", share("HTTP://films.example.org/", "Ten films"), "the scheme in any case")
        assertEquals("Router\nhttp://192.168.1.1/admin", share("http://192.168.1.1/admin", "Router"))

        val selection = "\"commonly used\"\n https://en.wikipedia.org/wiki/Touchscreen#:~:text=is%20commonly-,used,-for%20touch"
        assertEquals(selection, share(selection, "Including link: https://en.wikipedia.org/"), "Chrome's line over a selection is not a title")
        assertEquals("commonly used", share("commonly used", "Share via"), "Firefox's")
        assertEquals("ftp://files.example.org/", share("ftp://files.example.org/", "Files"), "only http and https")
        assertEquals("$page $page", share("$page $page", "Two"), "two addresses are not one alone")
        assertEquals("see $page", share("see $page", "Ten films"), "an address in a sentence")
        assertEquals(page, share(page, "  "), "a blank subject")
        assertEquals(page, share(page), "no subject")
        assertEquals("https://", share("https://", "Nothing"), "a scheme without an address")
    }
}
