package dev.keepassbar.domain.storage

import java.time.Instant

/**
 * Copies of the database as it was before each save (SPEC §3.3 step 7), in the app's own storage, never in the
 * synchronised folder. Blocks on file I/O.
 */
public fun interface DatabaseBackups {
    /** Keeps [database] (the file named [fileName] before a save at [at]); the oldest beyond the newest few go. */
    public fun keep(database: ByteArray, fileName: String, at: Instant)
}
