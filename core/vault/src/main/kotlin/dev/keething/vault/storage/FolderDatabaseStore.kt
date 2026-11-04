package dev.keething.vault.storage

import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.DatabaseChangedException
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseReplacedException
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import dev.keething.domain.storage.FolderWatch
import dev.keething.domain.storage.GrantedFolder
import dev.keething.domain.storage.StoppedSave
import dev.keething.domain.storage.folderOf
import dev.keething.domain.storage.nameOf
import dev.keething.domain.storage.pathIn
import dev.keething.domain.time.Clock
import dev.keething.vault.storage.SaveNotes.SaveNote
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.concurrent.withLock

/**
 * The database reached through a folder the user granted (keething-spec databases.saf), never by a file path: its
 * [path] inside [folder], looked up at every read and save, so a file a sync replaced is the same database (AC2). The
 * provider cannot rename over a file, so a save renames the database aside (AC4); a save the app did not finish is put
 * right at the next read, from the note [notes] keeps of it (AC5). One read or save of the database at a time in the
 * process; watching does not wait for them. [sleep] waits for the clock to move on (AC6).
 */
public class FolderDatabaseStore(
    private val folder: GrantedFolder,
    private val path: String,
    private val notes: SaveNotes,
    private val clock: Clock,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : DatabaseStore {
    private val databaseFolder = folderOf(path)
    private val lock = SaveNotes.lockOf(folder.id, path)

    /** The [watch]es not closed; under its own lock, not the save's. */
    private val watches = mutableListOf<Watch>()

    override val fileName: String = nameOf(path)

    override fun read(): DatabaseRead {
        val read: DatabaseRead
        val missed: List<() -> Unit>
        lock.withLock {
            read = try {
                readPuttingRight()
            } catch (_: AccessDeniedException) {
                DatabaseRead.AccessDenied
            }
            missed = watchAgain()
        }
        missed.forEach { it() }
        return read
    }

    private fun readPuttingRight(): DatabaseRead {
        val stoppedSave = putRight()
        var lastRead: DatabaseRead.Found? = null
        repeat(READ_ATTEMPTS) {
            val read = folder.read(path) ?: return DatabaseRead.Missing
            val found = DatabaseRead.Found(read.bytes, read.version, stoppedSave)
            if (folder.version(path) == read.version) return found
            lastRead = found
        }
        // Still changing: the earlier version makes the next check see a change and read again.
        return checkNotNull(lastRead)
    }

    override fun version(): FileVersion? {
        val version: FileVersion?
        val missed: List<() -> Unit>
        lock.withLock {
            version = folder.version(path)
            missed = watchAgain()
        }
        missed.forEach { it() }
        return version
    }

    /**
     * The new content in the database's folder under a name Syncthing never sends, the database renamed aside, the new
     * file renamed in, the old one deleted (AC4): at every moment the old or the new database is whole on the storage,
     * and the database is never written in place. The save is noted before its first step; a failed step puts the
     * folder back as it was, and what could not be put back is put back by the next read.
     */
    override fun replace(bytes: ByteArray, expected: FileVersion?): Unit = lock.withLock {
        val replaced = folder.version(path) ?: throw NoSuchFileException(path, null, "the database is not there")
        if (expected != null && replaced != expected) throw DatabaseChangedException("$fileName changed since it was read")
        val earlier = notes.replacing(folder.id, path, replaced.modified, clock.now())
        val cleared = waitClearOf(replaced.modified, earlier)
        val note = notes.begin(folder.id, path, temporaryFileName(), temporaryFileName(), bytes, clock.now())
        try {
            // 1. The new file, flushed, with a time a sync app tells from the replaced one's and the earlier ones'.
            writeNew(note, bytes, replaced.modified, earlier, cleared)
            // A sync that replaced the database meanwhile keeps its file; the save is made again on it.
            if (folder.version(path) != replaced) throw DatabaseChangedException("$fileName changed during the save")
            // 2. The database aside: the very file the save began from, or a sync renamed its own in at the last moment.
            val aside = folder.rename(path, note.aside)
            if (aside != note.aside) {
                note.asideIs(aside)
                throw IOException("the provider renamed $fileName to $aside, not to ${note.aside}")
            }
            if (folder.version(inFolder(aside)) != replaced) throw DatabaseChangedException("$fileName was replaced as the save began")
            // 3. The new file in its place.
            val renamed = folder.rename(inFolder(note.temporary), fileName)
            if (renamed != fileName) {
                // A sync brought its file in the moment the name was free: it stays, and ours goes at once, before
                // Syncthing sends it under the other name.
                val changed = DatabaseChangedException("$fileName was replaced during the save")
                try {
                    note.strayIs(renamed)
                    folder.delete(inFolder(renamed))
                } catch (failure: IOException) {
                    changed.addSuppressed(failure)
                }
                throw changed
            }
        } catch (failure: Exception) {
            if (!putBack(note, failure)) throw failure
        }
        // 4. The old file goes; one that would not is removed by the next read, with nothing said.
        val oldLeft = try {
            folder.delete(inFolder(note.aside))
            false
        } catch (_: IOException) {
            true
        }
        try {
            checkTime(replaced.modified)
        } catch (failure: IOException) {
            note.over(oldLeft)
            throw failure
        }
        note.over(oldLeft)
    }

    /**
     * Step 1: the new file in the database's folder under a name Syncthing never sends, flushed, with a time a sync app
     * tells from the replaced one's and from the [earlier] ones it may still hold (AC6). The clock said when to write
     * ([cleared], the times waited for); the file's own time is read back, and the file written once more, later, when
     * the storage stamped it otherwise (a coarser clock, a slow write): all before the database is touched.
     */
    private fun writeNew(note: SaveNote, bytes: ByteArray, replaced: Instant, earlier: List<Instant>, cleared: List<Instant>) {
        var times = cleared
        var lag = Duration.ZERO
        repeat(WRITE_ATTEMPTS) { attempt ->
            if (attempt > 0) times = waitClearOf(replaced, earlier, lag)
            val made = folder.create(databaseFolder, note.temporary)
            if (made != note.temporary) {
                note.temporaryIs(made)
                throw IOException("the provider made the new file as $made")
            }
            folder.write(inFolder(note.temporary), bytes)
            val written = folder.version(inFolder(note.temporary))?.modified ?: throw IOException("the new file is gone")
            if (times.all { Duration.between(it, written).abs() >= SYNC_TIME_WINDOW }) return
            lag = maxOf(Duration.between(written, clock.now()), Duration.ZERO)
            folder.delete(inFolder(note.temporary))
        }
        throw IOException("$fileName's new file keeps a time less than 2 s from one the file had: a sync app may not send it")
    }

    /** The save is over: its note goes, or says only that its old file is left ([oldLeft]) for the next read to remove. */
    private fun SaveNote.over(oldLeft: Boolean) {
        if (!oldLeft) return end()
        try {
            stateIs(SaveNote.State.FINISHED)
        } catch (_: IOException) {
            // The next read removes the old file all the same, and says the save was finished.
        }
    }

    /**
     * A save that failed in the middle leaves the database as it was and no file of its own (AC4). True when it turns
     * out done: the new file is the database (a rename that went through though its answer was lost; the old file gone
     * and the new one whole). What could not be put back is put back by the next read, with nothing more said.
     */
    private fun putBack(note: SaveNote, failure: Exception): Boolean {
        try {
            if (settle(note, Settle.PUT_BACK) == StoppedSave.FINISHED) return true
            note.end()
        } catch (putBackFailure: IOException) {
            failure.addSuppressed(putBackFailure)
            try {
                note.stateIs(SaveNote.State.FAILED)
            } catch (noteFailure: IOException) {
                failure.addSuppressed(noteFailure)
            }
        }
        return false
    }

    /**
     * What saves stopped in the middle left of this database, put right (AC5); which way the last one went, until the app
     * said it ([stoppedSaveSaid]). Only the last save begun decides: one begun before it found the database in place,
     * so the files of the earlier ones only go, as do those of a save that was over.
     */
    private fun putRight(): StoppedSave? {
        val left = notes.left(folder.id, path)
        val last = left.lastOrNull()
        for (note in left) {
            if (note.said != null) {
                if (note !== last) note.end()
                continue
            }
            val how = when {
                note !== last || note.state == SaveNote.State.FINISHED -> Settle.CLEAR
                note.state == SaveNote.State.FAILED -> Settle.PUT_BACK
                else -> Settle.STOPPED
            }
            val way = try {
                settle(note, how)
            } catch (failure: AccessDeniedException) {
                throw failure
            } catch (_: IOException) {
                // Left for the next read; the database is read as it is.
                continue
            }
            if (how != Settle.STOPPED || way == null) {
                note.end()
                continue
            }
            try {
                note.putRight(way)
            } catch (_: IOException) {
                // Put right all the same; the next read finds it so and says it again.
            }
        }
        return last?.said
    }

    override fun stoppedSaveSaid(): Unit = lock.withLock {
        notes.left(folder.id, path).filter { it.said != null }.forEach(SaveNote::end)
    }

    private enum class Settle {
        /** A stopped save: the new file in place when it is whole, else the old one (AC5). */
        STOPPED,

        /** A save that failed: the old file back, or the new one when the old one is gone (AC4). */
        PUT_BACK,

        /** A save that was over, or that a later one followed: its files only go. */
        CLEAR,
    }

    /**
     * Leaves no file of [note]'s save in the folder: its stray copy goes, and with the database missing, a file of the
     * save goes in its place as [how] says. Which way it went (null when it only cleared); an [IOException] when the
     * folder could not be put right.
     */
    private fun settle(note: SaveNote, how: Settle): StoppedSave? {
        val temporary = inFolder(note.temporary)
        val aside = inFolder(note.aside)
        note.stray?.let(::inFolder)?.let { stray ->
            // Only while it is the save's content: a name a sync gave its own file since is not touched.
            if (folder.read(stray)?.let { note.wrote(it.bytes) } == true) folder.delete(stray)
        }
        if (how != Settle.CLEAR && folder.version(path) == null) {
            val newIsWhole by lazy { folder.read(temporary)?.let { note.wrote(it.bytes) } == true }
            val back = when {
                how == Settle.STOPPED && newIsWhole -> temporary
                folder.version(aside) != null -> aside
                newIsWhole -> temporary
                else -> null
            }
            if (back != null && renamedIn(back)) {
                folder.delete(if (back == temporary) aside else temporary)
                return if (back == temporary) StoppedSave.FINISHED else StoppedSave.UNDONE
            }
        }
        folder.delete(temporary)
        folder.delete(aside)
        if (how == Settle.CLEAR) return null
        return if (folder.read(path)?.let { note.wrote(it.bytes) } == true) StoppedSave.FINISHED else StoppedSave.UNDONE
    }

    /** [from] renamed to the database's name; false, and [from]'s file gone, when a sync took the name meanwhile. */
    private fun renamedIn(from: String): Boolean {
        val renamed = folder.rename(from, fileName)
        if (renamed == fileName) return true
        folder.delete(inFolder(renamed))
        return false
    }

    /**
     * A sync app that tells a change by the size and the modification time takes times less than 2 s apart for equal
     * (Syncthing on Android does), and the provider cannot set a file's time (AC6): waits until the clock is 2 s from the
     * [replaced] file's time and from the [earlier] ones, a little past, and past the [lag] the storage showed (it stamps a
     * file from a coarser clock, behind). Which times it cleared: all, or the replaced one's only when clearing all would
     * take longer than [LONGEST_WAIT], which it never waits past.
     */
    private fun waitClearOf(replaced: Instant, earlier: List<Instant>, lag: Duration = Duration.ZERO): List<Instant> {
        val clear = SYNC_TIME_WINDOW + TIME_MARGIN + lag
        val now = clock.now()
        val times = (listOf(replaced) + earlier).takeIf { Duration.between(now, clearOf(it, clear, now)) <= LONGEST_WAIT }
            ?: listOf(replaced)
        val at = clearOf(times, clear, now)
        if (at > now) sleep(minOf(Duration.between(now, at), LONGEST_WAIT))
        return times
    }

    /** The first moment from [now] at least [clear] from each of [times]: past the run of their spans that holds [now]. */
    private fun clearOf(times: List<Instant>, clear: Duration, now: Instant): Instant {
        var at = now
        while (true) {
            at = times.filter { Duration.between(it, at).abs() < clear }.maxOfOrNull { it + clear } ?: return at
        }
    }

    /** The saved file's time read back: one a sync app would take for the replaced one's fails the save, replaced as it is. */
    private fun checkTime(replaced: Instant) {
        val saved = folder.version(path)?.modified ?: throw DatabaseReplacedException("$fileName is gone right after the save")
        if (Duration.between(replaced, saved).abs() < SYNC_TIME_WINDOW) {
            throw DatabaseReplacedException("$fileName was saved at $saved, less than 2 s from the file it replaced ($replaced): a sync app may not send it")
        }
    }

    override fun conflictCopies(): List<ConflictCopy> =
        folder.list(databaseFolder).orEmpty()
            .filterNot { it.isFolder }
            .mapNotNull { ConflictCopy.of(fileName, it.name) }
            .sortedByDescending { it.createdAt }

    override fun watch(onChange: () -> Unit): AutoCloseable {
        val watch = Watch(onChange)
        synchronized(watches) { watches += watch }
        return AutoCloseable {
            synchronized(watches) { watches -= watch }
            watch.close()
        }
    }

    /**
     * The provider's process ending ends its watches without a word (AC7): each read and each version check (the app
     * back in the foreground) starts again a watch that ended or never started, and calls what it missed meanwhile.
     * Under [lock]; the calls are made once it is let go.
     */
    private fun watchAgain(): List<() -> Unit> =
        synchronized(watches) { watches.toList() }.filterNot { it.isAlive() }.mapNotNull { watch ->
            watch.start()
            watch.onChange.takeIf { watch.changedSinceSeen() }
        }

    /**
     * A [watch] of the database on its folder's notices. What it saw last is compared under [lock], on the notices' thread
     * or a read's; starting and closing it take only its own lock, so the session's thread never waits for a save.
     */
    private inner class Watch(val onChange: () -> Unit) {
        @Volatile
        private var seen = state()
        private var folderWatch: FolderWatch? = null
        private var closed = false

        init {
            start()
        }

        fun isAlive(): Boolean = synchronized(this) { folderWatch.takeUnless { closed } }?.isAlive() == true

        fun start() {
            val started = try {
                folder.watch(databaseFolder, ::noticed)
            } catch (_: IOException) {
                null
            }
            val ended = synchronized(this) {
                if (closed) {
                    started
                } else {
                    folderWatch.also { folderWatch = started }
                }
            }
            ended?.close()
        }

        private fun noticed() {
            val changed = lock.withLock { !isClosed() && changedSinceSeen() }
            if (changed) onChange()
        }

        /** Whether the database or its conflict copies changed since last seen; under [lock]. */
        fun changedSinceSeen(): Boolean {
            val now = state()
            return (now != seen).also { seen = now }
        }

        private fun isClosed(): Boolean = synchronized(this) { closed }

        fun close() {
            val ended = synchronized(this) {
                closed = true
                folderWatch.also { folderWatch = null }
            }
            ended?.close()
        }
    }

    private fun state(): WatchedState = WatchedState(
        try {
            folder.version(path)
        } catch (_: IOException) {
            null
        },
        try {
            conflictCopies()
        } catch (_: IOException) {
            emptyList()
        },
    )

    private data class WatchedState(val version: FileVersion?, val conflictCopies: List<ConflictCopy>)

    private fun inFolder(name: String): String = pathIn(databaseFolder, name)

    public companion object {
        private const val READ_ATTEMPTS = 3
        private const val WRITE_ATTEMPTS = 2
        private val SYNC_TIME_WINDOW = Duration.ofSeconds(2)
        private val TIME_MARGIN = Duration.ofMillis(50)

        /** The replaced file's span and an earlier time's, met end to end: about 6 s. */
        private val LONGEST_WAIT = (SYNC_TIME_WINDOW + TIME_MARGIN).multipliedBy(3)

        /**
         * Syncthing never scans or sends `.syncthing.*.tmp` files (they are its own downloads), so the save's files
         * never leave the phone. The random middle keeps them apart from the `.syncthing.<name>.tmp` Syncthing itself
         * downloads into.
         */
        public fun temporaryFileName(): String = ".syncthing.keething-${UUID.randomUUID()}.tmp"

        /**
         * At the app's start: every save a stopped process left is put right (AC5), of each database whose grant
         * [folders] still reaches, listed or not, without waiting for its next read. Syncthing removes a temporary file
         * older than a day by its time, the old file aside keeps the database's, and the database's name missing reaches
         * the other devices as a deletion. Which way each went is said at the database's next read. A save that cannot
         * be put right now (no grant, a failure) is left for that read.
         */
        public fun putRightStoppedSaves(notes: SaveNotes, folders: (grant: String) -> GrantedFolder?, clock: Clock) {
            for ((grant, path) in notes.databases()) {
                val folder = folders(grant) ?: continue
                val store = FolderDatabaseStore(folder, path, notes, clock)
                try {
                    store.lock.withLock { store.putRight() }
                } catch (_: IOException) {
                    // Its read tries again.
                }
            }
        }
    }
}
