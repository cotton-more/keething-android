package dev.keething.android.storage

import android.net.Uri
import android.provider.DocumentsContract
import dev.keething.android.databases.DatabaseFiles
import dev.keething.android.databases.FileLook
import dev.keething.android.databases.Reach
import dev.keething.domain.storage.GrantedFolder
import dev.keething.domain.storage.StoragePlace
import dev.keething.vault.databases.FileLocation
import java.io.IOException
import java.nio.file.AccessDeniedException

/** Places on the phone's storage as the system's pickers name them (keething-spec databases.saf AC2, AC12). */
internal object StoragePlaces {
    /** Where a picker opens: [place] on the phone's storage, as its provider names it ("primary:Sync/KeePass"). */
    fun initialUri(place: StoragePlace?): Uri? =
        place?.let { DocumentsContract.buildDocumentUri(ProviderFolder.AUTHORITY, "${it.volume}:${it.path}") }

    /** The place of a file chosen in the file picker; null for another provider's (a cloud app's, Recent's media, AC12). */
    fun ofDocument(uri: Uri): StoragePlace? {
        if (uri.authority != ProviderFolder.AUTHORITY) return null
        return runCatching { ProviderFolder.placeOf(DocumentsContract.getDocumentId(uri)) }.getOrNull()
    }
}

/**
 * The list's files looked at through their grants, never opened (SPEC §6.2): a file in a granted folder by its version,
 * a key file granted alone by the provider's word. A grant gone or not of the phone's storage: no access (AC10).
 */
internal class GrantedFiles(
    private val folders: (grant: String) -> GrantedFolder?,
    private val files: (grant: String) -> ProviderFile?,
) : DatabaseFiles {
    override fun look(location: FileLocation): FileLook = try {
        when (location) {
            // Never looked at by its path (AC1): the lines say it needs its folder.
            is FileLocation.ByPath -> FileLook(Reach.NEEDS_FOLDER)
            is FileLocation.InFolder -> {
                val folder = folders(location.grant) ?: throw AccessDeniedException(location.fileName)
                folder.version(location.path)?.let { FileLook(Reach.THERE, it.modified) } ?: FileLook(Reach.NOT_FOUND)
            }
            is FileLocation.Granted -> {
                val file = files(location.grant) ?: throw AccessDeniedException(location.fileName)
                FileLook(if (file.isThere()) Reach.THERE else Reach.NOT_FOUND)
            }
        }
    } catch (_: AccessDeniedException) {
        FileLook(Reach.NO_ACCESS)
    } catch (_: IOException) {
        FileLook(Reach.UNKNOWN)
    }
}
