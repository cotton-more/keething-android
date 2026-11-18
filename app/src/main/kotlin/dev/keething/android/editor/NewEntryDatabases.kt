package dev.keething.android.editor

import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiText
import dev.keething.android.databases.DatabaseMark
import dev.keething.domain.vault.Vault
import dev.keething.search.EntrySearch
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef

/**
 * An open database as a new entry sees it: its session ([session], the [dev.keething.vault.databases.DatabaseSession]'s
 * number: opened again is another), what it read last, how it is called and where its file is.
 */
internal class NewEntryDatabase(val id: DatabaseId, val session: Int, val vault: Vault, val mark: DatabaseMark, val folder: String) {
    /** Under its label: its folder, unless the label says it already (names alike, keething-spec databases.name-colour AC3). */
    val detail: String? get() = folder.takeIf { it != mark.label.addition }

    /** It has a group to put a new entry in. */
    val takesEntries: Boolean get() = vault.groups.isNotEmpty()
}

/**
 * Which open database a new entry goes into (keething-spec databases.one-list AC4–AC6; the Mac's
 * `QuickAccessModel.newEntryDatabase` and `EntryCardModel.newEntryDatabases`), also a login saved by autofill and the
 * entries received (autofill.save AC7, sharing.receiving AC8). [open] are in the window's order.
 */
internal object NewEntryDatabases {
    /**
     * Where a new entry starts: the database the search names (`!Work`), else the open entry's, else the one the last new
     * entry went into, else the first open one; among those that have a group.
     */
    fun start(open: List<NewEntryDatabase>, query: String, openEntry: EntryRef?, last: DatabaseId?): DatabaseId? {
        val taking = open.filter { it.takesEntries }
        if (taking.size > 1) {
            val labels = open.map { it.mark.label.full }
            taking.firstOrNull { EntrySearch.names(it.mark.label.full, query, labels) }?.let { return it.id }
        }
        val ids = taking.map { it.id }
        return openEntry?.database?.takeIf { it in ids } ?: last?.takeIf { it in ids } ?: ids.firstOrNull()
    }

    /** Save into's choices while two or more are open: those with a group; none (no Save into) otherwise. */
    fun offered(open: List<NewEntryDatabase>): List<NewEntryDatabase> =
        if (open.size >= 2) open.filter { it.takesEntries }.takeIf { it.size >= 2 }.orEmpty() else emptyList()

    /** Under Save into's choices: the window's databases not open ("Not open: Family Shared, Crypto"), or null. */
    fun notOpen(window: List<DatabaseMark>, open: List<NewEntryDatabase>): UiText? {
        val ids = open.mapTo(HashSet()) { it.id }
        val names = window.filter { it.id !in ids }.takeIf { it.isNotEmpty() }?.map { uiPlain(it.label.full) } ?: return null
        return uiText(R.string.editor_not_open, names.reduce { list, name -> uiText(R.string.editor_names_more, list, name) })
    }

    /** A new entry's draft lives while its database is open in the same session; it goes when that one locks. */
    fun isOpen(id: DatabaseId, session: Int, open: List<NewEntryDatabase>): Boolean = open.any { it.id == id && it.session == session }

    /**
     * Where received entries go as the open databases change: the one [chosen] while it is open; once some are saved
     * ([keep]) the rest go there (opened again, the same file) or nowhere; else the start (the last new entry's, else the
     * first open one).
     */
    fun following(chosen: NewEntryDatabase?, open: List<NewEntryDatabase>, last: DatabaseId?, keep: Boolean): NewEntryDatabase? {
        if (chosen != null && isOpen(chosen.id, chosen.session, open)) return open.first { it.id == chosen.id }
        if (chosen != null && keep) return open.firstOrNull { it.id == chosen.id } ?: chosen
        val id = start(open, query = "", openEntry = null, last = last) ?: return null
        return open.first { it.id == id }
    }
}

/**
 * A new entry's draft as the open databases change (keething-spec databases.one-list AC6): it goes when its own
 * database, once seen open, locks; another database locking leaves it; a save in flight keeps its sheet until it ends,
 * and the draft goes when the editor is back to editing.
 */
internal class DraftFollower {
    /** The session of the draft's database seen open (the list of open ones follows a moment after an unlock). */
    private var followed: Int? = null

    /** Its database locked while the draft was not being edited (saving, or its sheet showing). */
    var gone: Boolean = false
        private set

    /** The open databases changed; true: the draft goes now. */
    fun follow(id: DatabaseId, session: Int, open: List<NewEntryDatabase>, editing: Boolean): Boolean {
        if (NewEntryDatabases.isOpen(id, session, open)) {
            followed = session
            gone = false
            return false
        }
        if (followed != session) return false
        gone = true
        return editing
    }

    /** Save into chose another open database, in [session]. */
    fun moved(session: Int) {
        followed = session
        gone = false
    }

    /** Back to editing after the save's sheet: true when the draft goes, its database having locked meanwhile. */
    fun backToEditing(): Boolean = gone
}
