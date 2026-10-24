package dev.keething.domain.storage

/**
 * How the app reaches the database file (SPEC §3.1), so the way of reaching it can change without
 * touching anything else. The functions block on file I/O; call them off the main thread.
 */
public interface DatabaseStore {
    /** The database file's name, as Syncthing and the user see it. */
    public val fileName: String

    public fun read(): DatabaseRead

    /** The file's current version, or null when there is no file. */
    public fun version(): FileVersion?

    /**
     * Makes [bytes] the database's content. Nothing that watches the folder (Syncthing) may ever see a
     * half-written database where the storage allows that, and the new file must look changed to it even
     * with the same size: a modification time it tells from the old one. Throws an [java.io.IOException]
     * also when the content was replaced but the new time could not be made sure of.
     */
    public fun replace(bytes: ByteArray)

    /** Syncthing's conflict copies of the database in its folder, newest first. */
    public fun conflictCopies(): List<ConflictCopy>

    /**
     * Calls [onChange] on a background thread when the database file (its [version]) or the set of its
     * conflict copies changed, until the returned handle is closed. Unrelated files in the folder cause
     * no calls; one change may cause more than one.
     */
    public fun watch(onChange: () -> Unit): AutoCloseable
}
