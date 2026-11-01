package dev.keething.android.quicknote

import dev.keething.android.quicknote.QuickNoteModel.Showing
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
 * gone, by a newer quick note and by a screen re-created after its process died, keeping nothing of the text.
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
        val model = QuickNoteModel(
            target = { target },
            isOpen = { sessionsSay ?: (state.value is SessionState.Unlocked) },
            state = { state },
            intoWindow = { intoWindow += it },
            started = started,
            locks = locks,
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
                    is Showing.Editor -> text in value.note.text
                    is MutableStateFlow<*> -> value.value?.let { it is Showing.Editor && text in it.note.text } == true
                    is QuickNoteRun.Source.Given -> text in value.text
                    is QuickNoteRun -> holds(value, text, depth + 1)
                    else -> false
                }
            }
    }

    // Open, locked, unlocked

    @Test
    fun anOpenDatabaseOpensTheQuickNotesEditorWithTheTextTrimmed() = runTest {
        val f = Fixture(this)
        f.model.start(recreated = false) { "\n\n  Perfect Days (2023)\nWim Wenders \n\n" }
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
            f.model.start(recreated = false) { text }
            assertEquals(Showing.Gone("No text to save"), f.showing, "for ${text?.length} characters")
            assertTrue(f.intoWindow.isEmpty(), "no unlock asked for nothing")
        }
        val f = Fixture(this)
        f.target = null
        f.model.start(recreated = false) { "Dune" }
        assertEquals(Showing.Gone("No database to put the quick note in"), f.showing)
    }

    @Test
    fun aLockedDatabaseShowsItsLockScreenAWrongPasswordKeepsItAndTheUnlockOpensTheEditor() = runTest {
        val f = Fixture(this, open = false)
        f.model.start(recreated = false) { "Dune" }
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
    fun aDatabaseOfTheListNotInTheWindowIsBroughtIntoItForItsLockScreen() = runTest {
        val f = Fixture(this, inWindow = false)
        f.model.start(recreated = false) { "Dune" }
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
        f.model.start(recreated = false) { "Films to watch: Dune" }
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
        f.model.start(recreated = false) { "Dune" }
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
        g.model.start(recreated = false) { "Dune" }
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
        left.model.start(recreated = false) { "Dune" }
        runCurrent()
        left.model.stopped(ownInFront = false)
        assertEquals(Showing.Gone(null), left.showing)
        assertFalse(holds(left.model, "Dune"))

        val closed = Fixture(this)
        closed.model.start(recreated = false) { "Dune" }
        runCurrent()
        closed.model.editorClosed()
        assertEquals(Showing.Gone(null), closed.showing, "the editor's own X or Back, discarded")

        val locked = Fixture(this)
        locked.model.start(recreated = false) { "Dune" }
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
        waiting.model.start(recreated = false) { "Dune" }
        runCurrent()
        assertEquals(Showing.Unlock(personal), waiting.showing, "the locks before it are not its own")
        waiting.locks.value++
        runCurrent()
        assertEquals(Showing.Gone(null), waiting.showing, "Lock all (split screen), its database locked already")
        waiting.state.value = unlocked()
        runCurrent()
        assertEquals(Showing.Gone(null), waiting.showing)

        val editing = Fixture(this)
        editing.model.start(recreated = false) { "Dune" }
        runCurrent()
        editing.locks.value++
        runCurrent()
        assertEquals(Showing.Gone(null), editing.showing)
        assertFalse(holds(editing.model, "Dune"))
    }

    @Test
    fun theDatabaseGoneFromTheWindowGivesItUp() = runTest {
        val waiting = Fixture(this, open = false)
        waiting.model.start(recreated = false) { "Dune" }
        runCurrent()
        waiting.state.value = null
        runCurrent()
        assertEquals(Showing.Gone(null), waiting.showing, "removed from the list or closed while its lock screen waits")

        val editing = Fixture(this)
        editing.model.start(recreated = false) { "Dune" }
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
        first.model.start(recreated = false) { "Dune" }
        runCurrent()
        assertEquals(Showing.Unlock(personal), first.showing)
        second.model.start(recreated = false) { "Arrival" }
        runCurrent()
        assertEquals(Showing.Gone(null), first.showing, "the newer one replaces it")
        assertFalse(holds(first.model, "Dune"))
        assertEquals(Showing.Unlock(personal), second.showing)
    }

    @Test
    fun aRequestWithNoTextOrNoDatabaseLeavesTheOneWaitingAlone() = runTest {
        val started = MutableStateFlow(0)
        val first = Fixture(this, open = false, started = started)
        first.model.start(recreated = false) { "Dune" }
        runCurrent()
        val blank = Fixture(this, started = started)
        blank.model.start(recreated = false) { " \n " }
        runCurrent()
        assertEquals(Showing.Gone("No text to save"), blank.showing)
        val nowhere = Fixture(this, started = started)
        nowhere.target = null
        nowhere.model.start(recreated = false) { "Arrival" }
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
        f.model.start(recreated = false) { "Dune" }
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
        f.model.start(recreated = true) { fail("the intent read again") }
        assertEquals(Showing.Gone(null), f.showing)
        assertEquals(0, f.started.value, "nothing started")
    }

    @Test
    fun startedAgainByTheSameScreenIsTheSameQuickNote() = runTest {
        val f = Fixture(this, open = false)
        f.model.start(recreated = false) { "Dune" }
        runCurrent()
        f.model.start(recreated = true) { fail("read again") }
        runCurrent()
        assertEquals(Showing.Unlock(personal), f.showing, "a configuration change keeps the model and its quick note")
        assertEquals(1, f.started.value)
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
            f.model.start(recreated = false) { "Films to watch" }
            runCurrent()
            f.model.saved(vault, id)
            assertEquals(Showing.Gone(said), f.showing, id)
            assertFalse(holds(f.model, "Films to watch"))
        }
    }
}
