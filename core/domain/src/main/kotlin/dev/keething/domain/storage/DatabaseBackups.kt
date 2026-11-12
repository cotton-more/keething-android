package dev.keething.domain.storage

import java.io.IOException
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
    public fun holds(sha256: ByteArray): Boolean = holding(sha256) != null

    /** The name of a copy with the SHA-256 [sha256], or null when none has the same bytes. */
    public fun holding(sha256: ByteArray): String? = null

    /**
     * The copy named [name], whole, to restore it (keething-spec databases.backups-window AC6): null when it is not
     * there; [BackupDamagedException] when its bytes are not those kept.
     */
    public fun read(name: String): ByteArray? = null

    /**
     * As [keep], whatever [on] says: the file before a restore, its copy marked so ("Before restoring", keething-spec
     * databases.backups-window AC6). Its name: the restore's Undo restores it. [restored] or [notSaved] follows.
     */
    public fun keepBeforeRestoring(database: InputStream, fileName: String, at: Instant): String =
        throw UnsupportedOperationException("restoring a backup")

    /**
     * The restore that kept the last copy replaced the file with the copy named [restoredName]: as [saved], but the oldest
     * go only while backups are on, and never that one.
     */
    public fun restored(restoredName: String): Unit = saved()
}

/** A backup's bytes are not those kept: changed or damaged since (keething-spec databases.backups-window AC5). */
public class BackupDamagedException(message: String) : IOException(message)
