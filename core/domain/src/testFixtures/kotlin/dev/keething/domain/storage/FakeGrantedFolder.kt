package dev.keething.domain.storage

import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.time.Duration
import java.time.Instant
import java.util.TreeMap
import java.util.TreeSet

/**
 * A granted folder in memory that behaves as stage S0 measured the phone's storage provider (keething-spec
 * features/databases/saf/plan.md §1): a name taken gives "name (1).ext" instead, never a file over another; a write
 * into an existing file keeps its tail; a rename keeps the file, its identity and its time; a file's time is the
 * clock's at its making and at each write, and the app cannot set it; a watch ends with the provider's process. Names
 * are taken regardless of case (stage S3 measured it): a path finds its file in any case, a name taken in another case
 * gives "name (1).ext", and a rename to the file's own name in another case too; a file keeps the spelling it was made
 * with.
 *
 * The provider's notices come on another thread, so they wait here for [deliverNotices]. [beforeCall] runs before each
 * call of the port reaches the provider: a test fails the call there, ends the app's process there ([ProcessEnded]),
 * or changes the folder as a sync would ([put], [remove]); [afterCall] once the provider did it, as when its answer is
 * lost. [calls] lists the calls that reached it. [stampLag]: the storage stamps a file from a coarser clock, behind.
 */
public class FakeGrantedFolder(
    override val id: String = "fake-grant",
    private val now: () -> Instant = { Instant.parse("2026-11-02T12:00:00Z") },
) : GrantedFolder {
    public enum class Operation { LIST, VERSION, READ, CREATE, WRITE, RENAME, DELETE, WATCH }

    /** A call of the port: [path] is the folder for [Operation.CREATE] and [Operation.WATCH]; [name] the one asked. */
    public data class Call(val operation: Operation, val path: String, val name: String? = null)

    private class Stored(var bytes: ByteArray, var modified: Instant, val inode: Long) {
        fun version() = FileVersion(bytes.size.toLong(), modified, "fake:$inode")
    }

    private inner class Watch(val folder: String, val onChange: () -> Unit) : FolderWatch {
        var ended = false
        var closed = false

        override fun isAlive(): Boolean = synchronized(this@FakeGrantedFolder) { !ended && !closed }

        override fun close() {
            synchronized(this@FakeGrantedFolder) { closed = true }
        }
    }

    private val files = TreeMap<String, Stored>(String.CASE_INSENSITIVE_ORDER)
    private val folders = TreeSet(String.CASE_INSENSITIVE_ORDER).apply { add("") }
    private val watches = mutableListOf<Watch>()
    private val noticed = TreeSet(String.CASE_INSENSITIVE_ORDER)
    private val made = mutableListOf<Call>()
    private var inodes = 0L

    /** Runs before each call of the port reaches the provider (outside its lock, so it may wait for another thread). */
    @Volatile
    public var beforeCall: (Call) -> Unit = {}

    /** Runs once the provider did a call (outside its lock): throwing there is a call done whose answer was lost. */
    @Volatile
    public var afterCall: (Call) -> Unit = {}

    /** How far behind the clock the storage stamps a file it makes or writes. */
    @Volatile
    public var stampLag: Duration = Duration.ZERO

    /** False: the grant is gone, and every call is refused. */
    @Volatile
    public var granted: Boolean = true

    /** The next write stores only this many bytes, and the process ends there. */
    @Volatile
    public var writeEndsAfter: Int? = null

    public val calls: List<Call> get() = synchronized(this) { made.toList() }

    override fun list(folder: String): List<FolderItem>? = call(Call(Operation.LIST, folder)) {
        if (folder !in folders) return@call null
        folders.filter { it.isNotEmpty() && !it.equals(folder, ignoreCase = true) && folderOf(it).equals(folder, ignoreCase = true) }
            .map { FolderItem(nameOf(it), true) } +
            files.keys.filter { folderOf(it).equals(folder, ignoreCase = true) }.map { FolderItem(nameOf(it), false) }
    }

    override fun version(path: String): FileVersion? = call(Call(Operation.VERSION, path)) { files[path]?.version() }

    override fun read(path: String): FolderRead? = call(Call(Operation.READ, path)) {
        files[path]?.let { FolderRead(it.bytes.copyOf(), it.version()) }
    }

    override fun create(folder: String, name: String): String = call(Call(Operation.CREATE, folder, name)) {
        if (folder !in folders) throw NoSuchFileException(folder)
        val given = freeName(folder, name)
        files[pathIn(folder, given)] = Stored(ByteArray(0), stamp(), ++inodes)
        noticed += folder
        given
    }

    override fun write(path: String, bytes: ByteArray): Unit = call(Call(Operation.WRITE, path)) {
        val stored = files[path] ?: throw NoSuchFileException(path)
        val written = writeEndsAfter?.let { bytes.copyOf(it) } ?: bytes
        // Mode "w" does not truncate (S0): what was beyond the new end stays.
        stored.bytes = if (stored.bytes.size > written.size) written + stored.bytes.copyOfRange(written.size, stored.bytes.size) else written.copyOf()
        stored.modified = stamp()
        noticed += folderOf(path)
        if (writeEndsAfter != null) {
            writeEndsAfter = null
            throw ProcessEnded()
        }
    }

    override fun rename(path: String, name: String): String = call(Call(Operation.RENAME, path, name)) {
        if (path !in files) throw NoSuchFileException(path)
        val folder = folderOf(path)
        // The name is found free or not with the file still there: its own name in another case is taken.
        val given = freeName(folder, name)
        files[pathIn(folder, given)] = checkNotNull(files.remove(path))
        noticed += folder
        given
    }

    override fun delete(path: String): Boolean = call(Call(Operation.DELETE, path)) {
        (files.remove(path) != null).also { if (it) noticed += folderOf(path) }
    }

    override fun watch(folder: String, onChange: () -> Unit): FolderWatch = call(Call(Operation.WATCH, folder)) {
        if (folder !in folders) throw NoSuchFileException(folder)
        Watch(folder, onChange).also { watches += it }
    }

    private inline fun <T> call(call: Call, operation: () -> T): T {
        beforeCall(call)
        val result = synchronized(this) {
            made += call
            if (!granted) throw AccessDeniedException(call.path)
            operation()
        }
        afterCall(call)
        return result
    }

    private fun stamp(): Instant = now() - stampLag

    /** As Android names a file whose name is taken, case aside: "name (1).ext", then "(2)"… */
    private fun freeName(folder: String, name: String): String {
        fun taken(candidate: String) = pathIn(folder, candidate).let { it in files || it in folders }
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val (stem, extension) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        return generateSequence(1) { it + 1 }.map { "$stem ($it)$extension" }.first { !taken(it) }
    }

    // What the test does to the folder, as a sync or the user would: no call of the port, nothing refused.

    /** A file at [path] as a sync brings it: a new file renamed over whatever was there, with the time given. */
    @Synchronized
    public fun put(path: String, bytes: ByteArray, modified: Instant = now()) {
        makeFolder(folderOf(path))
        files[path] = Stored(bytes.copyOf(), modified, ++inodes)
        noticed += folderOf(path)
    }

    /** The file at [path] written over in place, keeping its identity (a sync app that writes in place). */
    @Synchronized
    public fun writeInPlace(path: String, bytes: ByteArray, modified: Instant = now()) {
        files.getValue(path).apply {
            this.bytes = bytes.copyOf()
            this.modified = modified
        }
        noticed += folderOf(path)
    }

    /** The file at [path] gives the time given, keeping its content and identity (a sync's time only). */
    @Synchronized
    public fun touch(path: String, modified: Instant) {
        files.getValue(path).modified = modified
        noticed += folderOf(path)
    }

    @Synchronized
    public fun remove(path: String) {
        files.remove(path)
        noticed += folderOf(path)
    }

    @Synchronized
    public fun makeFolder(path: String) {
        var folder = path
        while (folder.isNotEmpty()) {
            folders += folder
            folder = folderOf(folder)
        }
    }

    @Synchronized
    public fun bytesOf(path: String): ByteArray? = files[path]?.bytes?.copyOf()

    @Synchronized
    public fun versionOf(path: String): FileVersion? = files[path]?.version()

    /** The names of the files in [folder]. */
    @Synchronized
    public fun names(folder: String = ""): List<String> = files.keys.filter { folderOf(it).equals(folder, ignoreCase = true) }.map(::nameOf)

    /** The provider's notices of what changed since the last ones, to the watches still alive. */
    public fun deliverNotices() {
        val calls = synchronized(this) {
            val due = watches.filter { !it.ended && !it.closed && it.folder in noticed }
            noticed.clear()
            due.map { it.onChange }
        }
        calls.forEach { it() }
    }

    /** The provider's process ended (`am force-stop com.android.externalstorage`): its watches stop, without a word. */
    @Synchronized
    public fun endWatches() {
        watches.forEach { it.ended = true }
    }

    /** The watches that would still give notices. */
    @Synchronized
    public fun liveWatches(): Int = watches.count { !it.ended && !it.closed }
}

/** The app's process ends here (the system ends it, the phone powers off): nothing after it runs, no cleanup either. */
public class ProcessEnded : Error("the app's process ended here")
