package dev.keething.android.attachment

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiText
import dev.keething.card.editing.EntryEditor
import dev.keething.domain.secret.Secret
import java.io.InputStream

/** A file picked to be attached (keething-spec edit.attachments AC1, AC2): read, or why it is not. */
internal sealed interface PickedFile {
    /** [content] is the caller's to wipe. */
    class Read(val name: String, val content: Secret) : PickedFile

    class Refused(val problem: UiText) : PickedFile
}

/** Reads the files the system's picker gives the editor, each up to [EntryEditor.MAXIMUM_ATTACHMENT_SIZE]. */
internal class NewAttachments(context: Context) {
    private val context = context.applicationContext

    /**
     * [uri]'s name and bytes; a folder, a file over the limit and one that cannot be read are refused, said in the Mac's
     * words. Blocks on the storage: call it off the main thread.
     */
    fun read(uri: Uri): PickedFile {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    size = if (cursor.isNull(1)) null else cursor.getLong(1)
                }
            }
        } catch (_: Exception) {
            // Some providers answer no query; the stream may still be read.
        }
        val shown = name?.takeIf { it.isNotBlank() }?.let(::uiPlain) ?: uiText(R.string.attachment_picked_unnamed)
        if (resolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR) return PickedFile.Refused(uiText(R.string.attachment_picked_folder, shown))
        size?.let { if (it > EntryEditor.MAXIMUM_ATTACHMENT_SIZE) return tooLarge(shown, it) }
        return try {
            val stream = resolver.openInputStream(uri) ?: return PickedFile.Refused(uiText(R.string.attachment_picked_nothing, shown))
            val bytes = stream.use { AttachmentReading.readAtMost(it, EntryEditor.MAXIMUM_ATTACHMENT_SIZE.toInt(), size) }
                ?: return tooLarge(shown, null)
            PickedFile.Read(name.orEmpty(), Secret.adopt(bytes))
        } catch (failure: Exception) {
            PickedFile.Refused(uiText(R.string.attachment_picked_unreadable, shown, failure.message ?: failure.javaClass.simpleName))
        } catch (_: OutOfMemoryError) {
            PickedFile.Refused(uiText(R.string.attachment_picked_no_memory, shown))
        }
    }

    /** [bytes] in the system's units, as the editor's rows show a size. */
    fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    private fun tooLarge(name: UiText, size: Long?): PickedFile.Refused {
        val limit = Formatter.formatShortFileSize(context, EntryEditor.MAXIMUM_ATTACHMENT_SIZE)
        return PickedFile.Refused(
            if (size == null) uiText(R.string.attachment_picked_too_big, name, limit)
            else uiText(R.string.attachment_picked_too_big_size, name, Formatter.formatShortFileSize(context, size), limit),
        )
    }
}

internal object AttachmentReading {
    private const val CHUNK = 1 shl 20

    /**
     * All of [input] when it is at most [limit] bytes, else null; never more than [limit] and a chunk held. [expected]: the
     * size its provider says, read into one array of that size when it is right. What is read and not returned is wiped.
     */
    fun readAtMost(input: InputStream, limit: Int, expected: Long?): ByteArray? {
        if (expected != null && expected > limit) return null
        val chunks = ArrayList<ByteArray>()
        var total = 0
        try {
            var size = expected?.toInt()?.takeIf { it > 0 } ?: CHUNK
            while (true) {
                val chunk = ByteArray(size)
                var filled = 0
                while (filled < size) {
                    val read = input.read(chunk, filled, size - filled)
                    if (read < 0) break
                    filled += read
                }
                total += filled
                if (filled > 0) chunks += if (filled == size) chunk else chunk.copyOf(filled).also { chunk.fill(0) }
                if (total > limit) return null
                if (filled < size) break
                // The size said may be short: the rest in chunks, until the end or past the limit.
                size = CHUNK
            }
            if (chunks.size <= 1) return chunks.removeFirstOrNull() ?: ByteArray(0)
            val bytes = ByteArray(total)
            var at = 0
            for (chunk in chunks) {
                chunk.copyInto(bytes, at)
                at += chunk.size
            }
            return bytes
        } finally {
            chunks.forEach { it.fill(0) }
        }
    }
}
