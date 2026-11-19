package dev.keething.vault.databases

import dev.keething.domain.settings.SettingsStore
import dev.keething.domain.storage.StoragePlace
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A database's id in the list (SPEC §3.1; Mac `DatabaseID`): given when it joins the list, never the file's (a KDBX
 * header has none that lasts, and a copy keeps every UUID). It names the database's backups' folder.
 */
public data class DatabaseId(val value: String) {
    /**
     * The id as a file or folder name: itself when it is a plain one (letters, digits, `-`), else its bytes in hex, so
     * a hand-edited setting can never name a path outside the folder it is meant for.
     */
    public val fileName: String
        get() = if (value.isNotEmpty() && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }) {
            value
        } else {
            "id-" + value.encodeToByteArray().joinToString("") { "%02x".format(it) }
        }

    override fun toString(): String = value

    public companion object {
        public fun make(): DatabaseId = DatabaseId(UUID.randomUUID().toString().uppercase())
    }
}

/**
 * A database of the list (SPEC §3.1; Mac `DatabaseRecord`): what this phone remembers of it. Nothing secret: where its
 * files are, and choices.
 */
public data class DatabaseRecord(
    val id: DatabaseId,
    /** Where the database is (keething-spec databases.saf AC2, AC13). */
    val file: FileLocation,
    /** Remembered by where it is, not its contents: read at every unlock (keething-spec kdbx.key-files AC8, databases.saf AC9). */
    val keyFile: FileLocation? = null,
    /** The file's own name (`Meta/DatabaseName`) as last read; null: the file name says it. */
    val name: String? = null,
    /** The file's own colour (`Meta/Color`) as last read, `#RRGGBB`; null: one of the palette's. */
    val color: String? = null,
    /** Its password is stored for the fingerprint at a typed unlock (a fingerprint per database, plan AD5). */
    val useBiometric: Boolean = false,
    /**
     * The fingerprint was chosen for it, or not: the lock screen's question answered, or its switch set on its page. The
     * lock screen asks only a database never chosen (keething-spec unlock.biometric AC4).
     */
    val fingerprintAsked: Boolean = false,
    /** The last unlock opened it with its key file and nothing typed: the lock screen shows no password field. */
    val opensWithoutPassword: Boolean = false,
    val lastOpened: Instant? = null,
    /** One of the databases shown side by side (several open, plan AD8). */
    val inWindow: Boolean = false,
    /** Checked in the chooser: every one until the user unchecks it, kept at once (keething-spec databases.chooser AC9). */
    val checkedInChooser: Boolean = true,
    /** Its switch "Back up before changes": the file is backed up before a save (keething-spec databases.backups-window AC2). */
    val backupsOn: Boolean = true,
    /** How many of its backups are kept, one of [BACKUPS_KEPT] (keething-spec databases.backups-window AC2). */
    val backupsKept: Int = DEFAULT_BACKUPS_KEPT,
) {
    public val fileName: String get() = file.fileName

    /** The name it goes by: its own, else the file name without `.kdbx`. */
    public val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: fileName.let { if (it.lowercase().endsWith(".kdbx")) it.dropLast(5) else it }

    /** Opens with its key file alone: nothing to type, nothing for the fingerprint to store. */
    public val isPasswordless: Boolean get() = keyFile != null && opensWithoutPassword

    public companion object {
        /** The numbers of backups a database can keep (keething-spec databases.backups-window AC2). */
        public val BACKUPS_KEPT: List<Int> = listOf(5, 10, 20, 50)

        public const val DEFAULT_BACKUPS_KEPT: Int = 10

        /**
         * KeePassXC fills in "Passwords" as every new database's name (its wizard; here in the languages of the
         * keyboard layouts the Mac knows), so it says nothing of which database it is (folded: case and accents aside).
         */
        private val defaultNames = setOf("passwords", "passworter", "пароли")
        private val marks = Regex("\\p{M}+")

        /**
         * The name a file's `Meta/DatabaseName` gives its database (keething-spec databases.name-colour AC1): itself, its
         * outer spaces aside; null for none, or KeePassXC's default (the file name then says it).
         */
        public fun nameFromFile(name: String): String? {
            val trimmed = name.trim()
            val folded = marks.replace(Normalizer.normalize(trimmed.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
            return if (trimmed.isEmpty() || folded in defaultNames) null else trimmed
        }

        /**
         * The colour a file's `Meta/Color` gives its database: `#RRGGBB` (KeePass 2.x and KeePassDX write it so; the `#`
         * may be left out), upper-case; null for anything else.
         */
        public fun colorFromFile(color: String?): String? {
            val hex = color?.trim()?.removePrefix("#") ?: return null
            return if (hex.length == 6 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) "#" + hex.uppercase(Locale.ROOT) else null
        }
    }
}

/** The list's databases and the current one: the one the lock screen shows, always one of the window's. */
public data class Databases(val records: List<DatabaseRecord>, val currentId: DatabaseId?) {
    public val current: DatabaseRecord? get() = currentId?.let(::record)

    /** The databases side by side, each with its session (plan AD8), in the list's order. */
    public val window: List<DatabaseRecord> get() = records.filter { it.inWindow }

    public fun record(id: DatabaseId): DatabaseRecord? = records.firstOrNull { it.id == id }
}

/**
 * The databases this phone knows (SPEC §3.1; Mac `DatabaseList`), kept as JSON under [STORE_KEY] in the settings:
 * `{"current": <id>, "databases": [record…], "version": 1}`. The list is the phone's own and never synced.
 *
 * It starts empty: there is no default path, and the one database of before the list is not carried over — it is
 * opened again from the welcome (plan AD4). An unreadable stored list is kept under another key, never overwritten,
 * and the list starts empty, [setAside] until a database joins it; one of a version newer than [VERSION] is read as far
 * as this version understands it, and kept as it was under another key too (keething-spec app.upgrade-safety AC3).
 * Call it on the main thread.
 */
public class DatabaseList(
    private val store: SettingsStore,
    private val makeId: () -> DatabaseId = DatabaseId::make,
    now: () -> Instant = Instant::now,
) {
    private val mutableState: MutableStateFlow<Databases>

    public val state: StateFlow<Databases>

    init {
        val raw = store.string(STORE_KEY)
        val stored = raw?.let(::decode)
        if (raw != null && stored != null && stored.second > VERSION) store.setString("$STORE_KEY.version${stored.second}.${now().epochSecond}", raw)
        if (raw != null && stored == null) {
            store.setString("$STORE_KEY.unreadable.${now().epochSecond}", raw)
            store.setString(SET_ASIDE_KEY, now().epochSecond.toString())
        }
        mutableState = MutableStateFlow(stored?.first ?: Databases(emptyList(), null))
        state = mutableState.asStateFlow()
        if (stored == null) save()
    }

    /**
     * The list kept before could not be read and was set aside, and no database has joined the list since: the welcome
     * says so (keething-spec app.upgrade-safety AC3).
     */
    public val setAside: Boolean get() = records.isEmpty() && store.string(SET_ASIDE_KEY) != null

    private val observers = mutableListOf<() -> Unit>()

    public val records: List<DatabaseRecord> get() = state.value.records
    public val currentId: DatabaseId? get() = state.value.currentId
    public val current: DatabaseRecord? get() = state.value.current
    public val window: List<DatabaseRecord> get() = state.value.window

    public fun record(id: DatabaseId): DatabaseRecord? = state.value.record(id)

    /**
     * Calls [handler] after every change, at once, with the list already changed (the sessions follow it before anything
     * reads them). A change made by a handler tells every handler again, from within.
     */
    public fun observe(handler: () -> Unit) {
        observers += handler
    }

    /**
     * The record of [file]: the one there is (the same file, however it is reached: [FileLocation.isSameFile]), or a new
     * one at the end of the list. The first one becomes current.
     */
    public fun add(file: FileLocation, useBiometric: Boolean = false): DatabaseRecord {
        recordOf(file)?.let { return it }
        val record = DatabaseRecord(makeId(), file, useBiometric = useBiometric)
        change(Databases(records + record, currentId ?: record.id))
        return record
    }

    /**
     * A new database at [file] with its [keyFile], at the end of the list, in place of the record of that file there was
     * (another database whose file is gone): one change, so nothing that follows the list sees the file's grant unused
     * in between (keething-spec databases.saf AC14). The removed one current, the new one is. The removed record, or null.
     */
    public fun replace(
        file: FileLocation,
        keyFile: FileLocation?,
        useBiometric: Boolean,
        fingerprintAsked: Boolean = false,
    ): Pair<DatabaseRecord, DatabaseRecord?> {
        val old = recordOf(file)
        val record = DatabaseRecord(makeId(), file, keyFile, useBiometric = useBiometric, fingerprintAsked = fingerprintAsked)
        val left = records.filter { it.id != old?.id }
        change(Databases(left + record, currentId?.takeIf { id -> left.any { it.id == id } } ?: record.id))
        return record to old
    }

    /** The database of the list that is [file], however it is reached; null for none. */
    public fun recordOf(file: FileLocation): DatabaseRecord? = records.firstOrNull { it.file.isSameFile(file) }

    /** The grants the list's databases and key files are reached through (keething-spec databases.saf AC14). */
    public val grantsInUse: Set<String> get() = records.flatMap { listOfNotNull(it.file.grant, it.keyFile?.grant) }.toSet()

    /**
     * [place] reached through a folder the list's databases or key files are already reached through, which holds it
     * (keething-spec databases.saf AC3, AC9: nothing more is asked for): [prefer]'s folder when it holds it (a database's
     * own, for its key file), else the first in the list's order. Only through a grant the app still [held] (null: every
     * one counts): one gone, its folder renamed or moved away, is no way in (AC10). Null when none holds it.
     */
    public fun inGrantedFolder(place: StoragePlace, prefer: FileLocation? = null, held: Set<String>? = null): FileLocation.InFolder? {
        val folders = listOfNotNull(prefer) + records.flatMap { listOfNotNull(it.file, it.keyFile) }
        for (folder in folders.filterIsInstance<FileLocation.InFolder>()) {
            if (held != null && folder.grant !in held) continue
            val path = place.inside(folder.folder) ?: continue
            if (path.isNotEmpty()) return FileLocation.InFolder(folder.grant, folder.folder, path)
        }
        return null
    }

    /**
     * Out of the list; the file stays. The current one removed: the window's first left becomes current (one the user
     * closed stays closed), else the list's first (keething-spec databases.list AC6).
     */
    public fun remove(id: DatabaseId) {
        if (record(id) == null) return
        val left = records.filter { it.id != id }
        val next = if (currentId == id) (left.firstOrNull { it.inWindow } ?: left.firstOrNull())?.id else currentId
        change(Databases(left, next))
    }

    public fun update(id: DatabaseId, change: (DatabaseRecord) -> DatabaseRecord) {
        val before = record(id) ?: return
        val after = change(before).copy(id = id)
        if (after == before) return
        change(Databases(records.map { if (it.id == id) after else it }, currentId))
    }

    /** Only a database of the list. */
    public fun setCurrent(id: DatabaseId) {
        if (currentId == id || record(id) == null) return
        change(Databases(records, id))
    }

    /** Into the window or out of it; only a database of the list. */
    public fun setInWindow(id: DatabaseId, inWindow: Boolean) {
        update(id) { it.copy(inWindow = inWindow) }
    }

    private fun change(databases: Databases) {
        mutableState.value = databases
        if (databases.records.isNotEmpty()) store.setString(SET_ASIDE_KEY, null)
        save()
        observers.toList().forEach { it() }
    }

    private fun save() {
        val databases = state.value
        val root = buildMap {
            put("version", VERSION)
            databases.currentId?.let { put("current", it.value) }
            put("databases", databases.records.map(::encode))
        }
        store.setString(STORE_KEY, Json.write(root))
    }

    public companion object {
        public const val STORE_KEY: String = "databases"

        /** The version of the list's JSON this app writes; a newer one is kept as it was besides (keething-spec app.upgrade-safety AC3). */
        public const val VERSION: Int = 1

        /** When the list kept before was set aside, unreadable; gone once a database joins the list. */
        private const val SET_ASIDE_KEY = "$STORE_KEY.setAside" // not user text

        /**
         * A database's file or key file: by path as before databases.saf (`"path"`, `"keyFilePath"`: the list of before
         * reads as it is, AC13), else an object under `"file"` or `"keyFile"`: a granted folder's address, the folder's
         * place on the storage and the path inside it (`grant`, `volume`, `folder`, `path`), or a file granted alone
         * (`grant`, `volume`, `place`).
         */
        private fun MutableMap<String, Any>.putLocation(pathKey: String, objectKey: String, location: FileLocation?) {
            when (location) {
                null -> Unit
                is FileLocation.ByPath -> put(pathKey, location.path)
                is FileLocation.InFolder -> put(
                    objectKey,
                    mapOf("grant" to location.grant, "volume" to location.folder.volume, "folder" to location.folder.path, "path" to location.path),
                )
                is FileLocation.Granted -> put(objectKey, mapOf("grant" to location.grant, "volume" to location.at.volume, "place" to location.at.path))
            }
        }

        private fun Map<*, *>.location(pathKey: String, objectKey: String): FileLocation? {
            val located = field<Map<*, *>>(objectKey)?.let { map ->
                val grant = map.field<String>("grant") ?: throw Unreadable()
                val volume = map.field<String>("volume") ?: throw Unreadable()
                val folder = map.field<String>("folder")
                if (folder != null) {
                    FileLocation.InFolder(grant, StoragePlace(volume, folder), map.field<String>("path")?.takeIf { it.isNotEmpty() } ?: throw Unreadable())
                } else {
                    FileLocation.Granted(grant, StoragePlace(volume, map.field<String>("place") ?: throw Unreadable()))
                }
            }
            return located ?: field<String>(pathKey)?.let(FileLocation::ByPath)
        }

        private fun encode(record: DatabaseRecord): Map<String, Any> = buildMap {
            put("id", record.id.value)
            putLocation("path", "file", record.file)
            putLocation("keyFilePath", "keyFile", record.keyFile)
            record.name?.let { put("name", it) }
            record.color?.let { put("color", it) }
            put("useBiometric", record.useBiometric)
            // Only once chosen: a list of before is written back as it was.
            if (record.fingerprintAsked) put("fingerprintAsked", true)
            put("opensWithoutPassword", record.opensWithoutPassword)
            // Seconds since 1970, as the Mac's list keeps its dates.
            record.lastOpened?.let { put("lastOpened", it.toEpochMilli() / 1000.0) }
            put("inWindow", record.inWindow)
            put("checkedInChooser", record.checkedInChooser)
            // Only once changed, as fingerprintAsked.
            if (!record.backupsOn) put("backupsOn", false)
            if (record.backupsKept != DatabaseRecord.DEFAULT_BACKUPS_KEPT) put("backupsKept", record.backupsKept)
        }

        /**
         * The list and its version (none: 0). Null when the text is not a list: not JSON, a record without `id` or a file
         * (`path` or `file`), a field of the wrong type.
         * Fields missing (an older or newer list) read as their defaults; unknown ones are ignored.
         */
        private fun decode(raw: String): Pair<Databases, Int>? = try {
            val root = Json.parse(raw) as? Map<*, *> ?: throw Unreadable()
            val version = root.field<Double>("version")?.toInt() ?: 0
            val records = (root.field<List<*>>("databases") ?: throw Unreadable()).map { record(it as? Map<*, *> ?: throw Unreadable()) }
            val current = root.field<String>("current")?.let(::DatabaseId)?.takeIf { id -> records.any { it.id == id } }
            Databases(records, current ?: records.firstOrNull()?.id) to version
        } catch (_: JsonException) {
            null
        } catch (_: Unreadable) {
            null
        }

        private fun record(map: Map<*, *>): DatabaseRecord = DatabaseRecord(
            id = DatabaseId(map.field<String>("id") ?: throw Unreadable()),
            file = map.location("path", "file") ?: throw Unreadable(),
            keyFile = map.location("keyFilePath", "keyFile"),
            name = map.field<String>("name"),
            color = map.field<String>("color"),
            useBiometric = map.field<Boolean>("useBiometric") ?: false,
            fingerprintAsked = map.field<Boolean>("fingerprintAsked") ?: false,
            opensWithoutPassword = map.field<Boolean>("opensWithoutPassword") ?: false,
            lastOpened = map.field<Double>("lastOpened")?.let { Instant.ofEpochMilli(Math.round(it * 1000)) },
            inWindow = map.field<Boolean>("inWindow") ?: false,
            checkedInChooser = map.field<Boolean>("checkedInChooser") ?: true,
            backupsOn = map.field<Boolean>("backupsOn") ?: true,
            // A number this app does not offer (a newer one's) keeps the nearest larger one offered, else the largest.
            backupsKept = map.field<Double>("backupsKept")?.toInt()?.let { kept ->
                DatabaseRecord.BACKUPS_KEPT.firstOrNull { it >= kept } ?: DatabaseRecord.BACKUPS_KEPT.last()
            } ?: DatabaseRecord.DEFAULT_BACKUPS_KEPT,
        )

        /** The field's value; null when it is missing or null; another type makes the list unreadable. */
        private inline fun <reified T : Any> Map<*, *>.field(key: String): T? = when (val value = this[key]) {
            null -> null
            is T -> value
            else -> throw Unreadable()
        }
    }

    private class Unreadable : Exception()
}
