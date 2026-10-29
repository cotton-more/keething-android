package dev.keething.android.storageprobe

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.storage.AllFilesAccess
import dev.keething.android.storage.PathNewFile
import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import java.io.File
import java.security.MessageDigest
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ProbeState(
    val allFilesAccess: Boolean = false,
    val folder: File? = null,
    val databaseCandidates: List<String> = emptyList(),
    val database: File? = null,
    val version: FileVersion? = null,
    val lastRead: String? = null,
    val conflictCopies: List<ConflictCopy> = emptyList(),
    val log: List<String> = emptyList(),
)

/**
 * The storage experiment (ROADMAP step 1): watches the database the way the app will and logs everything it
 * sees, so a Syncthing replace, a conflict copy or a lost file shows up in the log.
 */
internal class StorageProbeViewModel(
    private val databaseStoreAt: (File) -> DatabaseStore,
    private val settings: ProbeSettings,
    newFileAt: (File) -> PathNewFile,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProbeState())
    val state: StateFlow<ProbeState> = mutableState.asStateFlow()

    private val fileWork = Mutex()
    @Volatile private var store: DatabaseStore? = null
    @Volatile private var watch: AutoCloseable? = null

    init {
        refreshAccess()
        settings.databasePath?.let { open(it, reason = "remembered") }
    }

    fun refreshAccess() {
        mutableState.update { it.copy(allFilesAccess = AllFilesAccess.isGranted()) }
    }

    fun chooseFolder(folder: File) = io {
        val candidates = folder.list().orEmpty().filter { it.endsWith(".kdbx") }.sorted()
        mutableState.update { it.copy(folder = folder, databaseCandidates = candidates) }
        log("folder ${folder.path}: ${candidates.size} .kdbx file(s)")
    }

    fun chooseDatabase(fileName: String) {
        val folder = state.value.folder ?: return
        val database = File(folder, fileName)
        settings.databasePath = database
        open(database, reason = "chosen")
    }

    /** The app checks on every return to the foreground too, not only on watcher events (SPEC §3.2). */
    fun checkOnReturn() {
        refreshAccess()
        if (store != null) io { checkVersion("foreground") }
    }

    fun read() = io { readDatabase("button") }

    private val newFiles = NewFileProbe(newFileAt, ::log)

    /** New databases (AD2): putting a new file in place, the Mac's 1 s file timed here, this phone's measurement. */
    fun probeNewFilePlacement() = inFolder(newFiles::placement)

    fun timeMacDefault() = inFolder(newFiles::timeMacDefault)

    fun measure(seconds: Double) = inFolder { newFiles.measure(it, seconds) }

    private fun inFolder(work: (File) -> Unit) = io {
        val folder = state.value.folder ?: return@io log("choose a folder first")
        work(folder)
    }

    /** Replaces the database with its own bytes: exercises the temporary file and the rename. */
    fun rewriteDatabase() = io {
        val store = store ?: return@io
        when (val read = store.read()) {
            is DatabaseRead.Found -> {
                store.replace(read.bytes)
                log("rewrote the database (${read.bytes.size} bytes) through a temporary file")
                checkVersion("after rewrite")
            }
            else -> log("rewrite: cannot read: $read")
        }
    }

    /** A separate file, big enough that a sync of a half-written file would be visible on the Mac. */
    fun writeTestFile() = io {
        val folder = state.value.database?.parentFile ?: return@io
        val bytes = Random.nextBytes(TEST_FILE_SIZE)
        databaseStoreAt(File(folder, TEST_FILE_NAME)).replace(bytes)
        log("wrote $TEST_FILE_NAME (${bytes.size / 1024 / 1024} MiB, sha ${shortHash(bytes)})")
    }

    private fun open(database: File, reason: String) = io {
        watch?.close()
        val store = databaseStoreAt(database)
        this.store = store
        mutableState.update { it.copy(database = database, folder = database.parentFile) }
        log("database ($reason): ${database.path}")
        watch = store.watch { io { readDatabase("watcher") } }
        readDatabase("open")
    }

    private suspend fun checkVersion(trigger: String) {
        val store = store ?: return
        val now = runCatching { store.version() }
        if (now.getOrNull() == state.value.version && now.isSuccess) {
            log("$trigger: unchanged")
        } else {
            readDatabase(trigger)
        }
    }

    private suspend fun readDatabase(trigger: String) {
        val store = store ?: return
        val read = runCatching { store.read() }.getOrElse { error ->
            log("$trigger: read failed: $error")
            return
        }
        val copies = runCatching { store.conflictCopies() }.getOrDefault(emptyList())
        when (read) {
            is DatabaseRead.Found -> {
                val hash = shortHash(read.bytes)
                mutableState.update { it.copy(version = read.version, lastRead = hash, conflictCopies = copies) }
                log("$trigger: read ${read.bytes.size} bytes, sha $hash, ${describe(read.version)}")
            }
            DatabaseRead.Missing -> {
                mutableState.update { it.copy(version = null, conflictCopies = copies) }
                log("$trigger: MISSING")
            }
            DatabaseRead.AccessDenied -> log("$trigger: ACCESS DENIED")
        }
        if (copies.isNotEmpty()) log("$trigger: conflict copies: ${copies.joinToString { it.fileName }}")
    }

    private fun io(work: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            fileWork.withLock {
                runCatching { work() }.onFailure { log("error: $it") }
            }
        }
    }

    private fun log(line: String) {
        val stamped = "${LocalTime.now().format(TIME)}  $line"
        Log.i("StorageProbe", line) // file metadata only, never database content
        mutableState.update { it.copy(log = (listOf(stamped) + it.log).take(LOG_LINES)) }
    }

    override fun onCleared() {
        watch?.close()
    }

    private companion object {
        const val TEST_FILE_NAME = "keething-write-test.bin"
        const val TEST_FILE_SIZE = 8 * 1024 * 1024
        const val LOG_LINES = 200
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun shortHash(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(4).joinToString("") { "%02x".format(it) }

        fun describe(version: FileVersion): String =
            "size ${version.size}, modified ${version.modified}, id ${version.identity}"
    }
}
