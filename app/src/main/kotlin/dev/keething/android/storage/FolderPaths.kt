package dev.keething.android.storage

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * The plain path of a folder or file chosen in the system picker. With all-files access the app works with the path,
 * not with the picker's grant (SPEC §3.1): a file Syncthing replaces is the file at that path.
 */
internal object FolderPaths {
    fun of(treeUri: Uri): File? =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()?.let { pathOf(treeUri.authority, it, primary()) }

    /** A file chosen with Open document; null when its place has no path (a media item of Recent, another app's). */
    fun fileOf(documentUri: Uri): File? =
        runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull()?.let { pathOf(documentUri.authority, it, primary()) }

    /** Where the picker opens: [folder], when it is on the phone's own storage. */
    fun initialUri(folder: File?): Uri? {
        val relative = folder?.path?.let { path ->
            val root = primary().path
            if (path == root) "" else path.takeIf { it.startsWith("$root/") }?.removePrefix("$root/")
        } ?: return null
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$relative")
    }

    /**
     * The path behind a document id of the phone's storage provider (`primary:Sync/x.kdbx`, `1234-5678:Keys`), or of
     * the downloads provider when it gives one (`raw:/storage/…`); null for the others.
     */
    internal fun pathOf(authority: String?, documentId: String, primary: File): File? = when (authority) {
        EXTERNAL_STORAGE_AUTHORITY -> documentId.split(':', limit = 2).takeIf { it.size == 2 }?.let { (volume, relativePath) ->
            val root = when (volume) {
                "primary" -> primary
                "home" -> File(primary, "Documents")
                else -> File("/storage/$volume")
            }
            if (relativePath.isEmpty()) root else File(root, relativePath)
        }
        // The path the downloading app wrote, in the phone's storage's other names too: the same file is one path.
        DOWNLOADS_AUTHORITY -> documentId.takeIf { it.startsWith("raw:/") }?.removePrefix("raw:")?.let { path ->
            OTHER_NAMES.firstOrNull { path == it || path.startsWith("$it/") }?.let { File(primary, path.removePrefix(it).removePrefix("/")) } ?: File(path)
        }
        else -> null
    }

    private fun primary(): File = Environment.getExternalStorageDirectory()

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

    /** Links to the phone's own storage. */
    private val OTHER_NAMES = listOf("/sdcard", "/storage/self/primary")
}
