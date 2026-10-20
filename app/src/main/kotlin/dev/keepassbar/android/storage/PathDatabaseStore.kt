package dev.keepassbar.android.storage

import dev.keepassbar.domain.storage.ConflictCopy
import dev.keepassbar.domain.storage.DatabaseRead
import dev.keepassbar.domain.storage.DatabaseStore
import dev.keepassbar.domain.storage.FileVersion
import dev.keepassbar.domain.time.Clock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The database reached by its path, with all-files access (SPEC §3.1, side-loaded build). Remembering the
 * path rather than a handle means a file Syncthing replaced by a rename is simply the file at that path.
 */
internal class PathDatabaseStore(
    private val database: File,
    private val folderWatcher: FolderWatcher,
    private val clock: Clock,
    private val setModifiedTime: (Path, Instant) -> Unit = { path, time -> Files.setLastModifiedTime(path, FileTime.from(time)) },
) : DatabaseStore {
    private val folder: File = database.absoluteFile.parentFile
        ?: throw IllegalArgumentException("The database must be in a folder: $database")

    override val fileName: String = database.name

    override fun read(): DatabaseRead {
        var lastRead: DatabaseRead.Found? = null
        repeat(READ_ATTEMPTS) {
            val before = try {
                version() ?: return DatabaseRead.Missing
            } catch (_: AccessDeniedException) {
                return DatabaseRead.AccessDenied
            }
            val bytes = try {
                Files.readAllBytes(database.toPath())
            } catch (_: NoSuchFileException) {
                return DatabaseRead.Missing
            } catch (_: AccessDeniedException) {
                return DatabaseRead.AccessDenied
            }
            if (version() == before) return DatabaseRead.Found(bytes, before)
            lastRead = DatabaseRead.Found(bytes, before)
        }
        // Still changing: the earlier version makes the next check see a change and read again.
        return checkNotNull(lastRead)
    }

    override fun version(): FileVersion? {
        val attributes = try {
            Files.readAttributes(database.toPath(), BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) {
            return null
        }
        return FileVersion(
            size = attributes.size(),
            modified = attributes.lastModifiedTime().toInstant(),
            identity = attributes.fileKey()?.toString(),
        )
    }

    override fun replace(bytes: ByteArray) {
        val replaced = version()
        val temporary = File(folder, temporaryFileName())
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), database.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        stampModificationTime(after = replaced?.modified)
    }

    /**
     * Gives the new file a modification time Syncthing tells from the replaced file's (SPEC §3.3 step 7).
     * On the Pixel 6 (kernel 6.1) shared storage gives a file that replaced another by a rename the replaced
     * file's time at its first read, and Syncthing takes a file of the same size and time for unchanged.
     * Setting the time through the path after the rename makes the new one stay; reading a byte brings an
     * old time back before the check, if it would come back.
     */
    private fun stampModificationTime(after: Instant?) {
        val now = clock.now()
        val time = if (after == null) now else maxOf(now, after.plus(SYNCTHING_TIME_WINDOW))
        val path = database.toPath()
        setModifiedTime(path, time)
        Files.newInputStream(path).use { it.read() }
        val kept = version()?.modified
        if (kept == null || Duration.between(kept, time).abs() > TIME_PRECISION) {
            throw IOException("The database was replaced but its modification time is $kept, not $time: Syncthing may not send it")
        }
    }

    override fun conflictCopies(): List<ConflictCopy> =
        folder.list().orEmpty()
            .mapNotNull { ConflictCopy.of(fileName, it) }
            .sortedByDescending { it.createdAt }

    override fun watch(onChange: () -> Unit): AutoCloseable {
        val lock = Any()
        var seen = currentState()
        return folderWatcher.watch(folder) { changedFile ->
            if (changedFile != null && !concernsDatabase(changedFile)) return@watch
            val changed = synchronized(lock) {
                val now = currentState()
                (now != seen).also { seen = now }
            }
            if (changed) onChange()
        }
    }

    private fun concernsDatabase(changedFile: String): Boolean =
        changedFile == fileName || ConflictCopy.of(fileName, changedFile) != null

    private fun currentState(): WatchedState =
        WatchedState(runCatching { version() }.getOrNull(), runCatching { conflictCopies() }.getOrDefault(emptyList()))

    private data class WatchedState(val version: FileVersion?, val conflictCopies: List<ConflictCopy>)

    companion object {
        private const val READ_ATTEMPTS = 3

        /** Syncthing on Android takes modification times less than 2 s apart for equal, except on ext2–4 (shared storage is FUSE). */
        private val SYNCTHING_TIME_WINDOW = Duration.ofSeconds(2)
        private val TIME_PRECISION = Duration.ofMillis(1)

        /**
         * Syncthing never scans or sends `.syncthing.*.tmp` files (they are its own downloads), so a
         * half-written database never leaves the phone. The random middle keeps it apart from the
         * `.syncthing.<name>.tmp` Syncthing itself downloads into.
         */
        fun temporaryFileName(): String = ".syncthing.keepassbar-${UUID.randomUUID()}.tmp"
    }
}
