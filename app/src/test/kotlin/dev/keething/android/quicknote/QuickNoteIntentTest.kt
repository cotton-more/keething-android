package dev.keething.android.quicknote

import android.content.Intent
import dev.keething.card.editing.QuickNoteSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * What a share or the selection menu hands over to Keething note (SPEC §6.4; keething-spec app.quick-note AC3,
 * app.quick-note-source AC2, AC4, AC5; the shapes seen in plans/android-notes.md AQ0): the text, the page a browser
 * gives with it, and the app.
 */
class QuickNoteIntentTest {
    private class Read(val text: String, val app: String?, val page: String?)

    private fun read(action: String?, extras: Map<String, CharSequence?>, app: String? = "Chrome"): Read? =
        QuickNoteIntent.read(action, extras::get) { app }?.let { Read(it.text, it.source.app, it.source.page) }

    private fun share(text: CharSequence?, subject: CharSequence? = null): Read? =
        read(Intent.ACTION_SEND, mapOf(Intent.EXTRA_TEXT to text, Intent.EXTRA_SUBJECT to subject))

    private fun selected(text: CharSequence?): Read? = read(Intent.ACTION_PROCESS_TEXT, mapOf(Intent.EXTRA_PROCESS_TEXT to text))

    // The text (app.quick-note AC3)

    @Test
    fun aShareGivesItsTextAndTheSelectionMenuTheSelectedText() {
        assertEquals("Perfect Days (2023)\nWim Wenders", share("Perfect Days (2023)\nWim Wenders")?.text)
        assertEquals("Dune", share(StringBuilder("Dune"))?.text, "a text with spans, as a string")
        assertEquals("  Arrival \n", selected("  Arrival \n")?.text, "as it came: the run trims it")
        assertEquals(
            "Dune",
            read(Intent.ACTION_PROCESS_TEXT, mapOf(Intent.EXTRA_PROCESS_TEXT to "Dune", Intent.EXTRA_TEXT to "other"))?.text,
            "the selection menu's own extra only",
        )
    }

    @Test
    fun noTextHandedOverIsNoneAndTheAppIsNotAsked() {
        var asked = 0
        val nothing = mapOf<String, CharSequence?>(Intent.EXTRA_SUBJECT to "Ten films")
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_PROCESS_TEXT, null, Intent.ACTION_VIEW)) {
            assertNull(QuickNoteIntent.read(action, nothing::get) { asked++; "Chrome" }, "$action")
        }
        assertNull(QuickNoteIntent.read(null, mapOf(Intent.EXTRA_TEXT to "Dune")::get) { asked++; "Chrome" }, "no action")
        assertNull(QuickNoteIntent.read(Intent.ACTION_VIEW, mapOf(Intent.EXTRA_TEXT to "Dune")::get) { asked++; "Chrome" }, "another action")
        assertEquals(0, asked, "the relaunch after an app update (AQ0) tags nothing")
        assertEquals(" \n ", share(" \n ")?.text, "a blank text as it came: the run says there is none")
    }

    // The app (app.quick-note-source AC1, AC4)

    @Test
    fun theAppComesWithBothWaysInAndUnknownIsNone() {
        // spec: app.quick-note-source/AC1
        assertEquals("Chrome", share("Dune")?.app)
        assertEquals("Firefox", read(Intent.ACTION_PROCESS_TEXT, mapOf(Intent.EXTRA_PROCESS_TEXT to "Dune"), app = "Firefox")?.app)
        assertNull(read(Intent.ACTION_SEND, mapOf(Intent.EXTRA_TEXT to "Dune"), app = null)?.app)
    }

    // The page (app.quick-note-source AC2, AC4, AC5)

    @Test
    fun aPageSharedWithItsTitleTheTitleIsTheTextTheAddressTheWebsite() {
        // spec: app.quick-note-source/AC2
        val page = "https://films.example.org/festival-2026"
        val chrome = share(page, "Ten films from the festival")
        assertEquals("Ten films from the festival", chrome?.text, "Chrome: the title as the subject (AQ0)")
        assertEquals(page, chrome?.page)
        assertEquals("Ten films", share("\n $page \n", " Ten films ")?.text, "blank lines and spaces around the address")
        assertEquals(page, share("\n $page \n", " Ten films ")?.page, "the address without them")
        assertEquals("HTTP://films.example.org/", share("HTTP://films.example.org/", "Ten films")?.page, "the scheme in any case")
        assertEquals("http://192.168.1.1/admin", share("http://192.168.1.1/admin", "Router")?.page)
    }

    @Test
    fun aPageSharedWithoutATitleTheAddressIsTheTextAndTheWebsite() {
        // spec: app.quick-note-source/AC2
        val page = "https://films.example.org/festival-2026"
        for (subject in listOf(null, "", "  ")) {
            val firefox = share(page, subject)
            assertEquals(page, firefox?.text, "Firefox: no title; a note needs its text")
            assertEquals(page, firefox?.page)
        }
        for (line in listOf("Share via", "share via ", "Including link: https://films.example.org/")) {
            val selected = share(page, line)
            assertEquals(page, selected?.text, "a selected address shared: the browser's own line is no title ($line)")
            assertEquals(page, selected?.page)
        }
        val app = share("https://youtu.be/dQw4w9WgXcQ?si=abc", null)
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc", app?.page, "any app's lone address, the token in it too (AC5)")
    }

    @Test
    fun chromesLinkToTheHighlightGoesToTheWebsiteAndItsQuotesComeOff() {
        // spec: app.quick-note-source/AC2
        val link = "https://en.wikipedia.org/wiki/Touchscreen#:~:text=is%20commonly-,used,-for%20touch"
        val chrome = share("\"commonly used\"\n $link", "Including link: https://en.wikipedia.org/")
        assertEquals("commonly used", chrome?.text, "the quotes Chrome put around the selection off, its subject not the text")
        assertEquals(link, chrome?.page, "the whole address, the selection's words in its directive too (P5)")

        val lines = share("\"A screen\nthat you touch\"\n $link\n")
        assertEquals("A screen\nthat you touch", lines?.text, "a selection over several lines; a line break after the link")
        assertEquals(link, lines?.page)

        assertEquals("He said \"hi\"", share("\"He said \"hi\"\"\n $link")?.text, "only the outer quotes")
        assertEquals("commonly used", share("commonly used\n $link")?.text, "no quotes: the text as it is")
        assertEquals(link, share("commonly used\n $link")?.page)
        assertEquals("commonly used", share("commonly used\n$link")?.text, "Chromium's shape without its space")
    }

    @Test
    fun anAddressAnywhereElseIsTheTextsAndNothingIsGuessed() {
        // spec: app.quick-note-source/AC4
        val page = "https://films.example.org/festival-2026"
        for (text in listOf(
            "see $page", "$page $page", "Ten films\n $page", "Ten films\n$page", "$page\nTen films", "\"used\"\n $page#top",
        )) {
            val read = share(text, "Ten films")
            assertEquals(text, read?.text, text)
            assertNull(read?.page, text)
        }
        assertEquals("commonly used", share("commonly used", "Share via")?.text, "Firefox's selection: no address, its subject ignored")
        assertNull(share("commonly used", "Share via")?.page)
        assertNull(selected(page)?.page, "the selection menu gives no page, even a selected address")
        assertEquals(page, selected(page)?.text)
    }

    @Test
    fun onlyHttpAndHttpsWithAHost() {
        // spec: app.quick-note-source/AC5
        for (other in listOf("ftp://files.example.org/", "https://", "intent://films.example.org#Intent;end", "content://x/y")) {
            val read = share(other, "Files")
            assertEquals(other, read?.text, other)
            assertNull(read?.page, other)
        }
        assertNull(share("\"used\"\n ftp://files.example.org/#:~:text=used")?.page, "Chrome's shape with another scheme")
        val odd = share("https://exa<mple>.com/", "Odd page")
        assertEquals("Odd page\nhttps://exa<mple>.com/", odd?.text, "no address for the Website: the title kept, over the text")
        assertNull(odd?.page)
        assertEquals("https://exa<mple>.com/", share("https://exa<mple>.com/", "Share via")?.text, "not over the browser's own line")
        assertEquals("https://alex:hunter2@films.example.org/x?token=1", share("https://alex:hunter2@films.example.org/x?token=1")?.page, "kept whole")
    }

    @Test
    fun itSaysNothingOfTheTextWhenPrinted() {
        val given = QuickNoteIntent.read(Intent.ACTION_SEND, mapOf(Intent.EXTRA_TEXT to "Unforgettable")::get) { "Chrome" }
        assertEquals("Given", given.toString())
        assertEquals(QuickNoteSource(app = "Chrome"), given?.source)
    }

    // The clipboard's way in (the tile, the launcher's shortcut)

    @Test
    fun theClipboardsActionGivesTheClipboardAndNothingFromTheIntent() {
        val clipboard = object : dev.keething.android.clipboard.SystemClipboard {
            override fun put(text: String, sensitive: Boolean, token: String) = error("not touched")
            override fun owner() = error("not touched")
            override fun clear() = error("not touched")
            override fun text() = error("not read")
            override fun hasText() = error("not looked at")
        }
        val extras = mapOf<String, CharSequence?>(Intent.EXTRA_TEXT to "Dune", Intent.EXTRA_PROCESS_TEXT to "Dune")
        val source = QuickNoteIntent.source(QuickNoteIntent.ACTION_FROM_CLIPBOARD, extras::get, { error("no app asked: the clipboard does not say") }) { clipboard }
        assertSame(clipboard, assertIs<QuickNoteRun.Source.Clipboard>(source).clipboard)
        val shared = QuickNoteIntent.source(Intent.ACTION_SEND, extras::get, { "Chrome" }) { error("the clipboard not asked for a share") }
        assertEquals("Dune", assertIs<QuickNoteRun.Source.Given>(shared).text)
        assertNull(QuickNoteIntent.source(null, extras::get, { "Chrome" }) { error("not asked") })
    }
}
