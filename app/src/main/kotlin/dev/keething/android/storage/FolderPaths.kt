package dev.keething.android.storage

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * The plain path of a folder chosen in the system folder picker. With all-files access the app works with
 * the path, not with the picker's grant.
 */
internal object FolderPaths {
    fun of(treeUri: Uri): File? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val (volume, relativePath) = DocumentsContract.getTreeDocumentId(treeUri).split(':', limit = 2)
            .takeIf { it.size == 2 } ?: return null
        val root = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
        return if (relativePath.isEmpty()) root else File(root, relativePath)
    }

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
}
