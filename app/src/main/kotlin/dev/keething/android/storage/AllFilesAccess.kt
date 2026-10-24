package dev.keething.android.storage

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri

/** The all-files access the side-loaded build reaches the database with (SPEC §3.1), granted in Settings. */
internal object AllFilesAccess {
    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
}
