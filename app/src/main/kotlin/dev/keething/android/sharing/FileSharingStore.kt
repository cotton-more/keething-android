package dev.keething.android.sharing

import dev.keething.domain.sharing.LostBook
import dev.keething.domain.sharing.SharingStore
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * The sharing's requests (SPEC §10) in one app-private file, sealed before it gets here. In `noBackupFilesDir`: never
 * backed up nor moved to another phone. Written to a temporary file and renamed, so a write cut short leaves the old one.
 * A book given up is noted beside it, in `<file>.lost`: its moment in seconds and whether the user was told.
 */
internal class FileSharingStore(private val file: File) : SharingStore {
    override fun read(): ByteArray? = file.takeIf(File::exists)?.readBytes()

    private val lostFile = File(file.path + ".lost")

    override fun write(bytes: ByteArray) = replace(file, bytes)

    override fun delete() {
        file.delete()
    }

    override fun readLost(): LostBook? {
        val parts = lostFile.takeIf(File::exists)?.readText()?.trim()?.split(' ') ?: return null
        val seconds = parts.firstOrNull()?.toLongOrNull() ?: return null
        return LostBook(Instant.ofEpochSecond(seconds), parts.getOrNull(1) == "told")
    }

    override fun writeLost(lost: LostBook?) {
        if (lost == null) {
            lostFile.delete()
        } else {
            replace(lostFile, "${lost.at.epochSecond}${if (lost.told) " told" else ""}".toByteArray())
        }
    }

    private fun replace(target: File, bytes: ByteArray) {
        val folder = target.parentFile ?: throw IOException("no folder for ${target.name}")
        folder.mkdirs()
        val temporary = File(folder, target.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("could not replace ${target.name}")
        }
    }
}
