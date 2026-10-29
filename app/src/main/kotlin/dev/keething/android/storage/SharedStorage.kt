package dev.keething.android.storage

import android.os.Environment
import java.io.File

/** The phone's shared storage (`/storage/…`, `/sdcard`): a FUSE file system with no permissions of its own to set. */
internal object SharedStorage {
    fun contains(file: File): Boolean {
        val path = file.absolutePath
        val primary = Environment.getExternalStorageDirectory().absolutePath
        return path == primary || path.startsWith("$primary/") || path.startsWith("/storage/") || path.startsWith("/sdcard/")
    }
}
