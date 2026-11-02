package dev.keething.android.storage

import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

// spec: kdbx.external-changes/AC1
class SharedFolderWatcherTest {
    private val android = OneWatchPerPath()
    private val watcher = SharedFolderWatcher(android)
    private val sync = File("/sdcard/Sync/test")

    @Test
    fun twoDatabasesInOneFolderBothHearAChange() {
        val heard = mutableListOf<String>()
        watcher.watch(sync) { heard += "a:$it" }
        watcher.watch(sync) { heard += "b:$it" }

        android.report(sync, "a.kdbx")

        assertEquals(listOf("a:a.kdbx", "b:a.kdbx"), heard)
        assertEquals(1, android.started)
    }

    @Test
    fun closingOneKeepsTheOtherHearing() {
        val heard = mutableListOf<String>()
        val first = watcher.watch(sync) { heard += "a:$it" }
        watcher.watch(sync) { heard += "b:$it" }

        first.close()
        first.close()
        android.report(sync, "b.kdbx")

        assertEquals(listOf("b:b.kdbx"), heard)
    }

    @Test
    fun closingTheLastStopsTheWatchAndAnotherStartsIt() {
        val first = watcher.watch(sync) {}
        val second = watcher.watch(sync) {}
        first.close()
        second.close()
        assertEquals(0, android.watching(sync))

        val heard = mutableListOf<String?>()
        watcher.watch(sync) { heard += it }
        android.report(sync, "a.kdbx")

        assertEquals(listOf<String?>("a.kdbx"), heard)
        assertEquals(2, android.started)
    }

    @Test
    fun aWatchStartedWhileTheLastOneOfItsFolderStopsKeepsHearing() {
        // Sessions start and close their watches off the main thread (databases.saf stage S3), so the two can meet.
        val first = watcher.watch(sync) {}
        val heard = mutableListOf<String?>()
        var second: Thread? = null
        android.beforeStop = {
            android.beforeStop = {}
            second = thread { watcher.watch(sync) { synchronized(heard) { heard += it } } }
            second.join(200)
        }

        first.close()
        second!!.join()
        android.report(sync, "a.kdbx")

        assertEquals(listOf<String?>("a.kdbx"), heard)
    }

    @Test
    fun eachFolderItsOwnWatch() {
        val heard = mutableListOf<String>()
        watcher.watch(sync) { heard += "sync:$it" }
        watcher.watch(File("/sdcard/Other")) { heard += "other:$it" }

        android.report(sync, "a.kdbx")

        assertEquals(listOf("sync:a.kdbx"), heard)
        assertEquals(2, android.started)
    }
}

/**
 * As Android's FileObserver: one inotify watch per path in the process, so a second observer of a folder takes
 * the first one's events, and stopping either stops the watch for both.
 */
private class OneWatchPerPath : FolderWatcher {
    private val observers = mutableMapOf<String, (String?) -> Unit>()
    var started = 0
        private set

    /** Runs as a watch begins to stop, before the path's watch is gone. */
    @Volatile var beforeStop: () -> Unit = {}

    override fun watch(folder: File, onEvent: (fileName: String?) -> Unit): AutoCloseable = synchronized(this) {
        started++
        observers[folder.path] = onEvent
        AutoCloseable {
            beforeStop()
            synchronized(this) { observers.remove(folder.path) }
        }
    }

    fun report(folder: File, fileName: String?) {
        synchronized(this) { observers[folder.path] }?.invoke(fileName)
    }

    fun watching(folder: File): Int = synchronized(this) { if (folder.path in observers) 1 else 0 }
}
