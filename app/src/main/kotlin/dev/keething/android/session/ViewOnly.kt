package dev.keething.android.session

import dev.keething.android.ui.Notice
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.session.SessionState

/**
 * A KDBX 3.1 database is viewed only (keething-spec kdbx.kdbx3 AC6): it reads like any other, and a change tried on it
 * says so instead of opening the editor. A one-tap change (the recycle bin, a move, a group) is refused by the session
 * and says the same through its save's failure.
 */
internal object ViewOnly {
    fun notice(): Notice = Notice("This database is KDBX 3.1, which Keething opens to view only.", isProblem = true)
}

/** Whether its database is open and viewed only. */
internal val DatabaseSession.isViewOnly: Boolean get() = (session.state.value as? SessionState.Unlocked)?.viewOnly == true
