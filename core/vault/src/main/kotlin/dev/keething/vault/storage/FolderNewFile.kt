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
 * kdbx.new-key-file AC3), never over a file there. It is written whole under [temporaryName], which Syncthing never
 * scans or sends, and only then renamed to its own name: a Create stopped in the middle leaves no empty or partial file
 * under the database's name for a sync to carry to the other devices (the temporary one is removed at the next start,
 * [dev.keething.vault.creating.CreationNote]). The provider never renames over a file: it names ours otherwise
 * ("Personal (1).kdbx") when the name is taken, so ours is removed again and the name is said to be taken. The storage
 * has no permissions: [create]'s `readOnly` changes nothing.
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
        val temporary = pathIn(parent, folder.create(parent, temporaryName(fileName)))
        val given = try {
            folder.write(temporary, bytes)
            folder.rename(temporary, fileName)
        } catch (failure: Exception) {
            // The temporary file is this one's own: nothing is left behind.
            runCatching { folder.delete(temporary) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure as? IOException ?: IOException(failure.message ?: failure.javaClass.simpleName, failure)
        }
        if (given != fileName) {
            // Ours under the provider's other name; only while it is still what was written, in case a sync took that name since.
            val made = pathIn(parent, given)
            runCatching { if (folder.read(made)?.bytes?.contentEquals(bytes) == true) folder.delete(made) }
            throw FileAlreadyExistsException(fileName)
        }
        created = folder.version(path)
    }

    override fun removeCreated() {
        val mine = created ?: return
        if (folder.version(path) == mine) folder.delete(path)
        created = null
    }

    public companion object {
        /**
         * The name [create] writes [fileName] under first: Syncthing never scans or sends `.syncthing.*.tmp` files (its
         * own downloads, `.syncthing.<name>.tmp`, which the `keething-new` keeps apart).
         */
        public fun temporaryName(fileName: String): String = ".syncthing.$fileName.keething-new.tmp"
    }
}
