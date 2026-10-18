package dev.keepassbar.android.storageprobe

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

/** Remembers the probed database by its path, as the app will (SPEC §3.1). */
internal class ProbeSettings(private val preferences: SharedPreferences) {
    var databasePath: File?
        get() = preferences.getString(DATABASE_PATH, null)?.let(::File)
        set(value) = preferences.edit { putString(DATABASE_PATH, value?.path) }

    private companion object {
        const val DATABASE_PATH = "databasePath"
    }
}
