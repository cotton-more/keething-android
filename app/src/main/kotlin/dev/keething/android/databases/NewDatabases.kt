package dev.keething.android.databases

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keething.android.storage.CreationNote

/**
 * The New database screen for the whole app (SPEC §6.2): open from the welcome, the lock screen's sheet or Settings ›
 * Databases, over whatever was shown, and kept by the app rather than a screen, so that Create goes on when the screen
 * is gone. [notCreated]: what a process that ended while creating left was cleaned up at this start.
 */
internal class NewDatabases(private val start: () -> NewDatabaseModel) {
    var form: NewDatabaseModel? by mutableStateOf(null)
        private set

    /** A database that was being created when the app ended, and the files of it still there; said once, then null. */
    var notCreated: CreationNote.Leftover? by mutableStateOf(null)

    fun open() {
        if (form == null) form = start()
    }

    /** Back, or the close button: the form and what was typed go. Not while the file is written. */
    fun close() {
        val shown = form ?: return
        if (shown.isWriting) return
        shown.close()
        form = null
    }

    /** The app locked: the form and what was typed go, as the vault's screens go; not while it is being created. */
    fun locked() {
        val shown = form ?: return
        if (shown.phase != NewDatabaseModel.Phase.Editing) return
        shown.close()
        form = null
    }

    /** Made and opened: the screen goes, the new database's shows. */
    fun created(model: NewDatabaseModel) {
        if (form === model) form = null
    }
}
