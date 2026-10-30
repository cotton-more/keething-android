package dev.keething.android.editor

import dev.keething.android.databases.DatabaseLabel
import dev.keething.android.databases.DatabaseMark
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which open database a new entry goes into (keething-spec databases.one-list AC4–AC6; the Mac's DatabasesWindowTests). */
class NewEntryDatabasesTest {
    private val personal = DatabaseId("P")
    private val work = DatabaseId("W")
    private val shop = DatabaseId("S")

    private fun open(id: DatabaseId, name: String, session: Int, groups: Boolean = true) = NewEntryDatabase(
        id = id,
        session = session,
        vault = Vault(entries = emptyList(), groups = if (groups) listOf(VaultGroup("$name-root", name, emptyList())) else emptyList()),
        mark = DatabaseMark(id, DatabaseLabel(name), colorIndex = session),
        folder = "Sync/KeePass",
    )

    private val p = open(personal, "Personal", 1)
    private val w = open(work, "Work", 2)
    private val s = open(shop, "Workshop", 3)

    // spec: databases.one-list/AC4
    @Test
    fun aNewEntryStartsInTheDatabaseTheSearchNamesElseTheOpenEntrysElseTheLastOneUsed() {
        val all = listOf(p, w, s)
        assertEquals(work, NewEntryDatabases.start(all, "!Work git", openEntry = EntryRef(personal, "x"), last = personal), "the search names it")
        assertEquals(work, NewEntryDatabases.start(all, "!\"Work\" ", null, null), "quoted")
        assertEquals(shop, NewEntryDatabases.start(all, "!worksh", null, null), "a label's start")
        assertEquals(work, NewEntryDatabases.start(all, "!work", null, last = shop), "a whole label keeps that database only, not Workshop")
        assertEquals(personal, NewEntryDatabases.start(all, "git", openEntry = EntryRef(personal, "x"), last = work), "else the open entry's")
        assertEquals(shop, NewEntryDatabases.start(all, "", openEntry = null, last = shop), "else the last one a new entry went into")
        assertEquals(personal, NewEntryDatabases.start(all, "", null, last = DatabaseId("gone")), "else the first open one")
        assertEquals(personal, NewEntryDatabases.start(all, "!Crypto", EntryRef(DatabaseId("C"), "x"), null), "none of them open: the first")
        assertNull(NewEntryDatabases.start(emptyList(), "", null, null))
    }

    @Test
    fun aDatabaseWithoutAGroupTakesNoNewEntry() {
        val empty = open(work, "Work", 2, groups = false)
        assertEquals(personal, NewEntryDatabases.start(listOf(empty, p), "!Work", EntryRef(work, "x"), last = work))
        assertEquals(emptyList(), NewEntryDatabases.offered(listOf(empty, p)), "one left to choose: no Save into")
    }

    // spec: databases.one-list/AC5
    @Test
    fun saveIntoOffersTheOpenDatabasesWhileTwoOrMoreAreOpenAndNamesThoseNotOpen() {
        assertEquals(emptyList(), NewEntryDatabases.offered(listOf(p)), "one open: nothing of this shows")
        assertEquals(listOf(p, w), NewEntryDatabases.offered(listOf(p, w)))
        val window = listOf(p.mark, DatabaseMark(DatabaseId("F"), DatabaseLabel("Family Shared"), 4), w.mark, DatabaseMark(DatabaseId("C"), DatabaseLabel("Crypto"), 5))
        assertEquals("Not open: Family Shared, Crypto", NewEntryDatabases.notOpen(window, listOf(p, w)))
        assertNull(NewEntryDatabases.notOpen(listOf(p.mark, w.mark), listOf(p, w)), "all open")
        assertEquals("Sync/KeePass", p.detail, "its folder under its label")
        val alike = NewEntryDatabase(work, 2, w.vault, DatabaseMark(work, DatabaseLabel("Work", "Sync/KeePass"), 2), "Sync/KeePass")
        assertNull(alike.detail, "the label says the folder already (names alike): not twice")
    }

    // spec: databases.one-list/AC6
    @Test
    fun aDraftGoesWhenItsDatabaseLocksAndStaysWhenAnotherDoes() {
        assertTrue(NewEntryDatabases.isOpen(work, 2, listOf(p, w)))
        assertTrue(NewEntryDatabases.isOpen(work, 2, listOf(w)), "Personal locked: Work's draft stays")
        assertFalse(NewEntryDatabases.isOpen(work, 2, listOf(p)), "Work locked: its draft goes")
        assertFalse(NewEntryDatabases.isOpen(work, 2, listOf(p, open(work, "Work", 7))), "Work closed and put back meanwhile: another session")
    }

    // spec: databases.one-list/AC6
    @Test
    fun theDraftFollowsItsOwnDatabaseAndASaveInFlightKeepsItsSheetUntilItEnds() {
        val draft = DraftFollower()
        assertFalse(draft.follow(work, 2, emptyList(), editing = true), "not seen open yet (the list follows a moment later): stays")
        assertFalse(draft.follow(work, 2, listOf(p, w), editing = true))
        assertFalse(draft.follow(work, 2, listOf(w), editing = true), "Personal locked: Work's draft stays")
        assertTrue(draft.follow(work, 2, listOf(p), editing = true), "Work locked while editing: the draft goes")

        val saving = DraftFollower()
        saving.follow(work, 2, listOf(p, w), editing = true)
        assertFalse(saving.follow(work, 2, listOf(p), editing = false), "Work locked while its save is written: the sheet stays")
        assertTrue(saving.gone)
        assertTrue(saving.backToEditing(), "the save failed and Back to editing: then the draft goes")

        val moved = DraftFollower()
        moved.follow(work, 2, listOf(p, w), editing = true)
        moved.moved(1)
        assertFalse(moved.follow(personal, 1, listOf(p), editing = true), "moved to Personal: Work locking leaves it")
        assertFalse(moved.backToEditing())
        assertTrue(moved.follow(personal, 1, listOf(w), editing = true), "then Personal locking takes it")
    }

    // spec: sharing.receiving/AC8
    @Test
    fun receivedEntriesGoIntoTheLastNewEntrysDatabaseUntilAnotherIsChosenAndStayOnceASaveWasTried() {
        assertEquals(work, NewEntryDatabases.following(null, listOf(p, w), last = work, keep = false)?.id, "to start with: the last new entry's")
        assertEquals(personal, NewEntryDatabases.following(null, listOf(p, w), last = null, keep = false)?.id, "else the first open one")
        assertEquals(work, NewEntryDatabases.following(w, listOf(p, w, s), last = personal, keep = false)?.id, "chosen: it stays while open")
        assertEquals(personal, NewEntryDatabases.following(w, listOf(p, s), last = work, keep = false)?.id, "Work locked before anything was saved: another")
        assertEquals(w, NewEntryDatabases.following(w, listOf(p, s), last = null, keep = true), "a save tried in Work: the rest go there or nowhere")
        assertNull(NewEntryDatabases.following(null, emptyList(), last = null, keep = false))
    }
}
