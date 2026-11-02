package dev.keething.vault.storage

import dev.keething.domain.storage.FileVersion
import dev.keething.domain.storage.GrantedFolder
import dev.keething.domain.storage.NewFile
import dev.keething.domain.storage.NewFilePlace
import dev.keething.domain.storage.StoragePlace.Companion.folded
import dev.keething.domain.storage.folderOf
import dev.keething.domain.storage.nameOf
import dev.keething.domain.storage.pathIn
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/**
 * A new database or key file at [path] in a granted folder (keething-spec databases.saf AC11, kdbx.new-file AC8,
 * kdbx.new-key-file AC3), never over a file there. The provider never makes a file over another: it names the new one
 * otherwise ("Personal (1).kdbx") when the name is taken, so that one, still empty, is removed again and the name is
 * said to be taken. The file is made empty and written at once; a new database is about 1.2 KB, and Syncthing waits
 * 10 s after a change before it scans. The storage has no permissions: [create]'s `readOnly` changes nothing.
 */
public class FolderNewFile(private val folder: GrantedFolder, private val path: String) : NewFile {
    private val parent = folderOf(path)

    /** The version of the file [create] made, for [removeCreated]. */
    @Volatile private var created: FileVersion? = null

    override val fileName: String = nameOf(path)

    override fun check(): NewFilePlace {
        val items = folder.list(parent) ?: return NewFilePlace.NO_FOLDER
        // The storage takes names regardless of case.
        return if (items.any { folded(it.name) == folded(fileName) }) NewFilePlace.TAKEN else NewFilePlace.FREE
    }

    override fun create(bytes: ByteArray, readOnly: Boolean) {
        val given = folder.create(parent, fileName)
        if (given != fileName) {
            // The provider's own file, just made; only while it is still empty, in case a sync took that name since.
            val made = pathIn(parent, given)
            runCatching { if (folder.version(made)?.size == 0L) folder.delete(made) }
            throw FileAlreadyExistsException(fileName)
        }
        try {
            folder.write(path, bytes)
            created = folder.version(path)
        } catch (failure: Exception) {
            // The file is this one's own (the provider made it under the name asked): nothing is left behind.
            runCatching { folder.delete(path) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure as? IOException ?: IOException(failure.message ?: failure.javaClass.simpleName, failure)
        }
    }

    override fun removeCreated() {
        val mine = created ?: return
        if (folder.version(path) == mine) folder.delete(path)
        created = null
    }
}
