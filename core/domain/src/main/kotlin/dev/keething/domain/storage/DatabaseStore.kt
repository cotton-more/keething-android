package dev.keething.domain.storage

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * How the app reaches the database file (SPEC §3.1), so the way of reaching it can change without
 * touching anything else. The functions block on file I/O; call them off the main thread.
 */
public interface DatabaseStore {
    /** The database file's name, as Syncthing and the user see it. */
    public val fileName: String

    public fun read(): DatabaseRead

    /**
     * As [read], the file's bytes handed to [reading] as they are read, never held whole (a large database's save;
     * keething-spec app.large-database AC2). [reading] is called at most once: a read that saw the file change under it
     * is not made again, its version being the one it started at (a [replace] at it is refused).
     */
    public fun <T> read(reading: (InputStream) -> T): DatabaseReading<T> = when (val read = read()) {
        is DatabaseRead.Found -> DatabaseReading.Found(ByteArrayInputStream(read.bytes).use(reading), read.version, read.stoppedSave)
        DatabaseRead.Missing -> DatabaseReading.Missing
        DatabaseRead.AccessDenied -> DatabaseReading.AccessDenied
    }

    /** The file's current version, or null when there is no file. */
    public fun version(): FileVersion?

    /**
     * Makes [bytes] the database's content, in place of the file at the version [expected] (null: whatever is
     * there). Nothing that watches the folder (Syncthing) may ever see a half-written database where the
     * storage allows that, and the new file must look changed to it even with the same size: a modification
     * time it tells from the old one. Throws a [DatabaseChangedException] when the file is not, or is no
     * longer, at [expected] (nothing replaced), a [DatabaseReplacedException] when the content was replaced but
     * the new time could not be made sure of, and another [IOException] when nothing was replaced.
     */
    public fun replace(bytes: ByteArray, expected: FileVersion? = null)

    /**
     * Keeps [bytes] as a new file beside the database, named [name], or with ` 2`, ` 3` … before the database's extension when
     * that name is taken: never over a file there (keething-spec kdbx.kdbx3 AC7). Returns the name it got; throws an
     * [IOException] when nothing was kept.
     */
    public fun keepBeside(bytes: ByteArray, name: String): String = throw IOException("no folder to keep $name in")

    /** Syncthing's conflict copies of the database in its folder, newest first. */
    public fun conflictCopies(): List<ConflictCopy>

    /**
     * Calls [onChange] on a background thread when the database file (its [version]) or the set of its
     * conflict copies changed, until the returned handle is closed. Unrelated files in the folder cause
     * no calls; one change may cause more than one.
     */
    public fun watch(onChange: () -> Unit): AutoCloseable

    /**
     * The app said the [DatabaseRead.Found.stoppedSave] of a read: reads say it no more. Until then each read says it,
     * so a read that shows nothing (a save's own, an unlock with a wrong password) does not take it away.
     */
    public fun stoppedSaveSaid() {}
}

/**
 * The database file changed (a sync replaced it) while [DatabaseStore.replace] was getting ready to replace it: nothing
 * was replaced, and the change is to be made again on the file as it is now.
 */
public class DatabaseChangedException(message: String) : IOException(message)

/**
 * [DatabaseStore.replace] replaced the database, but could not make sure of what a sync app needs of the new file (its
 * time): the save is not reported as done, yet the file is the new one, and the copy kept before it is its backup.
 */
public class DatabaseReplacedException(message: String) : IOException(message)
