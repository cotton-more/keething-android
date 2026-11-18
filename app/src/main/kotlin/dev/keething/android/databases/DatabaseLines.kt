package dev.keething.android.databases

import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiListed
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiPlural
import dev.keething.android.ui.uiText
import dev.keething.domain.storage.StoragePlace
import dev.keething.search.SearchNormalizer
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.Databases
import dev.keething.vault.databases.FileLocation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/** Whether the app can reach a file of the list now (SPEC §6.2; keething-spec databases.saf AC10, AC13). */
internal enum class Reach {
    THERE,
    NOT_FOUND,

    /** Its grant is gone: the folder renamed, moved or deleted on any device, a key file's own grant ended by a sync. */
    NO_ACCESS,

    /** Kept by its path before databases.saf: its folder is asked for once (AC13). */
    NEEDS_FOLDER,

    /** Nothing can be known now (the provider failed otherwise): its read will say. */
    UNKNOWN,
}

/** What the app knows of a file of the list without opening it: whether it can be reached, when it changed. */
internal data class FileLook(val reach: Reach, val modified: Instant? = null)

/**
 * What the app knows of a database's files without opening them (SPEC §6.2), through their grants. Blocks on the
 * storage's provider: call it off the main thread.
 */
internal fun interface DatabaseFiles {
    /** [location]'s file, reached through a grant (one kept by its path is never looked at: [Reach.NEEDS_FOLDER]). */
    fun look(location: FileLocation): FileLook
}

/**
 * Every file of [databases] reached through a grant looked at side by side, a few at a time on the io dispatcher (each
 * is a call to the storage provider): the lines then read what was found.
 */
internal suspend fun DatabaseFiles.lookedAt(databases: Databases): DatabaseFiles {
    val locations = databases.records.flatMap { listOfNotNull(it.file, it.keyFile) }.filterNot { it is FileLocation.ByPath }.distinct()
    val found = withContext(LOOKING) { locations.map { async { it to look(it) } }.awaitAll().toMap() }
    return DatabaseFiles { found[it] ?: look(it) }
}

/** At most this many files looked at at once. */
@OptIn(ExperimentalCoroutinesApi::class)
private val LOOKING = Dispatchers.IO.limitedParallelism(8)

/** A database's key file as the lock screen and Settings say it; [reach]: anything but there is said in red, with Choose…. */
internal data class KeyFileLine(val name: String, val folder: String, val path: String, val reach: Reach = Reach.THERE) {
    /** Not there, or not to be read any more: Choose key file… (keething-spec databases.saf AC10). */
    val isMissing: Boolean get() = reach == Reach.NOT_FOUND || reach == Reach.NO_ACCESS || reach == Reach.NEEDS_FOLDER

    /** After its name: its folder, or why it cannot be used ("not there · Keys", "no access · Keys"). */
    val detail: UiText
        get() = when (reach) {
            Reach.NOT_FOUND -> uiText(R.string.databases_key_file_detail_not_there, folder)
            Reach.NO_ACCESS, Reach.NEEDS_FOLDER -> uiText(R.string.databases_key_file_detail_no_access, folder)
            Reach.THERE, Reach.UNKNOWN -> uiPlain(folder)
        }
}

/** A database of the list as the lock screen's line, its sheet and Settings show it (the canvas, rows "Databases"). */
internal data class DatabaseLine(
    val id: DatabaseId,
    val label: DatabaseLabel,
    val fileName: String,
    val path: String,
    /** "Sync/KeePass", from the shared storage's root. */
    val folder: String,
    /** After the folder: "changed 2 hours ago", "not found"; null when nothing is known. */
    val status: UiText?,
    /** Whether its file can be reached now: anything but there or unknown is said, with Locate… or its folder's question. */
    val reach: Reach,
    val keyFile: KeyFileLine?,
    val isCurrent: Boolean,
    val lastOpened: Instant?,
    val modified: Instant?,
    /** Its own Use fingerprint (the fingerprint opens it while the app's switch is on too). */
    val usesFingerprint: Boolean = false,
    /** Its colour, an index into [DatabasePalette.shades]. */
    val colorIndex: Int = 0,
) {
    /** Its file cannot be reached now: not found, no access any more, or its folder not asked for yet (Locate… or Choose its folder…). */
    val isMissing: Boolean get() = reach == Reach.NOT_FOUND || reach == Reach.NO_ACCESS || reach == Reach.NEEDS_FOLDER

    /** What is said of a file that cannot be reached: "not found", "no access", "needs its folder". */
    val reachWord: UiText?
        get() = when (reach) {
            Reach.NOT_FOUND -> uiText(R.string.databases_reach_not_found)
            Reach.NO_ACCESS -> uiText(R.string.databases_reach_no_access)
            Reach.NEEDS_FOLDER -> uiText(R.string.databases_reach_needs_folder)
            Reach.THERE, Reach.UNKNOWN -> null
        }

    /** The same said on its own, as a row's line: "Not found", "No access", "Needs its folder". */
    val reachTitle: UiText?
        get() = when (reach) {
            Reach.NOT_FOUND -> uiText(R.string.databases_reach_not_found_title)
            Reach.NO_ACCESS -> uiText(R.string.databases_reach_no_access_title)
            Reach.NEEDS_FOLDER -> uiText(R.string.databases_reach_needs_folder_title)
            Reach.THERE, Reach.UNKNOWN -> null
        }

    /** Its name, without what tells it apart from another named alike. */
    val name: String get() = label.name

    /** The tile's letter. */
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"

    /** Under the name on the lock screen: "Sync/KeePass · changed 2 hours ago", "Download · not found". */
    val detail: UiText get() = status?.let { uiText(R.string.databases_line_detail, folder, it) } ?: uiPlain(folder)

    /** Under its name in the lock screen's sheet: its folder, or that it is not found ("no access", "needs its folder"). */
    val sheetDetail: UiText get() = reachWord ?: uiPlain(folder)

    /** Under its name in Settings: "Sync/Work", "Not found · Download". */
    val settingsDetail: UiText get() = reachTitle?.let { uiText(R.string.databases_line_settings_detail, it, folder) } ?: uiPlain(folder)
}

/** What the lock screen shows of its database (SPEC §6.2; the Mac's `LockScreenDatabase`). */
internal data class LockScreenDatabase(
    val line: DatabaseLine,
    /** Said in red before anything is typed: its key file is not there. */
    val problem: UiText?,
    /** No field for a database that opens with its key file alone, nor for one not found (Locate… instead). */
    val showsPasswordField: Boolean,
    val showsLocate: Boolean,
    /** In place of the field when the file cannot be reached. */
    val missingText: UiText?,
    /** The button in place of the field: "Locate…", or for a database kept by its path "Choose its folder…" (AC13). */
    val locateLabel: UiText,
    val passwordPlaceholder: UiText,
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
 * How rows, cards and the header say a database while two or more are open (keething-spec databases.name-colour AC6;
 * the Mac's `DatabaseMark`): its label and colour over the whole list, so neither changes when another opens.
 */
internal data class DatabaseMark(val id: DatabaseId, val label: DatabaseLabel, val colorIndex: Int)

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

    /**
     * Where [location] is, as a path on the phone, for the app's words alone: its own path (kept by it before
     * databases.saf), else its place on the storage under the shared storage's [root] (a card's under `/storage`). The
     * file is never reached by it (keething-spec databases.saf AC1).
     */
    fun pathOf(location: FileLocation, root: String): String {
        if (location is FileLocation.ByPath) return location.path
        return location.place?.let { pathOf(it, root) } ?: location.fileName
    }

    /** [place] as a path on the phone, for the words: the phone's own storage under [root], a card's under `/storage`. */
    fun pathOf(place: StoragePlace, root: String): String {
        val top = if (place.volume == StoragePlace.PRIMARY) root.trimEnd('/') else "/storage/${place.volume}"
        return if (place.path.isEmpty()) top else "$top/${place.path}"
    }

    /** A folder's place as the screens say it: "Sync/KeePass", "Internal storage", "/storage/1234-5678/Keys". */
    fun words(place: StoragePlace, root: String): String = abbreviated(pathOf(place, root), root)

    /** A path from the shared storage's [root] (only the root itself, not a folder whose name starts like it). */
    fun abbreviated(path: String, root: String): String {
        val base = root.trimEnd('/')
        if (base.isEmpty()) return path
        if (path == base) return INTERNAL_STORAGE
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else path
    }

    /** When a file changed, as a person says it: "changed 2 hours ago", "changed yesterday", "changed in March". */
    fun changed(date: Instant, now: Instant, zone: ZoneId): UiText = uiText(R.string.databases_changed_ago, ago(date, now, zone))

    /** "just now", "2 hours ago", "today", "yesterday", "3 days ago", "last week", "in March", "in November 2025". */
    fun ago(date: Instant, now: Instant, zone: ZoneId): UiText {
        val seconds = Duration.between(date, now).seconds
        if (seconds < 60) return uiText(R.string.databases_ago_just_now)
        if (seconds < 3600) return (seconds / 60).toInt().let { uiPlural(R.plurals.databases_ago_minutes, it) }
        if (seconds < 12 * 3600) return (seconds / 3600).toInt().let { uiPlural(R.plurals.databases_ago_hours, it) }
        val day = date.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            days == 0L -> uiText(R.string.databases_ago_today)
            days == 1L -> uiText(R.string.databases_ago_yesterday)
            days < 7 -> uiPlural(R.plurals.databases_ago_days, days.toInt())
            days < 14 -> uiText(R.string.databases_ago_last_week)
            else -> {
                val month = day.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.ENGLISH)
                if (day.year == today.year) uiText(R.string.databases_ago_in_month, month) else uiText(R.string.databases_ago_in_month_year, month, day.year)
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
                DatabaseLabel(record.displayName, if (sameFile) folder(pathOf(record.file, root), root) else record.fileName)
            }
            record.id to label
        }
    }

    /** "Personal", "Personal and Work", "Personal, Work and Family Shared". */
    fun joined(names: List<String>): UiText = uiListed(names.map(::uiPlain))

    /**
     * The shared storage's root in words. TODO(T14): still English; [abbreviated] gives a String used as a database's
     * label and folder across the app, which a resource cannot reach without changing all of them.
     */
    private const val INTERNAL_STORAGE = "Internal storage" // not user text: see INTERNAL_STORAGE's KDoc (T14)
}

/** Every database's mark, over the whole list. */
internal fun Databases.marks(root: String): Map<DatabaseId, DatabaseMark> {
    val labels = DatabaseText.labels(records, root)
    val colors = DatabasePalette.indices(records.map { it.color })
    return records.withIndex().associate { (index, record) -> record.id to DatabaseMark(record.id, labels.getValue(record.id), colors[index]) }
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
    val look = look(record.file, files)
    val path = DatabaseText.pathOf(record.file, root)
    return DatabaseLine(
        id = record.id,
        label = label,
        fileName = record.fileName,
        path = path,
        folder = DatabaseText.folder(path, root),
        status = when (look.reach) {
            Reach.THERE, Reach.UNKNOWN -> look.modified?.let { DatabaseText.changed(it, now, zone) }
            Reach.NOT_FOUND -> uiText(R.string.databases_reach_not_found)
            Reach.NO_ACCESS -> uiText(R.string.databases_reach_no_access)
            Reach.NEEDS_FOLDER -> uiText(R.string.databases_reach_needs_folder)
        },
        reach = look.reach,
        keyFile = record.keyFile?.let { keyFile ->
            val keyPath = DatabaseText.pathOf(keyFile, root)
            // One kept by its path: Choose key file… (AC10), unless the database's own folder, asked first, holds it.
            val keyLook = if (keyFile is FileLocation.ByPath) FileLook(Reach.NO_ACCESS) else files.look(keyFile)
            KeyFileLine(keyFile.fileName, DatabaseText.folder(keyPath, root), keyPath, keyLook.reach)
        },
        modified = look.modified,
        isCurrent = record.id == currentId,
        lastOpened = record.lastOpened,
        usesFingerprint = record.useBiometric,
        colorIndex = colorIndex,
    )
}

/** [file] as the app can reach it now: one kept by its path needs its folder first, and is not looked at (AC1, AC13). */
private fun look(file: FileLocation, files: DatabaseFiles): FileLook =
    if (file is FileLocation.ByPath) FileLook(Reach.NEEDS_FOLDER) else files.look(file)

/** What the lock screen shows of [line]'s database. */
internal fun DatabaseRecord.lockScreen(line: DatabaseLine): LockScreenDatabase {
    val key = line.keyFile
    val removable = file.place?.let { it.volume != StoragePlace.PRIMARY } == true
    return LockScreenDatabase(
        line = line,
        problem = when {
            line.isMissing || key == null || !key.isMissing -> null
            key.reach == Reach.NOT_FOUND -> uiText(R.string.databases_lock_key_file_not_there, line.name, key.name, key.folder)
            else -> uiText(R.string.databases_lock_key_file_no_access, key.name)
        },
        showsPasswordField = !line.isMissing && !isPasswordless,
        showsLocate = line.isMissing,
        missingText = when (line.reach) {
            Reach.THERE, Reach.UNKNOWN -> null
            Reach.NEEDS_FOLDER -> uiText(R.string.databases_lock_needs_folder, line.folder, fileName)
            Reach.NO_ACCESS -> uiText(R.string.databases_lock_no_access, line.folder)
            Reach.NOT_FOUND -> uiText(if (removable) R.string.databases_lock_not_on_card else R.string.databases_lock_not_found, fileName, line.folder)
        },
        locateLabel = uiText(if (line.reach == Reach.NEEDS_FOLDER) R.string.databases_lock_choose_folder else R.string.databases_lock_locate),
        passwordPlaceholder = uiText(R.string.databases_master_password),
        needsPassword = keyFile == null,
    )
}
