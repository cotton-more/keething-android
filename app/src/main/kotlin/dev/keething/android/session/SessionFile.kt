package dev.keething.android.session

import android.os.Environment
import dev.keething.android.databases.DatabaseText
import dev.keething.vault.databases.DatabaseSession
import java.io.File

/**
 * The database's file as the app's words name it ("There is no database at …", a save's failure): its path on the phone,
 * never opened by it (keething-spec databases.saf AC1).
 */
internal val DatabaseSession.file: File get() = File(DatabaseText.pathOf(location, Environment.getExternalStorageDirectory().path))
