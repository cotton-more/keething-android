package dev.keething.android.storage

import android.os.FileObserver
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [FolderWatcher] on inotify. Syncthing replaces a file by renaming its download over it (MOVED_TO).
 *
 * Shared storage is a FUSE file system, and right after an event `stat` can still show the file as it was
 * (measured on Android 17: stale at the event, fresh 0.3 s later). So every event is reported again once
 * the folder has had [SETTLE_MILLIS] to settle.
 */
internal class FileObserverFolderWatcher : FolderWatcher {
    override fun watch(folder: File, onEvent: (fileName: String?) -> Unit): AutoCloseable {
        val settled = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "folder-watcher-settle").apply { isDaemon = true }
        }
        val observer = object : FileObserver(folder, EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                Log.d(TAG, "event 0x${Integer.toHexString(event)} $path")
                onEvent(path)
                settled.schedule({ onEvent(path) }, SETTLE_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
        observer.startWatching()
        // The handle keeps the observer reachable: a collected FileObserver stops watching.
        return AutoCloseable {
            observer.stopWatching()
            settled.shutdownNow()
        }
    }

    private companion object {
        const val TAG = "FolderWatcher"
        const val SETTLE_MILLIS = 1_000L
        const val EVENTS = FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.ATTRIB or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }
}
