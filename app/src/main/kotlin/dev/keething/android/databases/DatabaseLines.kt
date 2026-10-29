package dev.keething.android.databases

import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.Databases
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What the app knows of a database's files without opening them (SPEC §6.2): whether one is there, when it changed. */
internal interface DatabaseFiles {
    /** Null: nothing can be known (no all-files access: a file in shared storage looks gone). */
    fun exists(path: String): Boolean?

    fun modified(path: String): Instant?

    companion object {
        fun live(accessGranted: () -> Boolean): DatabaseFiles = object : DatabaseFiles {
            override fun exists(path: String): Boolean? = if (accessGranted()) File(path).isFile else null

            override fun modified(path: String): Instant? = File(path).lastModified().takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        }
    }
}

/** A database's key file as the lock screen and Settings say it. */
internal data class KeyFileLine(val name: String, val folder: String, val path: String, val isMissing: Boolean)

/** A database of the list as the lock screen's line, its sheet and Settings show it (the canvas, rows "Databases"). */
internal data class DatabaseLine(
    val id: DatabaseId,
    val name: String,
    val fileName: String,
    val path: String,
    /** "Sync/KeePass", from the shared storage's root. */
    val folder: String,
    /** After the folder: "changed 2 hours ago", "not found"; null when nothing is known. */
    val status: String?,
    val isMissing: Boolean,
    val keyFile: KeyFileLine?,
    val isCurrent: Boolean,
    val lastOpened: Instant?,
    val modified: Instant?,
) {
    /** The tile's letter. */
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"

    /** Under the name on the lock screen: "Sync/KeePass · changed 2 hours ago", "Download · not found". */
    val detail: String get() = listOfNotNull(folder, status).joinToString(" · ")

    /** Under its name in the lock screen's sheet: its folder, or that it is not found. */
    val sheetDetail: String get() = if (isMissing) "not found" else folder

    /** Under its name in Settings: "Sync/Work", "Not found · Download". */
    val settingsDetail: String get() = if (isMissing) "Not found · $folder" else folder
}

/** What the lock screen shows of its database (SPEC §6.2; the Mac's `LockScreenDatabase`). */
internal data class LockScreenDatabase(
    val line: DatabaseLine,
    /** Said in red before anything is typed: its key file is not there. */
    val problem: String?,
    /** No field for a database that opens with its key file alone, nor for one not found (Locate… instead). */
    val showsPasswordField: Boolean,
    val showsLocate: Boolean,
    /** In place of the field when the file is not found. */
    val missingText: String?,
    val passwordPlaceholder: String,
    /** Unlock needs something typed: without a key file nothing else could open it. */
    val needsPassword: Boolean,
) {
    /** Key File… under the field: none is set yet (not for a file not found: Locate… first). */
    val offersKeyFile: Boolean get() = line.keyFile == null && !line.isMissing

    /** Its key file opens it alone: Unlock without a field. */
    val opensWithKeyFileAlone: Boolean get() = !showsPasswordField && !showsLocate

    /**
     * A fingerprint may open it: not while its file or its key file is not there (it could not), nor while it opens
     * with its key file alone (there is no password for it to give; the fingerprint for that comes with plan AD5).
     */
    val offersFingerprint: Boolean get() = problem == null && showsPasswordField
}

/** The words for databases: their folders, when they changed (the Mac's `DatabaseText`). */
internal object DatabaseText {
    /** The folder a file is in, from the shared storage's [root] ("Sync/KeePass"); the root itself "Internal storage". */
    fun folder(path: String, root: String): String = abbreviated(File(path).parent ?: "/", root)

    /** A path from the shared storage's [root] (only the root itself, not a folder whose name starts like it). */
    fun abbreviated(path: String, root: String): String {
        val base = root.trimEnd('/')
        if (base.isEmpty()) return path
        if (path == base) return "Internal storage"
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else path
    }

    /** When a file changed, as a person says it: "changed 2 hours ago", "changed yesterday", "changed in March". */
    fun changed(date: Instant, now: Instant, zone: ZoneId): String = "changed " + ago(date, now, zone)

    /** "just now", "2 hours ago", "today", "yesterday", "3 days ago", "last week", "in March", "in November 2025". */
    fun ago(date: Instant, now: Instant, zone: ZoneId): String {
        val seconds = Duration.between(date, now).seconds
        if (seconds < 60) return "just now"
        if (seconds < 3600) return "${count(seconds / 60, "minute")} ago"
        if (seconds < 12 * 3600) return "${count(seconds / 3600, "hour")} ago"
        val day = date.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            days == 0L -> "today"
            days == 1L -> "yesterday"
            days < 7 -> "$days days ago"
            days < 14 -> "last week"
            else -> {
                val month = day.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.ENGLISH)
                if (day.year == today.year) "in $month" else "in $month ${day.year}"
            }
        }
    }

    private fun count(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"
}

/** The list's databases as the screens show them: in the list's order, the current one marked. */
internal fun Databases.lines(files: DatabaseFiles, root: String, now: Instant, zone: ZoneId): List<DatabaseLine> = records.map { record ->
    val missing = files.exists(record.path) == false
    val modified = if (missing) null else files.modified(record.path)
    DatabaseLine(
        id = record.id,
        name = record.displayName,
        fileName = record.fileName,
        path = record.path,
        folder = DatabaseText.folder(record.path, root),
        status = if (missing) "not found" else modified?.let { DatabaseText.changed(it, now, zone) },
        isMissing = missing,
        keyFile = record.keyFilePath?.let {
            KeyFileLine(File(it).name, DatabaseText.folder(it, root), it, isMissing = files.exists(it) == false)
        },
        isCurrent = record.id == currentId,
        lastOpened = record.lastOpened,
        modified = modified,
    )
}

/** What the lock screen shows of [line]'s database. */
internal fun DatabaseRecord.lockScreen(line: DatabaseLine): LockScreenDatabase {
    val key = line.keyFile
    val removable = path.startsWith("/storage/") && !path.startsWith("/storage/emulated/")
    return LockScreenDatabase(
        line = line,
        problem = if (!line.isMissing && key != null && key.isMissing) "${line.name} needs its key file: ${key.name} is not in ${key.folder}." else null,
        showsPasswordField = !line.isMissing && !isPasswordless,
        showsLocate = line.isMissing,
        missingText = when {
            !line.isMissing -> null
            removable -> "$fileName is not in ${line.folder}. Put the card in, or locate the file."
            else -> "$fileName is not in ${line.folder} any more. Locate the file, or choose another database."
        },
        passwordPlaceholder = if (keyFilePath == null) "Master password" else "Master password, if it has one",
        needsPassword = keyFilePath == null,
    )
}
