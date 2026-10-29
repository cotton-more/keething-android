package dev.keething.vault.databases

import dev.keething.domain.settings.SettingsStore
import java.io.File
import java.time.Instant
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
 * A database of the list (SPEC §3.1; Mac `DatabaseRecord`): what this phone remembers of it. Nothing secret: paths
 * and choices only.
 */
public data class DatabaseRecord(
    val id: DatabaseId,
    val path: String,
    /** Remembered by its path, not its contents: read at every unlock (keething-spec kdbx.key-files AC8). */
    val keyFilePath: String? = null,
    /** The file's own name (`Meta/DatabaseName`) as last read; null: the file name says it. */
    val name: String? = null,
    /** The file's own colour (`Meta/Color`) as last read, `#RRGGBB`; null: one of the palette's. */
    val color: String? = null,
    /** Its password is stored for the fingerprint at a typed unlock (a fingerprint per database, plan AD5). */
    val useBiometric: Boolean = false,
    /** The last unlock opened it with its key file and nothing typed: the lock screen shows no password field. */
    val opensWithoutPassword: Boolean = false,
    val lastOpened: Instant? = null,
    /** One of the databases shown side by side (several open, plan AD8). */
    val inWindow: Boolean = false,
    /** Checked in the chooser, as at the last unlock of several (plan AD13). */
    val checkedInChooser: Boolean = true,
) {
    public val fileName: String get() = path.substringAfterLast('/')

    /** The name it goes by: its own, else the file name without `.kdbx`. */
    public val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: fileName.let { if (it.lowercase().endsWith(".kdbx")) it.dropLast(5) else it }

    /** Opens with its key file alone: nothing to type, nothing for the fingerprint to store. */
    public val isPasswordless: Boolean get() = keyFilePath != null && opensWithoutPassword
}

/** The list's databases and the current one: the one the lock screen shows and the session opens. */
public data class Databases(val records: List<DatabaseRecord>, val currentId: DatabaseId?) {
    public val current: DatabaseRecord? get() = currentId?.let(::record)

    public fun record(id: DatabaseId): DatabaseRecord? = records.firstOrNull { it.id == id }
}

/**
 * The databases this phone knows (SPEC §3.1; Mac `DatabaseList`), kept as JSON under [STORE_KEY] in the settings:
 * `{"current": <id>, "databases": [record…], "version": 1}`. The list is the phone's own and never synced.
 *
 * It starts empty: there is no default path, and the one database of before the list is not carried over — it is
 * opened again from the welcome (plan AD4). An unreadable stored list is kept under another key, never overwritten,
 * and the list starts empty. Call it on the main thread.
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
        if (raw != null && stored == null) store.setString("$STORE_KEY.unreadable.${now().epochSecond}", raw)
        mutableState = MutableStateFlow(stored ?: Databases(emptyList(), null))
        state = mutableState.asStateFlow()
        if (stored == null) save()
    }

    public val records: List<DatabaseRecord> get() = state.value.records
    public val currentId: DatabaseId? get() = state.value.currentId
    public val current: DatabaseRecord? get() = state.value.current

    public fun record(id: DatabaseId): DatabaseRecord? = state.value.record(id)

    /**
     * The record of the file at [path]: the one there is (the same file, however the path is written), or a new one at
     * the end of the list. The first one becomes current.
     */
    public fun add(path: String, useBiometric: Boolean = false): DatabaseRecord {
        val standardized = standardized(path)
        records.firstOrNull { standardized(it.path) == standardized }?.let { return it }
        val record = DatabaseRecord(makeId(), standardized, useBiometric = useBiometric)
        change(Databases(records + record, currentId ?: record.id))
        return record
    }

    /** Out of the list; the file stays. The current one removed: the first left becomes current. */
    public fun remove(id: DatabaseId) {
        if (record(id) == null) return
        val left = records.filter { it.id != id }
        change(Databases(left, if (currentId == id) left.firstOrNull()?.id else currentId))
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

    private fun change(databases: Databases) {
        mutableState.value = databases
        save()
    }

    private fun save() {
        val databases = state.value
        val root = buildMap {
            put("version", 1)
            databases.currentId?.let { put("current", it.value) }
            put("databases", databases.records.map(::encode))
        }
        store.setString(STORE_KEY, Json.write(root))
    }

    public companion object {
        public const val STORE_KEY: String = "databases"

        /** The same file however its path is written: `.` and `..` resolved, doubled and trailing `/` dropped. */
        public fun standardized(path: String): String = File(path).normalize().path

        private fun encode(record: DatabaseRecord): Map<String, Any> = buildMap {
            put("id", record.id.value)
            put("path", record.path)
            record.keyFilePath?.let { put("keyFilePath", it) }
            record.name?.let { put("name", it) }
            record.color?.let { put("color", it) }
            put("useBiometric", record.useBiometric)
            put("opensWithoutPassword", record.opensWithoutPassword)
            // Seconds since 1970, as the Mac's list keeps its dates.
            record.lastOpened?.let { put("lastOpened", it.toEpochMilli() / 1000.0) }
            put("inWindow", record.inWindow)
            put("checkedInChooser", record.checkedInChooser)
        }

        /**
         * Null when the text is not a list: not JSON, a record without `id` or `path`, a field of the wrong type.
         * Fields missing (an older or newer list) read as their defaults; unknown ones are ignored.
         */
        private fun decode(raw: String): Databases? = try {
            val root = Json.parse(raw) as? Map<*, *> ?: throw Unreadable()
            root.field<Double>("version")
            val records = (root.field<List<*>>("databases") ?: throw Unreadable()).map { record(it as? Map<*, *> ?: throw Unreadable()) }
            val current = root.field<String>("current")?.let(::DatabaseId)?.takeIf { id -> records.any { it.id == id } }
            Databases(records, current ?: records.firstOrNull()?.id)
        } catch (_: JsonException) {
            null
        } catch (_: Unreadable) {
            null
        }

        private fun record(map: Map<*, *>): DatabaseRecord = DatabaseRecord(
            id = DatabaseId(map.field<String>("id") ?: throw Unreadable()),
            path = map.field<String>("path") ?: throw Unreadable(),
            keyFilePath = map.field<String>("keyFilePath"),
            name = map.field<String>("name"),
            color = map.field<String>("color"),
            useBiometric = map.field<Boolean>("useBiometric") ?: false,
            opensWithoutPassword = map.field<Boolean>("opensWithoutPassword") ?: false,
            lastOpened = map.field<Double>("lastOpened")?.let { Instant.ofEpochMilli(Math.round(it * 1000)) },
            inWindow = map.field<Boolean>("inWindow") ?: false,
            checkedInChooser = map.field<Boolean>("checkedInChooser") ?: true,
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
