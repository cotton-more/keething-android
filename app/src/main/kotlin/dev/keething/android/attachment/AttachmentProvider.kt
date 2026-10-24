package dev.keething.android.attachment

import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Serves the files of [AttachmentFiles] to the app that opens one. The type it tells must be the one the
 * intent carries: a viewer asks the provider again (the HTML Viewer showed a text file without an extension
 * as a download that never ended when the provider said `application/octet-stream`).
 */
internal class AttachmentProvider : FileProvider() {
    override fun getType(uri: Uri): String {
        val type = super.getType(uri) ?: AttachmentTypes.GENERIC
        if (type != AttachmentTypes.GENERIC) return type
        val bytes = runCatching { cachedFile(uri)?.takeIf { it.length() <= AttachmentTypes.MAX_TEXT_CHECK }?.readBytes() }.getOrNull()
        return if (bytes != null && AttachmentTypes.isText(bytes)) AttachmentTypes.TEXT else type
    }

    /** The file behind [uri]: `/attachments/<folder>/<name>` under the cache, as `attachment_paths.xml` says. */
    private fun cachedFile(uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[0] != "attachments" || segments.any { it == ".." || it == "." }) return null
        val root = File(context?.cacheDir ?: return null, "attachments")
        return File(File(root, segments[1]), segments[2])
    }
}
