package dev.keepassbar.android.attachment

import dev.keepassbar.domain.secret.Secret
import java.io.File
import java.util.UUID

/**
 * Attachments opened in another app (SPEC §4): another app can only read a file, so the attachment is written
 * to a folder of its own under [root] (in the app's cache, served by the `FileProvider`) and removed on lock
 * and at the next start (after the process died). The one place where an attachment is on the phone's
 * storage unencrypted by the database, as the Mac app's Quick Look and KeePassXC's "Open" do it.
 *
 * [write] runs on a background thread, [clear] on the main thread; a lock between the tap and the write must
 * not leave a file behind, hence the [generation] a write is asked for.
 */
internal class AttachmentFiles(private val root: File) {
    /** Changes on every [clear]: a write asked for before it writes nothing. */
    @get:Synchronized
    var generation: Int = 0
        private set

    /**
     * Writes [bytes] as [name] (made a safe file name) into a new folder; null when [clear] came after
     * [generation] was read. The caller wipes [bytes].
     */
    @Synchronized
    fun write(generation: Int, name: String, bytes: Secret): File? {
        if (generation != this.generation) return null
        val folder = File(root, UUID.randomUUID().toString())
        val file = File(folder, AttachmentNames.fileName(name))
        try {
            check(folder.mkdirs()) { "Could not make $folder" }
            bytes.useBytes(file::writeBytes)
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
        return file
    }

    /** Removes every file written; on lock and at the start. */
    @Synchronized
    fun clear() {
        generation++
        root.deleteRecursively()
    }
}
