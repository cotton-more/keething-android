package dev.keething.android.storage

import dev.keething.domain.storage.NewFile
import dev.keething.domain.storage.NewFilePlace
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * A new database or key file reached by its path (keething-spec kdbx.new-file AC8, kdbx.new-key-file AC3). Android
 * has no exclusive rename (the Mac's `renamex_np`), and shared storage refuses hard links (`EACCES` on the Pixel 6,
 * SPEC §3.5), so the file is created with `O_EXCL` (`CREATE_NEW`: refused when anything is at the path, a link that
 * leads nowhere too) and its bytes written in one go and synced: one step that refuses a file already there, never a
 * check followed by a move. A new database is about 1.2 KB, written within milliseconds of its creation, and Syncthing
 * waits 10 s after a change before it scans. A new file keeps its modification time (no SPEC §3.3 step 7 fix needed).
 * [ownerOnly]: the storage has permissions (app-private storage): 0600, a key file 0400. Shared storage has none.
 */
internal class PathNewFile(
    private val file: File,
    private val ownerOnly: Boolean,
    private val setPermissions: (Path, Set<PosixFilePermission>) -> Unit = { path, permissions -> Files.setPosixFilePermissions(path, permissions) },
) : NewFile {
    private val folder: File = file.absoluteFile.parentFile ?: throw IllegalArgumentException("A new file needs a folder: $file")
    private val path: Path = file.toPath()

    /** The identity (device and inode) of the file [create] made, for [removeCreated]. */
    @Volatile private var created: Any? = null

    override val fileName: String = file.name

    override fun check(): NewFilePlace = when {
        !folder.isDirectory -> NewFilePlace.NO_FOLDER
        Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> NewFilePlace.TAKEN
        else -> NewFilePlace.FREE
    }

    override fun create(bytes: ByteArray, readOnly: Boolean) {
        if (!folder.isDirectory) throw NoSuchFileException(folder.path)
        val channel = try {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (taken: FileAlreadyExistsException) {
            throw FileAlreadyExistsException(fileName).apply { initCause(taken) }
        }
        try {
            channel.use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            if (ownerOnly) setPermissions(path, if (readOnly) OWNER_READ else OWNER_READ_WRITE)
        } catch (failure: Exception) {
            // The file is this one's own (CREATE_NEW made it): nothing is left behind, whatever failed.
            runCatching { Files.deleteIfExists(path) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure as? IOException ?: IOException(failure.message ?: failure.javaClass.simpleName, failure)
        }
        created = identity()
    }

    override fun removeCreated() {
        val mine = created ?: return
        if (identity() == mine) Files.deleteIfExists(path)
        created = null
    }

    private fun identity(): Any? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
    } catch (_: IOException) {
        null
    }

    private companion object {
        val OWNER_READ = setOf(PosixFilePermission.OWNER_READ)
        val OWNER_READ_WRITE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }
}
