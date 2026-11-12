package dev.keething.domain.storage

import java.io.InputStream
import java.time.Instant

/**
 * Copies of the database as it was before a save (SPEC §3.3 step 7), in the app's own storage, never in the
 * synchronised folder. Blocks on file I/O. A save keeps one before it replaces the file, then says how it went: only a
 * save that replaced the file pushes the oldest copy out, so one that failed leaves them as they were (keething-spec
 * kdbx.hardening AC9). Whether a save keeps one at all is the saver's: not when backups are off ([on]), when the
 * session wrote that file itself, or when a copy of the same bytes is kept already ([holds]; databases.backups AC1).
 */
public fun interface DatabaseBackups {
    /** Keeps [database] (the file named [fileName] before a save at [at]), flushed to the storage; none goes yet. */
    public fun keep(database: ByteArray, fileName: String, at: Instant)

    /**
     * As [keep], the copy written from [database] as it is read, to its end: a large file is never held whole
     * (keething-spec app.large-database AC2). A failure part of the way leaves the part kept: [notSaved] takes it back.
     */
    public fun keep(database: InputStream, fileName: String, at: Instant): Unit = keep(database.readBytes(), fileName, at)

    /** The save that kept the last one replaced the file: the oldest beyond the number kept go. */
    public fun saved() {}

    /** The save that kept the last one did not replace the file: that one goes, the others stay as they were. */
    public fun notSaved() {}

    /** The database's switch "Back up before changes" (keething-spec databases.backups-window AC2, AC3). */
    public val on: Boolean get() = true

    /** Whether one of the database's copies has the SHA-256 [sha256]: the same bytes are kept already. */
    public fun holds(sha256: ByteArray): Boolean = false
}
