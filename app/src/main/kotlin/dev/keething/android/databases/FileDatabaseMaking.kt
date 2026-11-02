package dev.keething.android.databases

import dev.keething.android.storage.PathKeyFileSource
import dev.keething.android.storage.PathNewFile
import dev.keething.domain.encryption.DatabaseEncryption
import dev.keething.domain.encryption.MeasuredEncryption
import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.NewFile
import dev.keething.vault.creating.CreationNote
import dev.keething.vault.creating.DatabaseCreator
import dev.keething.vault.creating.DerivedKey
import dev.keething.vault.creating.EncryptionMeasurement
import dev.keething.vault.databases.FileLocation
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant

/**
 * [DatabaseMaking] on the phone's files: the creator of plan AD2 placing each file by its path with `O_EXCL`
 * ([newFileAt]), each noted in [note] before it is written.
 */
internal class FileDatabaseMaking(
    private val newFileAt: (File) -> PathNewFile,
    private val note: CreationNote,
) : DatabaseMaking {
    private val creator = DatabaseCreator(now = Instant::now)

    /** The key files made by this Create, by path: only those are removed again. */
    private val madeKeyFiles = mutableMapOf<String, NewFile>()

    override fun measure(encryption: DatabaseEncryption, checkCancelled: () -> Unit): MeasuredEncryption =
        EncryptionMeasurement.measure(encryption, checkCancelled)

    override fun begin(name: String) {
        note.begin(name)
    }

    override fun createKeyFile(path: String): Secret {
        val file = File(path)
        val target = note.noting(newFileAt(file), FileLocation.ByPath(path))
        val key = creator.createKeyFile(target, PathKeyFileSource(file))
        synchronized(madeKeyFiles) { madeKeyFiles[path] = target }
        return key
    }

    override fun readKeyFile(path: String): Secret = creator.readKeyFile(PathKeyFileSource(File(path)))

    override fun removeKeyFile(path: String) {
        synchronized(madeKeyFiles) { madeKeyFiles.remove(path) }?.removeCreated()
    }

    override fun create(path: String, name: String, password: Secret, keyFile: Secret?, measured: MeasuredEncryption): DerivedKey {
        val file = File(path)
        return creator.create(note.noting(newFileAt(file), FileLocation.ByPath(path)), name, password, keyFile, measured)
    }

    override fun end(made: Boolean) {
        synchronized(madeKeyFiles) { madeKeyFiles.clear() }
        note.end(made)
    }
}

/** [NewDatabaseFiles] on the phone's storage, reached by path (all-files access). */
internal class PhoneNewDatabaseFiles(private val root: File, private val writable: () -> Boolean) : NewDatabaseFiles {
    override fun exists(path: String): Boolean = Files.exists(File(path).toPath(), LinkOption.NOFOLLOW_LINKS)

    override fun isFolder(path: String): Boolean = File(path).isDirectory

    override fun syncs(folder: String): Boolean {
        var current: File? = File(folder).absoluteFile
        val top = root.absoluteFile
        while (current != null) {
            if (File(current, ".stfolder").exists()) return true
            if (current == top) return false
            current = current.parentFile
        }
        return false
    }

    override fun writable(): Boolean = writable.invoke()
}
