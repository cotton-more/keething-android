package dev.keething.android.storage

import android.util.Log
import dev.keething.domain.storage.FolderRead
import dev.keething.domain.storage.FolderReading
import dev.keething.domain.storage.GrantedFolder
import java.io.File
import java.io.InputStream

/**
 * Debug builds only: [folder] with a pause before one of its changes, so the process can be killed between a save's
 * steps (keething-spec databases.saf AC4, AC5) or while a new database is written (AC11) and what the next start does
 * with it seen on the phone; or before a read, so the database can be locked while a save waits at its first step
 * (kdbx.hardening AC3). A marker file `files/debug-pause` holding `<call> [text]` (`read`, `rename`, `delete`, `create`
 * or `write`; the text a part of the path the call is given) pauses the first such call for two minutes and removes the
 * marker: `adb shell run-as dev.keething.android sh -c 'echo "rename .syncthing" > files/debug-pause'`. The log says
 * when it pauses (tag KeethingPause). A release build never makes one.
 */
internal class PausingFolder(private val folder: GrantedFolder, private val marker: File) : GrantedFolder by folder {
    override fun read(path: String): FolderRead? {
        pauseBefore("read", path)
        return folder.read(path)
    }

    override fun <T> read(path: String, reading: (InputStream) -> T): FolderReading<T>? {
        pauseBefore("read", path)
        return folder.read(path, reading)
    }

    override fun create(folder: String, name: String): String {
        pauseBefore("create", "$folder/$name")
        return this.folder.create(folder, name)
    }

    override fun write(path: String, bytes: ByteArray) {
        pauseBefore("write", path)
        folder.write(path, bytes)
    }

    override fun rename(path: String, name: String): String {
        pauseBefore("rename", path)
        return folder.rename(path, name)
    }

    override fun delete(path: String): Boolean {
        pauseBefore("delete", path)
        return folder.delete(path)
    }

    private fun pauseBefore(call: String, path: String) {
        val wanted = synchronized(marker) {
            val line = runCatching { marker.readText().trim() }.getOrNull() ?: return
            val words = line.split(' ', limit = 2)
            if (words[0] != call || (words.size == 2 && words[1] !in path)) return
            marker.delete()
            line
        }
        Log.w(TAG, "paused before $call $path ($wanted) for $PAUSE_SECONDS s")
        Thread.sleep(PAUSE_SECONDS * 1000L)
        Log.w(TAG, "going on with $call $path")
    }

    private companion object {
        const val TAG = "KeethingPause"
        const val PAUSE_SECONDS = 120
    }
}
