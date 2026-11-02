package dev.keething.android.quicknote

import dev.keething.android.clipboard.ClipOwner
import dev.keething.android.clipboard.SystemClipboard
import dev.keething.android.quicknote.QuickNoteRun.Source
import dev.keething.android.quicknote.QuickNoteRun.State
import dev.keething.android.settings.AppSettings
import dev.keething.android.settings.InMemorySettingsStore
import dev.keething.card.editing.QuickNoteSource
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.databases.Databases
import dev.keething.vault.databases.QuickNoteTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A quick note's run (SPEC §6.4; keething-spec app.quick-note AC3, AC4, AC7; the Mac's `QuickNoteTests`, "The run"):
 * the editor opened with the text, after the unlock when the database is locked; the clipboard read only then; given
 * up, nothing opens and nothing is kept.
 */
class QuickNoteRunTest {
    /** A clipboard that counts how often its text is read (its description is looked at by `hasText`). */
    private class CountingClipboard : SystemClipboard {
        var text: String? = null
        var reads = 0
        var writes = 0

        override fun put(text: String, sensitive: Boolean, token: String) {
            writes++
        }

        override fun owner(): ClipOwner = ClipOwner.Other

        override fun clear() {
            writes++
        }

        override fun text(): String? {
            reads++
            return text
        }

        override fun hasText(): Boolean = text != null
    }

    private class Fixture(var target: DatabaseId? = DatabaseId("P"), var open: Boolean = true) {
        val clipboard = CountingClipboard()
        val opened = mutableListOf<Pair<DatabaseId, String>>()
        val sources = mutableListOf<QuickNoteSource>()
        val run = QuickNoteRun(target = { target }, isOpen = { open }, openEditor = { database, text, source ->
            opened += database to text
            sources += source
        })
        val personal = DatabaseId("P")

        fun fromClipboard() = run.start(Source.Clipboard(clipboard))
    }

    /** Whether any of the run's fields holds [text] (a string, or a text handed over, its app or its page). */
    private fun QuickNoteRun.holds(text: String): Boolean = javaClass.declaredFields.any { field ->
        field.isAccessible = true
        when (val value = field.get(this)) {
            is String -> text in value
            is Source.Given -> text in value.text || text in value.source.app.orEmpty() || text in value.source.page.orEmpty()
            else -> false
        }
    }

    // The clipboard (the tile, the launcher's shortcut)

    @Test
    fun anOpenDatabaseReadsTheClipboardOnceTrimmedAndOpensItsEditor() {
        val f = Fixture()
        f.clipboard.text = "\n\n  Dune\nArrival \n\n"
        f.fromClipboard()
        assertEquals(State.Opened(f.personal), f.run.state)
        assertEquals(listOf(f.personal to "  Dune\nArrival "), f.opened)
        assertEquals(1, f.clipboard.reads)
        assertEquals(0, f.clipboard.writes, "the clipboard is left as it was")
    }

    @Test
    fun noTextOrBlankTextOnTheClipboardOpensNothingAndSaysSo() {
        val f = Fixture()
        f.fromClipboard()
        assertEquals(State.NoText(fromClipboard = true), f.run.state)
        assertEquals(0, f.clipboard.reads, "with no text on it the clipboard is not read, only its description")
        assertEquals("No text on the clipboard", f.run.state.message)
        f.clipboard.text = " \n\t\n"
        f.fromClipboard()
        assertEquals(State.NoText(fromClipboard = true), f.run.state)
        assertTrue(f.opened.isEmpty())
    }

    @Test
    fun aLockedDatabaseIsUnlockedFirstTheClipboardReadOnlyAfterTheUnlock() {
        val f = Fixture(open = false)
        f.clipboard.text = "Dune"
        f.fromClipboard()
        assertEquals(State.Unlocking(f.personal), f.run.state)
        assertEquals(0, f.clipboard.reads, "nothing of the text in memory while the lock screen waits")
        f.run.unlocked(DatabaseId("other"))
        assertEquals(State.Unlocking(f.personal), f.run.state, "another database unlocked: still waiting")
        f.run.unlocked(f.personal)
        assertEquals(State.Unlocking(f.personal), f.run.state, "told unlocked while it is not open: still waiting")
        assertEquals(0, f.clipboard.reads)
        f.open = true
        f.run.unlocked(f.personal)
        assertEquals(State.Opened(f.personal), f.run.state)
        assertEquals(1, f.clipboard.reads)
        assertEquals(listOf(f.personal to "Dune"), f.opened)
        f.run.unlocked(f.personal)
        assertEquals(1, f.opened.size, "opened once")
    }

    @Test
    fun theTextCopiedAfterTheRequestIsTheOneTakenOnceUnlocked() {
        val f = Fixture(open = false)
        f.clipboard.text = "Dune"
        f.fromClipboard()
        f.clipboard.text = "   "
        f.open = true
        f.run.unlocked(f.personal)
        assertEquals(State.NoText(fromClipboard = true), f.run.state, "blank by then: no editor")
        assertTrue(f.opened.isEmpty())
    }

    // A text handed over (a share, the selection menu)

    @Test
    fun aTextHandedOverOpensTheEditorTrimmedAndTheClipboardIsNotTouched() {
        val f = Fixture()
        f.clipboard.text = "something else"
        f.run.start(Source.Given("\n  Dune\n\n"))
        assertEquals(State.Opened(f.personal), f.run.state)
        assertEquals(listOf(f.personal to "  Dune"), f.opened)
        assertEquals(0, f.clipboard.reads)
    }

    @Test
    fun noTextOrABlankOneHandedOverSaysNoTextToSave() {
        val f = Fixture()
        f.run.start(Source.Given(" \n\t "))
        assertEquals(State.NoText(fromClipboard = false), f.run.state)
        assertEquals("No text to save", f.run.state.message)
        f.run.start(Source.Given(""))
        assertEquals(State.NoText(fromClipboard = false), f.run.state)
        assertTrue(f.opened.isEmpty())
    }

    @Test
    fun aTextHandedOverWaitsForTheUnlockAndGoesToTheEditorThen() {
        val f = Fixture(open = false)
        f.run.start(Source.Given("Dune"))
        assertEquals(State.Unlocking(f.personal), f.run.state)
        assertTrue(f.opened.isEmpty())
        assertTrue(f.run.holds("Dune"), "held while the lock screen waits (it came in the intent)")
        f.open = true
        f.run.unlocked(f.personal)
        assertEquals(listOf(f.personal to "Dune"), f.opened)
        assertFalse(f.run.holds("Dune"), "handed to the editor, not kept")
    }

    // Where it came from (app.quick-note-source)

    private val chrome = QuickNoteSource(app = "Chrome", page = "https://films.example.org/festival")

    @Test
    fun theAppAndThePageGoToTheEditorWithTheText() {
        // spec: app.quick-note-source/AC1
        val f = Fixture()
        f.run.start(Source.Given("Dune", chrome))
        assertEquals(listOf(chrome), f.sources)
        assertEquals("Chrome", f.sources.single().app)
        assertEquals("https://films.example.org/festival", f.sources.single().page)
    }

    @Test
    fun theyWaitForTheUnlockWithTheTextAndAreDroppedWhenItIsGivenUp() {
        // spec: app.quick-note-source/AC3
        val f = Fixture(open = false)
        f.run.start(Source.Given("Dune", chrome))
        assertTrue(f.run.holds("films.example.org") && f.run.holds("Chrome"), "held while the lock screen waits, as the text")
        f.open = true
        f.run.unlocked(f.personal)
        assertEquals(listOf(chrome), f.sources, "kept through the unlock")
        assertFalse(f.run.holds("films.example.org") || f.run.holds("Chrome"), "handed to the editor, not kept")

        val given = Fixture(open = false)
        given.run.start(Source.Given("Dune", chrome))
        given.run.giveUp()
        assertFalse(given.run.holds("films.example.org") || given.run.holds("Chrome"), "given up: nothing kept")
        given.open = true
        given.run.unlocked(given.personal)
        assertTrue(given.sources.isEmpty())
    }

    @Test
    fun theClipboardSaysNoAppAndNoPage() {
        // spec: app.quick-note-source/AC4
        val f = Fixture()
        f.clipboard.text = "Dune"
        f.fromClipboard()
        assertEquals(listOf(QuickNoteSource()), f.sources)
        val handed = Fixture()
        handed.run.start(Source.Given("Dune"))
        assertEquals(listOf(QuickNoteSource()), handed.sources, "a text handed over with nothing known")
    }

    // Either way

    @Test
    fun noDatabaseToPutItIn() {
        val f = Fixture(target = null)
        f.clipboard.text = "Dune"
        f.fromClipboard()
        assertEquals(State.NoDatabase, f.run.state)
        assertEquals("No database to put the quick note in", f.run.state.message)
        assertEquals(0, f.clipboard.reads)
        f.run.start(Source.Given("Dune"))
        assertEquals(State.NoDatabase, f.run.state)
        assertTrue(f.opened.isEmpty())
    }

    @Test
    fun givenUpAtTheLockScreenNothingIsReadAndALaterUnlockOpensNothing() {
        for (source in listOf<(Fixture) -> Source>({ Source.Clipboard(it.clipboard) }, { Source.Given("Dune") })) {
            val f = Fixture(open = false)
            f.clipboard.text = "Dune"
            f.run.start(source(f))
            f.run.giveUp()
            assertEquals(State.GivenUp, f.run.state)
            f.open = true
            f.run.unlocked(f.personal)
            assertEquals(State.GivenUp, f.run.state, "an unlock after giving up opens nothing")
            assertEquals(0, f.clipboard.reads)
            assertTrue(f.opened.isEmpty())
            assertFalse(f.run.holds("Dune"), "given up: nothing kept")
        }
    }

    @Test
    fun givingUpOnlyMeansSomethingWhileTheUnlockWaits() {
        val f = Fixture()
        f.run.start(Source.Given("Dune"))
        f.run.giveUp()
        assertEquals(State.Opened(f.personal), f.run.state, "the editor is open: its own Back gives it up, not the run")
    }

    @Test
    fun aSecondRequestWhileTheUnlockWaitsChangesNothing() {
        val f = Fixture(open = false)
        f.run.start(Source.Given("Dune"))
        f.run.start(Source.Given("Arrival"))
        assertEquals(State.Unlocking(f.personal), f.run.state)
        f.open = true
        f.run.unlocked(f.personal)
        assertEquals(listOf(f.personal to "Dune"), f.opened, "the first one's text (AQ2 decides whether a second share replaces it)")
    }

    @Test
    fun theDatabaseIsTheTargetAtTheRequestNotAtTheUnlock() {
        val f = Fixture(open = false)
        f.run.start(Source.Given("Dune"))
        f.target = DatabaseId("W")
        f.open = true
        f.run.unlocked(DatabaseId("W"))
        assertEquals(State.Unlocking(f.personal), f.run.state, "another database unlocked")
        f.run.unlocked(f.personal)
        assertEquals(listOf(f.personal to "Dune"), f.opened)
    }

    @Test
    fun nothingOfTheTextIsStoredInTheSettingsOrKeptByTheRun() {
        val store = InMemorySettingsStore()
        val settings = AppSettings(store)
        val personal = DatabaseRecord(DatabaseId("P"), FileLocation.ByPath("/a/Personal.kdbx"))
        settings.chooseQuickNoteDatabase(personal.id)
        val before = store.snapshot()

        val databases = Databases(listOf(personal), currentId = personal.id)
        val opened = mutableListOf<String>()
        val run = QuickNoteRun(
            target = { QuickNoteTarget.resolve(settings.quickNoteDatabase.value, databases) }, isOpen = { false },
            openEditor = { _, text, _ -> opened += text },
        )
        run.start(Source.Given("Unforgettable words"))
        assertEquals(State.Unlocking(personal.id), run.state)
        assertFalse("Unforgettable" in run.state.toString())
        assertFalse("Unforgettable" in Source.Given("Unforgettable words").toString(), "a source says nothing of its text")
        run.giveUp()
        assertFalse(run.holds("Unforgettable"))
        assertEquals(before, store.snapshot(), "the run writes nothing to the store")
        assertFalse(store.snapshot().values.any { "Unforgettable" in it })
        assertEquals(mapOf("quickNoteDatabase" to "P"), before, "the database's id alone")
        assertTrue(opened.isEmpty())
    }
}
