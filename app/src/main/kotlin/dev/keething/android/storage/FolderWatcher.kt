package dev.keething.android.storage

import java.io.File

/** Reports changes among the files of one folder: created, written, deleted, renamed. */
internal fun interface FolderWatcher {
    /**
     * Calls [onEvent] on a background thread with the name of the file that changed (null when the event
     * names none) until the returned handle is closed.
     */
    fun watch(folder: File, onEvent: (fileName: String?) -> Unit): AutoCloseable
}
