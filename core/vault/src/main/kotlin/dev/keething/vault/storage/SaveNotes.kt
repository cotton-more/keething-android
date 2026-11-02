package dev.keething.vault.storage

import dev.keething.domain.storage.StoppedSave
import dev.keething.domain.storage.StoragePlace.Companion.folded
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The saves under way, each noted in the app's own storage before it touches the database's folder (keething-spec
 * databases.saf AC5): the app can end between a save's steps (the system ends it, the phone powers off), and the next
 * read of that database puts the save right from its note ([FolderDatabaseStore]). A note names the database (its
 * grant and its path), the save's two files under Syncthing's temporary names, and the new content's size and SHA-256:
 * the content the save had read back and checked with the database's key before writing it, so a file that matches is
 * the whole new database. One file per save in [directory], written and flushed before the save's first step and
 * deleted after its last; a stopped save's note stays, put right, until the app has said so. Blocking file I/O. In
 * memory, the times of the files the saves replaced lately (AC6): one for the whole app.
 */
public class SaveNotes(private val directory: File) {
    /** Per database (its grant and path), the times of the files its saves replaced lately; under its own lock. */
    private val replacedTimes = HashMap<String, MutableList<Instant>>()

    /** A save of the database at [path] in the grant [folder], begun at [begun], is about to touch it: noted and flushed first. */
    internal fun begin(folder: String, path: String, temporary: String, aside: String, bytes: ByteArray, begun: Instant): SaveNote =
        SaveNote(File(directory, "${UUID.randomUUID()}$SUFFIX"), folder, path, begun, bytes.size.toLong(), digest(bytes), temporary, aside)
            .also { it.write() }

    /**
     * A save of the database at [path] in the grant [folder] replaces a file whose time was [replaced]: the times of the
     * files the saves before it replaced, kept until a minute past each, which a sync app that has not yet seen those
     * saves still holds as the database's (keething-spec databases.saf AC6). [replaced] is kept for the saves after it.
     */
    internal fun replacing(folder: String, path: String, replaced: Instant, now: Instant): List<Instant> = synchronized(replacedTimes) {
        val times = replacedTimes.getOrPut(key(folder, path)) { mutableListOf() }
        times.removeAll { it + REPLACED_TIME_KEPT < now }
        val earlier = times.filter { it != replaced }
        if (replaced !in times) times += replaced
        earlier
    }

    /**
     * What saves of the database at [path] in the grant [folder] left, the last begun last; a note that cannot be read is
     * left alone. The path's case aside, as the storage takes names.
     */
    internal fun left(folder: String, path: String): List<SaveNote> =
        all().filter { it.folder == folder && folded(it.path) == folded(path) }.sortedWith(compareBy<SaveNote> { it.begun }.thenBy { it.name })

    /**
     * The database at [path] in the grant [folder] left the list (keething-spec databases.saf AC14): the notes of its saves
     * that leave nothing to put right go (one put right and not yet said). One whose folder still needs putting right
     * stays, and keeps its grant ([unsettled]): a stopped save, or one over whose old file, a whole copy of the database,
     * stayed in the folder (a sync app other than Syncthing would send it). Waits for a read or save of it under way.
     */
    public fun forget(folder: String, path: String) {
        lockOf(folder, path).withLock { left(folder, path).filter { it.settled }.forEach(SaveNote::end) }
    }

    /** [forget] of every database whose notes are here and that [listed] does not name: at the app's start. */
    public fun forgetAllBut(listed: (folder: String, path: String) -> Boolean) {
        all().filterNot { listed(it.folder, it.path) }.map { it.folder to it.path }.distinct().forEach { (folder, path) -> forget(folder, path) }
    }

    /** Each database whose saves left a note, by its grant and path: one per database, case aside. */
    internal fun databases(): List<Pair<String, String>> = all().distinctBy { key(it.folder, it.path) }.map { it.folder to it.path }

    /** Whether a save of a database in the grant [folder] left it to put right, or is under way: its grant is kept. */
    public fun unsettled(folder: String): Boolean = all().any { it.folder == folder && !it.settled }

    private fun all(): List<SaveNote> = directory.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty().mapNotNull(::read)

    private fun read(file: File): SaveNote? {
        val properties = Properties()
        try {
            file.inputStream().use(properties::load)
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return SaveNote(
            file,
            folder = properties.getProperty(FOLDER) ?: return null,
            path = properties.getProperty(PATH) ?: return null,
            begun = properties.getProperty(BEGUN)?.let(::instantOrNull) ?: return null,
            size = properties.getProperty(SIZE)?.toLongOrNull() ?: return null,
            digest = properties.getProperty(DIGEST) ?: return null,
            temporary = properties.getProperty(TEMPORARY) ?: return null,
            aside = properties.getProperty(ASIDE) ?: return null,
            stray = properties.getProperty(STRAY),
            // A state this version does not know is a save stopped, which puts everything right.
            state = SaveNote.State.entries.firstOrNull { it.name == properties.getProperty(STATE) } ?: SaveNote.State.SAVING,
            said = StoppedSave.entries.firstOrNull { it.name == properties.getProperty(SAID) },
        )
    }

    private fun instantOrNull(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    internal companion object {
        private const val SUFFIX = ".save"
        private const val FOLDER = "folder"
        private const val PATH = "path"
        private const val BEGUN = "begun"
        private const val SIZE = "size"
        private const val DIGEST = "sha256"
        private const val TEMPORARY = "temporary"
        private const val ASIDE = "aside"
        private const val STRAY = "stray"
        private const val STATE = "state"
        private const val SAID = "said"
        private val REPLACED_TIME_KEPT = Duration.ofMinutes(1)

        private val locks = ConcurrentHashMap<String, ReentrantLock>()

        /** One read or save of the database at [path] in the grant [folder] at a time in the process, however many stores reach it. */
        fun lockOf(folder: String, path: String): ReentrantLock = locks.computeIfAbsent(key(folder, path)) { ReentrantLock() }

        /** The path's case aside: the storage takes names so, and two spellings of one name are one database. */
        private fun key(folder: String, path: String) = "$folder\u0000${folded(path)}"

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * One save's note. The names follow the ones the provider really gave, in memory first, so the save puts right what
     * is there even when its note could not be written again. [stray]: the new content under a name Syncthing sends
     * (`Personal (1).kdbx`), when a sync took the database's name in the moment it was free.
     */
    internal class SaveNote(
        private val file: File,
        val folder: String,
        val path: String,
        val begun: Instant,
        private val size: Long,
        private val digest: String,
        temporary: String,
        aside: String,
        stray: String? = null,
        state: State = State.SAVING,
        said: StoppedSave? = null,
    ) {
        enum class State {
            /** Under way, or stopped in the middle: put right with the new file when it is whole (AC5). */
            SAVING,

            /** Failed, and said so, but could not be put back: put back as it was (AC4), nothing more said. */
            FAILED,

            /** Over, but its old file could not be removed: only that goes, nothing said. */
            FINISHED,
        }

        val name: String get() = file.name

        /**
         * Nothing left in the folder to put right: put right, and said only until the app says it. One over whose old
         * file stayed is not: that file, a whole copy of the database, goes at the next read.
         */
        val settled: Boolean get() = said != null
        var temporary: String = temporary
            private set
        var aside: String = aside
            private set
        var stray: String? = stray
            private set
        var state: State = state
            private set

        /** A stopped save put right, which way, until the app has said it. */
        var said: StoppedSave? = said
            private set

        /** Whether [bytes] are the content the save wrote. */
        fun wrote(bytes: ByteArray): Boolean = bytes.size.toLong() == size && digest(bytes) == digest

        fun temporaryIs(name: String) {
            temporary = name
            write()
        }

        fun asideIs(name: String) {
            aside = name
            write()
        }

        fun strayIs(name: String) {
            stray = name
            write()
        }

        fun stateIs(state: State) {
            this.state = state
            write()
        }

        fun putRight(way: StoppedSave) {
            said = way
            write()
        }

        /** Nothing of the save is left to put right or to say: the note goes. */
        fun end() {
            file.delete()
        }

        fun write() {
            // Not in an apply: Properties has a size of its own.
            val properties = Properties()
            properties.setProperty(FOLDER, folder)
            properties.setProperty(PATH, path)
            properties.setProperty(BEGUN, begun.toString())
            properties.setProperty(SIZE, size.toString())
            properties.setProperty(DIGEST, digest)
            properties.setProperty(TEMPORARY, temporary)
            properties.setProperty(ASIDE, aside)
            stray?.let { properties.setProperty(STRAY, it) }
            properties.setProperty(STATE, state.name)
            said?.let { properties.setProperty(SAID, it.name) }
            file.parentFile.mkdirs()
            val next = File(file.parentFile, file.name + ".new")
            FileOutputStream(next).use { stream ->
                properties.store(stream, null)
                stream.fd.sync()
            }
            if (!next.renameTo(file)) {
                next.delete()
                throw IOException("the note of the save could not be written")
            }
        }
    }
}
