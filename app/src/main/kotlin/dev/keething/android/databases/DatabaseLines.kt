package dev.keething.android.databases

import dev.keething.search.SearchNormalizer
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
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
    val label: DatabaseLabel,
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
    /** Its own Use fingerprint (the fingerprint opens it while the app's switch is on too). */
    val usesFingerprint: Boolean = false,
    /** Its colour, an index into [DatabasePalette.shades]. */
    val colorIndex: Int = 0,
) {
    /** Its name, without what tells it apart from another named alike. */
    val name: String get() = label.name

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
     * A fingerprint may open it: not while its file or its key file is not there (it could not). One that opens with its
     * key file alone opens after the fingerprint with nothing stored.
     */
    val offersFingerprint: Boolean get() = problem == null && !line.isMissing
}

/** How a database is called where it is listed (keething-spec databases.name-colour AC3; the Mac's `DatabaseLabel`). */
internal data class DatabaseLabel(val name: String, val addition: String? = null) {
    /** "Personal · Personal-2024.kdbx". */
    val full: String get() = addition?.let { "$name · $it" } ?: name
}

/**
 * The colours of databases (keething-spec databases.name-colour AC4, AC5; the Mac's `DatabasePalette`): teal, orange,
 * green, purple, pink, indigo, brown, red, grey; each a dot and a text colour, light and dark. A file's own colour
 * (`Meta/Color`) is shown as the nearest of them: they are tuned for both themes, an arbitrary colour is not.
 */
internal object DatabasePalette {
    /** A colour's dot and its text (4.5:1), light · dark, as `0xRRGGBB`. */
    class Shade(val dotLight: Int, val dotDark: Int, val textLight: Int, val textDark: Int)

    val shades: List<Shade> = listOf(
        Shade(0x0E9AA7, 0x2CC4CF, 0x0A6A73, 0x5FD8E0),
        Shade(0xE07A00, 0xFF9F0A, 0x9E4800, 0xFFB340),
        Shade(0x2E9E4F, 0x3FBF63, 0x1A6E33, 0x6FD68A),
        Shade(0x8E5BD9, 0xA57EF0, 0x6338B5, 0xBFA3FF),
        Shade(0xD6458B, 0xF062A6, 0xA82A66, 0xFF8CC2),
        Shade(0x5856D6, 0x7D7AFF, 0x4341B0, 0xA5A3FF),
        Shade(0xA2845E, 0xB8946A, 0x6E5434, 0xD2B48C),
        Shade(0xD93A3A, 0xFF6961, 0xA82222, 0xFF8A84),
        Shade(0x8E8E93, 0x9A9BA3, 0x55565E, 0xB9BAC1),
    )
    private const val GREY = 8

    /**
     * Each database's colour over the whole list, from what its file says (`DatabaseRecord.color`, in the list's
     * order): a file's own colour as its nearest, also when two files share it (the user chose it); the others take
     * the colours no database has, in order, and once there are none left go round the palette by their place.
     */
    fun indices(fileColors: List<String?>): List<Int> {
        val result = fileColors.map { it?.let(::nearest) }.toMutableList()
        val taken = result.filterNotNull().toMutableSet()
        for (index in result.indices) {
            if (result[index] != null) continue
            val free = shades.indices.firstOrNull { it !in taken }
            result[index] = free ?: (index % shades.size)
            free?.let(taken::add)
        }
        return result.map { it ?: 0 }
    }

    /**
     * The palette's colour nearest to `#RRGGBB`; null for anything else. Nearest as the eye sees it (OKLab): its hue
     * first, its chroma capped (a vivid yellow is orange here, not far from all), its lightness weighing half; a colour
     * with hardly any chroma (black, white, a grey) is grey.
     */
    fun nearest(hex: String): Int? {
        val digits = hex.trim().removePrefix("#")
        if (digits.length != 6 || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val color = OkLch(digits.toInt(16))
        if (color.chroma < 0.035) return GREY
        var best = 0
        var bestDistance = Double.MAX_VALUE
        shades.forEachIndexed { index, shade ->
            for (reference in intArrayOf(shade.dotLight, shade.dotDark)) {
                val distance = color.distance(OkLch(reference))
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
        }
        return best
    }

    /** A colour in OKLab's lightness, chroma and hue (Björn Ottosson's matrices, from sRGB). */
    private class OkLch(rgb: Int) {
        val lightness: Double
        val chroma: Double
        val hue: Double

        init {
            fun linear(channel: Int): Double {
                val c = (channel and 0xFF) / 255.0
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            val r = linear(rgb shr 16)
            val g = linear(rgb shr 8)
            val b = linear(rgb)
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
            val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
            lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
            chroma = sqrt(a * a + bb * bb)
            hue = atan2(bb, a)
        }

        fun distance(other: OkLch): Double {
            val cap = 0.12
            val c1 = min(chroma, cap)
            val c2 = min(other.chroma, cap)
            val hueDifference = 2 * sqrt(c1 * c2) * sin((hue - other.hue) / 2)
            val lightnessDifference = 0.5 * (lightness - other.lightness)
            return sqrt(lightnessDifference * lightnessDifference + (c1 - c2) * (c1 - c2) + hueDifference * hueDifference)
        }
    }
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

    /**
     * Each database's label over the whole list (keething-spec databases.name-colour AC3), so one does not change when
     * another opens: its name, and when another has the same name (folded as the search folds: case and accents
     * aside), its file name, or with the file names alike too, its folder.
     */
    fun labels(records: List<DatabaseRecord>, root: String): Map<DatabaseId, DatabaseLabel> {
        val byName = records.groupBy { SearchNormalizer.normalize(it.displayName) }
        return records.associate { record ->
            val alike = byName.getValue(SearchNormalizer.normalize(record.displayName))
            val label = if (alike.size < 2) {
                DatabaseLabel(record.displayName)
            } else {
                val fileName = SearchNormalizer.normalize(record.fileName)
                val sameFile = alike.any { it.id != record.id && SearchNormalizer.normalize(it.fileName) == fileName }
                DatabaseLabel(record.displayName, if (sameFile) folder(record.path, root) else record.fileName)
            }
            record.id to label
        }
    }

    private fun count(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"
}

/** The list's databases as the screens show them: in the list's order, the current one marked. */
internal fun Databases.lines(files: DatabaseFiles, root: String, now: Instant, zone: ZoneId): List<DatabaseLine> {
    val labels = DatabaseText.labels(records, root)
    val colors = DatabasePalette.indices(records.map { it.color })
    return records.mapIndexed { index, record -> line(record, labels.getValue(record.id), colors[index], files, root, now, zone) }
}

private fun Databases.line(
    record: DatabaseRecord,
    label: DatabaseLabel,
    colorIndex: Int,
    files: DatabaseFiles,
    root: String,
    now: Instant,
    zone: ZoneId,
): DatabaseLine {
    val missing = files.exists(record.path) == false
    val modified = if (missing) null else files.modified(record.path)
    return DatabaseLine(
        id = record.id,
        label = label,
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
        usesFingerprint = record.useBiometric,
        colorIndex = colorIndex,
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
