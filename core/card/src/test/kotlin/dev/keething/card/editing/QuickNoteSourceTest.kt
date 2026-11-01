package dev.keething.card.editing

import dev.keething.card.now
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.EntryTypeChange
import dev.keething.domain.vault.NoteDates
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a quick note came from (SPEC §6.4; keething-spec app.quick-note-source; the Mac's `QuickNoteSourceTests`, the
 * browser parts left out): the app as a tag, the page in the Website, in the editor before Save.
 */
class QuickNoteSourceTest {
    private val root = VaultGroup("R", "Root", emptyList())
    private val inbox = VaultGroup("I", "Inbox", listOf("Inbox"))
    private val dates = NoteDates { "<${it.epochSecond}>" }

    private fun quickNote(text: String, source: QuickNoteSource, knownTags: List<String> = emptyList()): Pair<EntryEditor, EntryDraft> {
        val editor = EntryEditor.createQuickNote(Vault(entries = emptyList(), groups = listOf(root, inbox), defaultUsername = "alex"), now, dates, id = "new")
        return editor to source.applied(editor.start.copy(notes = text), knownTags)
    }

    // The rules (AC1, AC5)

    @Test
    fun anAddressOnlyWhenHttpOrHttpsWithAHostOuterSpacesTrimmed() {
        // spec: app.quick-note-source/AC5
        assertEquals("https://example.com/a?b=1#c", QuickNoteSource.address("https://example.com/a?b=1#c"))
        assertEquals("http://example.com/", QuickNoteSource.address("  http://example.com/\n"))
        assertEquals("HTTPS://Example.com", QuickNoteSource.address("HTTPS://Example.com"))
        assertEquals("http://192.168.1.1:8080/admin", QuickNoteSource.address("http://192.168.1.1:8080/admin"))
        assertEquals("http://[::1]/", QuickNoteSource.address("http://[::1]/"))
        for (other in listOf(
            "file:///Users/alex/a.txt", "chrome://settings", "about:blank", "javascript:alert(1)", "data:text/plain,x",
            "content://com.android.chrome.FileProvider/x", "intent://example.com#Intent;end", "example.com", "", "   ",
            "https://", "https:///path", "https://alex@/x", "https://:8080/", "http:example.com", "https://example.com/a b",
            "see https://example.com/", "http://[x", "http://[x]/", "https://exa<mple>.com", "https://a.com,https://b.com",
            "https://a\"b.com/", "https://exam{ple}.com/", "http://[::1]evil/", "https://example.com:abc/", "http://.../",
            "http://-/", "http://%/",
        )) {
            assertNull(QuickNoteSource.address(other), other)
        }
        assertNull(QuickNoteSource.address(null))
    }

    @Test
    fun noAddressWithAControlOrInvisibleFormatCharacterOrAHostOfNoLetterOrDigit() {
        // spec: app.quick-note-source/AC5
        for (other in listOf(
            "https://example.com/a\u0000b", "https://example.com/?q=\u0008", "https://example.com/\u007F",
            "https://exa\u0001mple.com/", "https://example.com/\u0085", "https://→/", "https://→.★/", "https://\u200B/",
            "https://example.com/\u202Efdp.exe", "https://example.com/a\u2066b", "https://exa\u200Bmple.com/",
            "https://example.com/\u200F", "https://example.com/\uFEFF",
        )) {
            assertNull(QuickNoteSource.address(other), other)
        }
    }

    @Test
    fun anAddressIsKeptWholeAUserPasswordATokenAndChromesLinkToTheHighlightToo() {
        // spec: app.quick-note-source/AC5
        for (kept in listOf(
            "https://example.com/reset?token=abc123&lang=de", "https://example.com/cb#access_token=abc&token_type=bearer",
            "https://b.s3.amazonaws.com/f.pdf?X-Amz-Credential=AK%2F20&X-Amz-Signature=beef", "https://example.com/s?q=caf%C3%A9#top",
            "https://alex:hunter2@example.com/x?token=1", "https://alex@example.com/x",
            "https://en.wikipedia.org/wiki/Touchscreen#:~:text=is%20commonly-,used,-for%20touch",
            "https://例え.jp/パス", "https://उदाहरण.परीक्षा/", "https://ตัวอย่าง.ไทย/", "https://example.com./", "https://example.com:/",
            "https://u:p@ss@example.com/", "HTTPS://WWW.EXAMPLE.COM/", "https://\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645.ir/", "https://my_host.local:8443/", "http://[2001:db8::1]:8080/x", "https://xn--e1afmkfd.xn--p1ai/",
        )) {
            assertEquals(kept, QuickNoteSource.address(kept))
        }
    }

    @Test
    fun theAppsTagAsTheDatabaseSpellsItASeparatorInItsNameASpace() {
        // spec: app.quick-note-source/AC1
        assertEquals("chrome", QuickNoteSource.tag("Chrome", listOf("work", "chrome")))
        assertEquals("Gmail", QuickNoteSource.tag("Gmail", listOf("work")))
        assertEquals("Firefox", QuickNoteSource.tag("  Firefox ", emptyList()))
        assertEquals("Café", QuickNoteSource.tag("Cafe", listOf("Café")), "compared as tags are: accents aside")
        assertEquals("Notes Drafts Two", QuickNoteSource.tag("Notes; Drafts, Two", emptyList()))
        assertNull(QuickNoteSource.tag(" ; ", emptyList()))
        assertNull(QuickNoteSource.tag("", emptyList()))
    }

    @Test
    fun itSaysNothingOfTheAppOrThePageWhenPrinted() {
        val printed = QuickNoteSource("Chrome", "https://example.com/secret").toString()
        assertFalse("Chrome" in printed || "example" in printed, printed)
    }

    // The editor (AC1–AC4)

    @Test
    fun theAppAsATagAndThePageInTheWebsiteWithItsRowBothInTheChanges() {
        // spec: app.quick-note-source/AC2
        val (editor, draft) = quickNote("Dune", QuickNoteSource("Chrome", "https://example.com/dune"), knownTags = listOf("chrome"))
        assertEquals(listOf("chrome"), draft.tags, "the database's spelling")
        assertEquals("https://example.com/dune", draft.url)
        assertTrue(editor.showsWebsite(draft))
        assertTrue(editor.showsWebsite(draft.copy(url = "")), "the row stays while its address is taken out")
        assertEquals(
            EntryChanges(
                title = "Note, <${now.epochSecond}>", url = "https://example.com/dune", notes = "Dune", notesProtected = true,
                entryType = EntryTypeChange.Set(EntryType.NOTE), tags = listOf("chrome"),
            ),
            editor.changes(draft),
        )
    }

    @Test
    fun eitherCanBeTakenOutBeforeSaveLikeAnyTagOrWebsite() {
        // spec: app.quick-note-source/AC3
        val (editor, draft) = quickNote("Dune", QuickNoteSource("Chrome", "https://example.com/dune"))
        val changes = editor.changes(draft.removeTag("Chrome").copy(url = ""))
        assertNull(changes.tags, "no tag saved")
        assertNull(changes.url, "no Website saved")
        assertEquals("Dune", changes.notes)
    }

    @Test
    fun unknownNothingStandsForIt() {
        // spec: app.quick-note-source/AC4
        val (editor, draft) = quickNote("Dune", QuickNoteSource())
        assertEquals(emptyList(), draft.tags)
        assertEquals("", draft.url)
        assertFalse(editor.showsWebsite(draft), "no Website row for nothing")
        val appOnly = quickNote("Dune", QuickNoteSource(app = "Gmail")).second
        assertEquals(listOf("Gmail"), appOnly.tags)
        assertEquals("", appOnly.url)
        assertEquals(emptyList(), quickNote("Dune", QuickNoteSource(app = " , ")).second.tags, "a name with nothing left")
    }

    @Test
    fun theTagIsAddedOnceAndATypedWebsiteIsKept() {
        val draft = EntryDraft(notes = "Dune", tags = listOf("CHROME"), url = "https://mine.example")
        val applied = QuickNoteSource("Chrome", "https://example.com/dune").applied(draft, listOf("chrome"))
        assertEquals(listOf("CHROME"), applied.tags)
        assertEquals("https://mine.example", applied.url)
    }
}
