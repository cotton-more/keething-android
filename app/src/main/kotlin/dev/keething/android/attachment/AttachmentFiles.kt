package dev.keething.android.attachment

import dev.keething.domain.secret.Secret
import dev.keething.vault.databases.DatabaseId
import java.io.File
import java.util.UUID

/**
 * Attachments opened in another app (SPEC §4): another app can only read a file, so the attachment is written
 * to a folder of its own under [root] (in the app's cache, served by the `FileProvider`), in its database's folder, and
 * removed at that database's lock (also alone, while others stay open), at every lock of all and at the next start
 * (after the process died). The one place where an attachment is on the phone's storage unencrypted by the database,
 * as the Mac app's Quick Look and KeePassXC's "Open" do it.
 *
 * [write] runs on a background thread, [clear] on the main thread; a lock between the tap and the write must
 * not leave a file behind, hence the [generation] a write is asked for.
 */
internal class AttachmentFiles(private val root: File) {
    /** When a write was asked for: a clear since then, of all or of its database, makes it write nothing. */
    data class Generation(val all: Int, val own: Int)

    private var all = 0
    private val own = HashMap<String, Int>()

    /** Changes on every [clear], of all or of [database]: a write asked for before it writes nothing. */
    @Synchronized
    fun generation(database: DatabaseId): Generation = Generation(all, own[database.fileName] ?: 0)

    /**
     * Writes [bytes] as [name] (made a safe file name) into a new folder in [database]'s; null when [clear] came after
     * [generation] was read. The caller wipes [bytes].
     */
    @Synchronized
    fun write(database: DatabaseId, generation: Generation, name: String, bytes: Secret): File? {
        if (generation != generation(database)) return null
        val folder = File(File(root, database.fileName), UUID.randomUUID().toString())
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
        all++
        root.deleteRecursively()
    }

    /** Removes [database]'s files: it locked alone. */
    @Synchronized
    fun clear(database: DatabaseId) {
        own[database.fileName] = (own[database.fileName] ?: 0) + 1
        File(root, database.fileName).deleteRecursively()
    }
}
