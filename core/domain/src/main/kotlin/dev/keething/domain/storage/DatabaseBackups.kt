package dev.keething.domain.storage

import java.time.Instant

/**
 * Copies of the database as it was before each save (SPEC §3.3 step 7), in the app's own storage, never in the
 * synchronised folder. Blocks on file I/O. A save keeps one before it replaces the file, then says how it went: only a
 * save that replaced the file pushes the oldest copy out, so one that failed leaves them as they were (keething-spec
 * kdbx.hardening AC9).
 */
public fun interface DatabaseBackups {
    /** Keeps [database] (the file named [fileName] before a save at [at]), flushed to the storage; none goes yet. */
    public fun keep(database: ByteArray, fileName: String, at: Instant)

    /** The save that kept the last one replaced the file: the oldest beyond the newest few go. */
    public fun saved() {}

    /** The save that kept the last one did not replace the file: that one goes, the others stay as they were. */
    public fun notSaved() {}
}
