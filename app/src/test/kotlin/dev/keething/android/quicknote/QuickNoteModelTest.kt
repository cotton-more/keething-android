package dev.keething.android.quicknote

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.keething.android.quicknote.QuickNoteModel.Showing
import dev.keething.card.editing.QuickNoteSource
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import dev.keething.vault.opening.OpenFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The quick note's screen over another app (SPEC §6.4; keething-spec app.quick-note AC3, AC4, AC7; the Mac's
 * `QuickNoteTests`, "The window"): a text handed over by a share or the selection menu, into the quick note's editor,
 * after the database's lock screen when it is locked; given up by Back, by the screen left, by a lock, by its database
 * gone, by a newer quick note and by a screen re-created after its process died, keeping nothing of the text. The
 * clipboard's (the tile, the launcher's shortcut) once the screen has the window focus, its text after the unlock.
 */
/** An open database's state. */
private fun unlocked(vault: Vault = Vault(emptyList())) = SessionState.Unlocked(vault)

class QuickNoteModelTest {
    private val personal = DatabaseId("P")

    private class Fixture(scope: TestScope, open: Boolean = true, inWindow: Boolean = true, val started: MutableStateFlow<Int> = MutableStateFlow(0)) {
        var target: DatabaseId? = DatabaseId("P")

        /** The database's state as the window has it; null while it is not in the window. */
        val state = MutableStateFlow(if (!inWindow) null else if (open) unlocked() else SessionState.Locked(LockReason.Initial))

        /** What the sessions say when it differs from the window's state (the window's flow follows them a moment late). */
        var sessionsSay: Boolean? = null
        val intoWindow = mutableListOf<DatabaseId>()

        /** The app's locks, counted. */
        val locks = MutableStateFlow(0)

        /** What it last said of waiting behind a screen it opened (the lock alarm wakes the app for it); null: nothing. */
        var awaited: Boolean? = null
        val model = QuickNoteModel(
            target = { target },
            isOpen = { sessionsSay ?: (state.value is SessionState.Unlocked) },
            state = { state },
            intoWindow = { intoWindow += it },
            started = started,
            locks = locks,
            awaited = { awaited = it },
            scope = scope.backgroundScope,
        )

        val showing: Showing get() = model.showing.value
    }

    /** Whether the model, its run or what it shows holds [text] anywhere. */
    private fun holds(model: Any, text: String, depth: Int = 0): Boolean {
        if (depth > 3) return false
        return generateSequence<Class<*>>(model.javaClass) { it.superclass.takeIf { s -> s.name.startsWith("dev.keething") } }
            .flatMap { it.declaredFields.asSequence() }
            .any { field ->
                field.isAccessible = true
                when (val value = field.get(model)) {
                    is String -> text in value
                    is Showing.Editor -> value.note.holds(text)
                    is MutableStateFlow<*> -> value.value?.let { it is Showing.Editor && it.note.holds(text) } == true
                    is QuickNoteRun.Source.Given -> text in value.text || text in value.source.app.orEmpty() || text in value.source.page.orEmpty()
                    is QuickNoteRun -> holds(value, text, depth + 1)
                    else -> false
                }
            }
    }

    private fun dev.keething.android.editor.NewNote.holds(text: String) =
        text in this.text || text in source.app.orEmpty() || text in source.page.orEmpty()

    // Open, locked, unlocked

    @Test
    fun anOpenDatabaseOpensTheQuickNotesEditorWithTheTextTrimmed() = runTest {
        val f = Fixture(this)
        f.model.start(recreated = false) { given("\n\n  Perfect Days (2023)\nWim Wenders \n\n") }
        runCurrent()
        val editor = assertIs<Showing.Editor>(f.showing)
        assertEquals(personal, editor.database)
        assertEquals("  Perfect Days (2023)\nWim Wenders ", editor.note.text)
        assertTrue(editor.note.quick, "a quick note's editor: the Inbox, no Save into")
        assertTrue(f.intoWindow.isEmpty())
    }

    @Test
    fun noTextOrABlankOneSaysNoTextToSaveAndNoDatabaseSaysSo() = runTest {
        for (text in listOf(null, "", " \n\t\n")) {
            val f = Fixture(this)
            f.model.start(recreated = false) { given(text) }
            assertEquals(Showing.Gone("No text to save"), f.showing, "for ${text?.length} characters")
            assertTrue(f.intoWindow.isEmpty(), "no unlock asked for nothing")
        }
        val f = Fixture(this)
        f.target = null
        f.model.start(recreated = false) { given("Dune") }
        assertEquals(Showing.Gone("No database to put the quick note in"), f.showing)
    }

    @Test
    fun aLockedDatabaseShowsItsLockScreenAWrongPasswordKeepsItAndTheUnlockOpensTheEditor() = runTest {
        val f = Fixture(this, open = false)
        f.model.start(recreated = false) { given("Dune") }
        runCurrent()
        assertEquals(Showing.Unlock(personal), f.showing)
        f.state.value = SessionState.Unlocking
        runCurrent()
        f.state.value = SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongPassword))
        runCurrent()
        assertEquals(Showing.Unlock(personal), f.showing, "a wrong password: its lock screen still")
        f.state.value = unlocked()
        runCurrent()
        val editor = assertIs<Showing.Editor>(f.showing)
        assertEquals("Dune", editor.note.text)
        assertTrue(editor.note.quick)
    }

    @Test
    fun theTagAndThePageReachTheEditorThroughTheUnlockAndAreGoneWithAQuickNoteGivenUp() = runTest {
        // spec: app.quick-note-source/AC3
        val chrome = QuickNoteSource(app = "Chrome", page = "https://films.example.org/festival")
        val f = Fixture(this, open = false)
        f.model.start(recreated = false) { given("Dune", chrome) }
        runCurrent()
        f.state.value = unlocked()
        runCurrent()
        assertEquals(chrome, assertIs<Showing.Editor>(f.showing).note.source)

        val given = Fixture(this, open = false)
        given.model.start(recreated = false) { given("Dune", chrome) }
        runCurrent()
        given.model.back()
        runCurrent()
        assertFalse(holds(given.model, "films.example.org") || holds(given.model, "Chrome"), "given up: nothing of them kept")
    }

    @Test
    fun aDatabaseOfTheListNotInTheWindowIsBroughtIntoItForItsLockScreen() = runTest {
        val f = Fixture(this, inWindow = false)
        f.model.start(recreated = false) { given("Dune") }
        runCurrent()
        assertEquals(Showing.Unlock(personal), f.showing, "not open: its lock screen, not given up for not being there yet")
        assertEquals(listOf(personal), f.intoWindow)
        f.state.value = SessionState.Locked(LockReason.Initial)
        runCurrent()
        f.state.value = unlocked()
        runCurrent()
        assertIs<Showing.Editor>(f.showing)
    }

    // Given up

    @Test
    fun backAtTheLockScreenGivesUpAndALaterUnlockOpensNothingKeepingNothing() = runTest {
        val f = Fixture(this, open = false)
        f.model.start(recreated = false) { given("Films to watch: Dune") }
        runCurrent()
        assertTrue(holds(f.model, "Films to watch"), "held while the lock screen waits (it came in the intent)")
        f.model.back()
        assertEquals(Showing.Gone(null), f.showing)
        f.state.value = unlocked()
        runCurrent()
        assertEquals(Showing.Gone(null), f.showing, "unlocked later (the main app): no editor")
        assertFalse(holds(f.model, "Films to watch"))
    }

    @Test
    fun theScreenLeftGivesItUpButAScreenItOpenedItselfDoesNotUntilTheAppLocks() = runTest {
        val f = Fixture(this, open = false)
        f.model.start(recreated = false) { given("Dune") }
        runCurrent()
        f.model.stopped(ownInFront = true)
        advanceTimeBy(60.minutes)
        runCurrent()
        assertEquals(Showing.Unlock(personal), f.showing, "the key file picker in front: still waiting, the app's locks decide")
        f.locks.value++
        runCurrent()
        assertEquals(Showing.Gone(null), f.showing, "a stay away past the lock timeout, counted when the app is back in front")
        assertFalse(holds(f.model, "Dune"))

        val g = Fixture(this, open = false)
        g.model.start(recreated = false) { given("Dune") }
        runCurrent()
        g.model.stopped(ownInFront = false)
        assertEquals(Showing.Gone(null), g.showing, "Home, another app, the screen off")
        g.state.value = unlocked()
        runCurrent()
        assertEquals(Showing.Gone(null), g.showing)
    }

    @Test
    fun theEditorLeftClosedOrLockedGivesItUpKeepingNothing() = runTest {
        val left = Fixture(this)
        left.model.start(recreated = false) { given("Dune") }
        runCurrent()
        left.model.stopped(ownInFront = false)
        assertEquals(Showing.Gone(null), left.showing)
        assertFalse(holds(left.model, "Dune"))

        val closed = Fixture(this)
        closed.model.start(recreated = false) { given("Dune") }
        runCurrent()
        closed.model.editorClosed()
        assertEquals(Showing.Gone(null), closed.showing, "the editor's own X or Back, discarded")

        val locked = Fixture(this)
        locked.model.start(recreated = false) { given("Dune") }
        runCurrent()
        locked.state.value = SessionState.Locked(LockReason.Manual)
        runCurrent()
        assertEquals(Showing.Gone(null), locked.showing, "Lock all from the main app, the timeout")
        assertFalse(holds(locked.model, "Dune"))
        locked.state.value = unlocked()
        runCurrent()
        assertEquals(Showing.Gone(null), locked.showing, "unlocked again: nothing comes back")
    }

    @Test
    fun aLockCountedAfterTheStartGivesItUpAtTheLockScreenAndInTheEditor() = runTest {
        val waiting = Fixture(this, open = false)
        waiting.locks.value = 3
        waiting.model.start(recreated = false) { given("Dune") }
        runCurrent()
        assertEquals(Showing.Unlock(personal), waiting.showing, "the locks before it are not its own")
        waiting.locks.value++
        runCurrent()
        assertEquals(Showing.Gone(null), waiting.showing, "Lock all (split screen), its database locked already")
        waiting.state.value = unlocked()
        runCurrent()
        assertEquals(Showing.Gone(null), waiting.showing)

        val editing = Fixture(this)
        editing.model.start(recreated = false) { given("Dune") }
        runCurrent()
        editing.locks.value++
        runCurrent()
        assertEquals(Showing.Gone(null), editing.showing)
        assertFalse(holds(editing.model, "Dune"))
    }

    @Test
    fun theDatabaseGoneFromTheWindowGivesItUp() = runTest {
        val waiting = Fixture(this, open = false)
        waiting.model.start(recreated = false) { given("Dune") }
        runCurrent()
        waiting.state.value = null
        runCurrent()
        assertEquals(Showing.Gone(null), waiting.showing, "removed from the list or closed while its lock screen waits")

        val editing = Fixture(this)
        editing.model.start(recreated = false) { given("Dune") }
        runCurrent()
        editing.state.value = null
        runCurrent()
        assertEquals(Showing.Gone(null), editing.showing)
    }

    @Test
    fun aNewerQuickNoteReplacesTheOneWaiting() = runTest {
        val started = MutableStateFlow(0)
        val first = Fixture(this, open = false, started = started)
        val second = Fixture(this, open = false, started = started)
        first.model.start(recreated = false) { given("Dune") }
        runCurrent()
        assertEquals(Showing.Unlock(personal), first.showing)
        second.model.start(recreated = false) { given("Arrival") }
        runCurrent()
        assertEquals(Showing.Gone(null), first.showing, "the newer one replaces it")
        assertFalse(holds(first.model, "Dune"))
        assertEquals(Showing.Unlock(personal), second.showing)
    }

    @Test
    fun aRequestWithNoTextOrNoDatabaseLeavesTheOneWaitingAlone() = runTest {
        val started = MutableStateFlow(0)
        val first = Fixture(this, open = false, started = started)
        first.model.start(recreated = false) { given("Dune") }
        runCurrent()
        val blank = Fixture(this, started = started)
        blank.model.start(recreated = false) { given(" \n ") }
        runCurrent()
        assertEquals(Showing.Gone("No text to save"), blank.showing)
        val nowhere = Fixture(this, started = started)
        nowhere.target = null
        nowhere.model.start(recreated = false) { given("Arrival") }
        runCurrent()
        assertEquals(Showing.Gone("No database to put the quick note in"), nowhere.showing)
        assertEquals(Showing.Unlock(personal), first.showing, "nothing replaced it")
        first.state.value = unlocked()
        runCurrent()
        assertEquals("Dune", assertIs<Showing.Editor>(first.showing).note.text)
    }

    @Test
    fun theWindowStillSayingOpenJustAfterALockOpensNothingAndLosesNothing() = runTest {
        // Seen on the Pixel 6: the lock timeout ran out as the screen started; the window's flow still said open.
        val f = Fixture(this)
        f.sessionsSay = false
        f.model.start(recreated = false) { given("Dune") }
        runCurrent()
        assertEquals(Showing.Unlock(personal), f.showing, "the sessions decide: locked")
        f.state.value = SessionState.Locked(LockReason.Timeout)
        runCurrent()
        assertEquals(Showing.Unlock(personal), f.showing, "the window catching up gives nothing up")
        f.sessionsSay = null
        f.state.value = unlocked()
        runCurrent()
        assertEquals("Dune", assertIs<Showing.Editor>(f.showing).note.text)
    }

    @Test
    fun aScreenReCreatedAfterItsProcessDiedGivesUpWithoutReadingTheIntent() = runTest {
        val f = Fixture(this)
        f.model.start(recreated = true) { given(fail("the intent read again")) }
        assertEquals(Showing.Gone(null), f.showing)
        assertEquals(0, f.started.value, "nothing started")
    }

    @Test
    fun waitingBehindAScreenItOpenedItHasTheAppWokenAtTheTimeoutWhoseLockGivesItUp() = runTest {
        // spec: app.quick-note/AC4
        val f = Fixture(this, open = false)
        f.model.start(recreated = false) { given("Dune") }
        runCurrent()
        f.model.stopped(ownInFront = true)
        assertEquals(true, f.awaited, "Home from the key file picker: the lock alarm wakes the app, nothing open to lock too")
        f.locks.value++
        runCurrent()
        assertEquals(Showing.Gone(null), f.showing)
        assertEquals(false, f.awaited, "gone, nothing to wake the app for")
        assertFalse(holds(f.model, "Dune"))

        val left = Fixture(this, open = false)
        left.model.start(recreated = false) { given("Dune") }
        runCurrent()
        left.model.stopped(ownInFront = false)
        assertEquals(Showing.Gone(null), left.showing)
        assertEquals(null, left.awaited, "left: given up at once, no alarm")
        left.model.stopped(ownInFront = true)
        assertEquals(null, left.awaited, "gone already: nothing to wake the app for")
    }

    @Test
    fun clearedWhileWaitingItNoLongerWaitsAndKeepsNothing() = runTest {
        // spec: app.quick-note/AC4
        val f = Fixture(this, open = false)
        val store = ViewModelStore()
        ViewModelProvider.create(store, viewModelFactory { initializer { f.model } })[QuickNoteModel::class]
        f.model.start(recreated = false) { given("Dune") }
        runCurrent()
        f.model.stopped(ownInFront = true)
        store.clear()
        assertEquals(false, f.awaited)
        assertEquals(Showing.Gone(null), f.showing)
        assertFalse(holds(f.model, "Dune"))
    }

    @Test
    fun aQuickNoteGoneWithoutWaitingLeavesAnotherOneWaiting() = runTest {
        // spec: app.quick-note/AC4
        val started = MutableStateFlow(0)
        val waiting = Fixture(this, open = false, started = started)
        waiting.model.start(recreated = false) { given("Dune") }
        runCurrent()
        waiting.model.stopped(ownInFront = true)
        assertEquals(true, waiting.awaited)
        // The tile with nothing on the clipboard, and a screen re-created after its process died: neither ever waited.
        val empty = Fixture(this, open = false, started = started)
        empty.model.start(recreated = false) { given("  ") }
        val recreated = Fixture(this, open = false, started = started)
        recreated.model.start(recreated = true) { fail("the intent read again") }
        runCurrent()
        assertEquals(null, empty.awaited)
        assertEquals(null, recreated.awaited)
        assertIs<Showing.Unlock>(waiting.showing)
    }

    @Test
    fun startedAgainByTheSameScreenIsTheSameQuickNote() = runTest {
        val f = Fixture(this, open = false)
        f.model.start(recreated = false) { given("Dune") }
        runCurrent()
        // A configuration change: the model is kept, and the new screen's start passes recreated = false.
        f.model.start(recreated = false) { given(fail("read again")) }
        runCurrent()
        assertEquals(Showing.Unlock(personal), f.showing, "a configuration change keeps the model and its quick note")
        assertEquals(1, f.started.value)
    }

    // The clipboard (the tile, the launcher's shortcut)

    /**
     * The clipboard as Android shows it to the app: nothing, not even whether it holds text, while the screen does not
     * have the window focus (plans/android-notes.md AQ0). Counts the looks at its description and the reads of its text.
     */
    private class FocusClipboard : dev.keething.android.clipboard.SystemClipboard {
        var text: String? = null
        var inFront = false
        var looks = 0
        var reads = 0

        override fun put(text: String, sensitive: Boolean, token: String) = fail("the clipboard is left as it was")

        override fun owner() = dev.keething.android.clipboard.ClipOwner.Other

        override fun clear() = fail("the clipboard is left as it was")

        override fun text(): String? {
            reads++
            return text.takeIf { inFront }
        }

        override fun hasText(): Boolean {
            looks++
            return inFront && text != null
        }
    }

    private class Clipboard(scope: TestScope, open: Boolean = true, started: MutableStateFlow<Int> = MutableStateFlow(0)) {
        val clipboard = FocusClipboard()
        val f = Fixture(scope, open = open, started = started)

        fun start() = f.model.start(recreated = false) { QuickNoteRun.Source.Clipboard(clipboard) }

        /** The window focus, as the activity hands it on: the clipboard follows it. */
        fun focus(has: Boolean) {
            clipboard.inFront = has
            f.model.focus(has)
        }
    }

    @Test
    fun theClipboardIsLookedAtOnlyOnceTheScreenHasTheFocusAndItsTextReadOnce() = runTest {
        // spec: app.quick-note/AC7
        val c = Clipboard(this)
        c.clipboard.text = "\n Perfect Days (2023)\n"
        c.start()
        runCurrent()
        assertEquals(Showing.Nothing, c.f.showing, "no focus yet (onResume): it waits instead of finding no text")
        assertEquals(0, c.clipboard.looks + c.clipboard.reads)
        c.focus(true)
        runCurrent()
        val editor = assertIs<Showing.Editor>(c.f.showing)
        assertEquals(" Perfect Days (2023)", editor.note.text)
        assertTrue(editor.note.quick)
        assertEquals(QuickNoteSource(), editor.note.source, "the clipboard says no app and no page (app.quick-note-source AC4)")
        c.focus(false)
        c.focus(true)
        runCurrent()
        assertEquals(1, c.clipboard.reads, "read once: one \"pasted from your clipboard\" per note")
        assertIs<Showing.Editor>(c.f.showing)
    }

    @Test
    fun noTextOnTheClipboardSaysSoAndAsksNoUnlockWhenTheDatabaseIsLocked() = runTest {
        for (open in listOf(true, false)) {
            val c = Clipboard(this, open = open)
            c.start()
            c.focus(true)
            runCurrent()
            assertEquals(Showing.Gone("No text on the clipboard"), c.f.showing, if (open) "open" else "locked")
            assertTrue(c.f.intoWindow.isEmpty(), "no unlock asked for nothing")
            assertEquals(0, c.clipboard.reads, "its description alone looked at")
        }
    }

    @Test
    fun aLockedDatabaseReadsTheClipboardOnlyAfterTheUnlockWithTheFocusBack() = runTest {
        val c = Clipboard(this, open = false)
        c.clipboard.text = "Dune"
        c.start()
        c.focus(true)
        runCurrent()
        assertEquals(Showing.Unlock(personal), c.f.showing)
        assertEquals(0, c.clipboard.reads, "nothing of the text in memory while the lock screen waits")
        c.focus(false) // The fingerprint prompt, the system's window over the screen.
        c.f.state.value = unlocked()
        runCurrent()
        assertEquals(Showing.Unlock(personal), c.f.showing, "unlocked under the prompt: waits for the focus, finds no empty clipboard")
        assertEquals(0, c.clipboard.reads)
        c.focus(true)
        runCurrent()
        assertEquals("Dune", assertIs<Showing.Editor>(c.f.showing).note.text)
        assertEquals(1, c.clipboard.reads)
    }

    @Test
    fun lockedAgainBeforeTheFocusCameBackKeepsTheLockScreen() = runTest {
        val c = Clipboard(this, open = false)
        c.clipboard.text = "Dune"
        c.start()
        c.focus(true)
        runCurrent()
        c.focus(false)
        c.f.state.value = unlocked()
        runCurrent()
        c.f.state.value = SessionState.Locked(LockReason.Timeout)
        runCurrent()
        c.focus(true)
        runCurrent()
        assertEquals(Showing.Unlock(personal), c.f.showing, "its lock screen again, nothing read")
        assertEquals(0, c.clipboard.reads)
    }

    @Test
    fun theScreenLeftBeforeItHadTheFocusGivesUpLookingAtNothing() = runTest {
        val c = Clipboard(this)
        c.clipboard.text = "Dune"
        c.start()
        c.f.model.stopped(ownInFront = false)
        assertEquals(Showing.Gone(null), c.f.showing)
        c.focus(true)
        runCurrent()
        assertEquals(Showing.Gone(null), c.f.showing)
        assertEquals(0, c.clipboard.looks + c.clipboard.reads)
        assertEquals(0, c.f.started.value)
    }

    @Test
    fun startedAgainBeforeTheFocusIsTheSameQuickNote() = runTest {
        val c = Clipboard(this)
        c.clipboard.text = "Dune"
        c.start()
        // A configuration change: the model is kept, and the new screen's start passes recreated = false.
        c.f.model.start(recreated = false) { fail("asked again") }
        c.focus(true)
        runCurrent()
        assertEquals("Dune", assertIs<Showing.Editor>(c.f.showing).note.text)
        assertEquals(1, c.f.started.value)
    }

    @Test
    fun aLockCountedBeforeTheFocusIsNotThisQuickNotes() = runTest {
        // The app's own start counts a stay away past the timeout; the database's state then says what to show.
        val c = Clipboard(this, open = false)
        c.clipboard.text = "Dune"
        c.start()
        c.f.locks.value++
        c.focus(true)
        runCurrent()
        assertEquals(Showing.Unlock(personal), c.f.showing)
        c.f.locks.value++
        runCurrent()
        assertEquals(Showing.Gone(null), c.f.showing, "one counted after it started is")
    }

    @Test
    fun aClipboardNoteReplacesOneWaitingOnlyOnceItHasATextAndADatabase() = runTest {
        val started = MutableStateFlow(0)
        val first = Fixture(this, open = false, started = started)
        first.model.start(recreated = false) { given("Dune") }
        runCurrent()
        val empty = Clipboard(this, started = started)
        empty.start()
        empty.focus(true)
        runCurrent()
        assertEquals(Showing.Gone("No text on the clipboard"), empty.f.showing)
        val waiting = Clipboard(this, started = started)
        waiting.clipboard.text = "Arrival"
        waiting.start()
        runCurrent()
        assertEquals(Showing.Unlock(personal), first.showing, "nothing replaced it: one without the focus has not started")
        waiting.focus(true)
        runCurrent()
        assertEquals(Showing.Gone(null), first.showing, "the newer one replaces it")
        assertEquals("Arrival", assertIs<Showing.Editor>(waiting.f.showing).note.text)
    }

    @Test
    fun aTextHandedOverDoesNotWaitForTheFocus() = runTest {
        val f = Fixture(this, open = false)
        f.model.focus(false)
        f.model.start(recreated = false) { given("Dune") }
        runCurrent()
        assertEquals(Showing.Unlock(personal), f.showing)
        f.state.value = unlocked()
        runCurrent()
        assertEquals("Dune", assertIs<Showing.Editor>(f.showing).note.text, "the fingerprint prompt's focus does not hold up a shared text")
    }

    // Saved

    @Test
    fun savedSaysTheGroupItWentToNeverTheTitleOrText() = runTest {
        val root = VaultGroup("r", "Personal", emptyList())
        val vault = Vault(
            entries = listOf(
                Entry(id = "inbox", title = "Dune", notes = "Films to watch", groupPath = listOf("Inbox")),
                Entry(id = "films", title = "Arrival", groupPath = listOf("Media", "Films")),
                Entry(id = "top", title = "Heat", groupPath = emptyList()),
            ),
            groups = listOf(root),
        )
        for ((id, said) in listOf("inbox" to "Saved to Inbox", "films" to "Saved to Films", "top" to "Saved to Personal", "gone" to "Saved")) {
            val f = Fixture(this)
            f.model.start(recreated = false) { given("Films to watch") }
            runCurrent()
            f.model.saved(vault, id)
            assertEquals(Showing.Gone(said), f.showing, id)
            assertFalse(holds(f.model, "Films to watch"))
        }
    }
}
