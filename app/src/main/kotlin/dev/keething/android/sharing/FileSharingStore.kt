package dev.keething.android.sharing

import dev.keething.domain.sharing.SharingStore
import java.io.File
import java.io.IOException

/**
 * The sharing's requests (SPEC §10) in one app-private file, sealed before it gets here. In `noBackupFilesDir`: never
 * backed up nor moved to another phone. Written to a temporary file and renamed, so a write cut short leaves the old one.
 */
internal class FileSharingStore(private val file: File) : SharingStore {
    override fun read(): ByteArray? = file.takeIf(File::exists)?.readBytes()

    override fun write(bytes: ByteArray) {
        val folder = file.parentFile ?: throw IOException("no folder for ${file.name}")
        folder.mkdirs()
        val temporary = File(folder, file.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("could not replace ${file.name}")
        }
    }

    override fun delete() {
        file.delete()
    }
}
