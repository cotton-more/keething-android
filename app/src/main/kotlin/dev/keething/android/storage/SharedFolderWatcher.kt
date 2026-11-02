package dev.keething.android.storage

import java.io.File

/**
 * One watch of [single] per folder, shared by every database in it (keething-spec kdbx.external-changes AC1).
 * Android's FileObserver keeps one inotify watch per path in the process: a second observer of a folder takes
 * the first one's events, and stopping either stops both, so two databases in one folder each watching it would
 * leave only the one opened last noticing a change. The watch stops when its last database stops watching.
 */
internal class SharedFolderWatcher(private val single: FolderWatcher) : FolderWatcher {
    private val lock = Any()
    private val folders = mutableMapOf<String, Shared>()

    private class Shared {
        val listeners = mutableListOf<(String?) -> Unit>()
        var handle: AutoCloseable? = null
    }

    override fun watch(folder: File, onEvent: (fileName: String?) -> Unit): AutoCloseable {
        val key = folder.absoluteFile.normalize().path
        synchronized(lock) {
            val shared = folders.getOrPut(key) { Shared() }
            shared.listeners += onEvent
            if (shared.handle == null) {
                shared.handle = single.watch(folder) { fileName ->
                    synchronized(lock) { shared.listeners.toList() }.forEach { it(fileName) }
                }
            }
        }
        var closed = false
        return AutoCloseable {
            synchronized(lock) {
                if (closed) return@AutoCloseable
                closed = true
                val shared = folders.getValue(key)
                shared.listeners.remove(onEvent)
                if (shared.listeners.isNotEmpty()) return@AutoCloseable
                folders.remove(key)
                // Stopped under the lock: a watch of the folder started meanwhile would be stopped with it (one per path).
                shared.handle?.close()
            }
        }
    }
}
