package dev.keepassbar.android.attachment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import dev.keepassbar.domain.secret.Secret
import java.io.File
import java.io.IOException

/** What the card hands to Android for an attachment: another app to open it, a document to save it into. */
internal class AndroidAttachments(context: Context) {
    private val context = context.applicationContext
    private val authority = "${context.packageName}.attachments"

    /** From the extension; null when it tells none. */
    fun mimeType(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(AttachmentNames.extension(name))

    /**
     * Opens [file] (written by [AttachmentFiles] from [bytes]) in an app the user picks, which may only read
     * it. The caller wipes [bytes].
     */
    fun viewIntent(file: File, bytes: Secret): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                FileProvider.getUriForFile(context, authority, file),
                bytes.useBytes { AttachmentTypes.forOpening(mimeType(file.name), it) },
            )
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Writes [bytes] into the document the save dialog made. The caller wipes [bytes]. */
    fun writeDocument(uri: Uri, bytes: Secret) {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No stream for the document")
        stream.use { bytes.useBytes(it::write) }
    }

    /** Removes the document the save dialog made when nothing could be written into it. */
    fun deleteDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }
}

/** The save dialog's file name and type. */
internal data class SaveRequest(val fileName: String, val mimeType: String)

/** The system's save dialog: the user picks where the copy goes; null when called off. */
internal class SaveAttachment : ActivityResultContract<SaveRequest, Uri?>() {
    override fun createIntent(context: Context, input: SaveRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}
