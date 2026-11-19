package dev.keething.android.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dev.keething.domain.storage.GrantedFile
import dev.keething.domain.storage.Grants
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException

/**
 * A key file the user granted alone in the system's file picker (keething-spec databases.saf AC9): [uri] is the
 * grant's address. A sync replacing the file ends the grant (stage S0): its read then says no access (AC10).
 */
internal class ProviderFile(private val resolver: ContentResolver, private val uri: Uri) : GrantedFile {
    override val id: String = uri.toString()

    override fun read(): ByteArray? = try {
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("the provider gave no file") // not user text
    } catch (_: FileNotFoundException) {
        null
    } catch (failure: SecurityException) {
        throw AccessDeniedException(id, null, failure.message).apply { initCause(failure) }
    } catch (failure: RuntimeException) {
        throw IOException(failure.message ?: failure.javaClass.simpleName, failure)
    }

    /**
     * Whether the file is there, asked of the provider without reading it: false when it has none. Throws an
     * [AccessDeniedException] when the grant is gone (a sync replaced the file), an [IOException] for any other failure.
     */
    override fun isThere(): Boolean = try {
        // The provider gives no cursor for a document that is not there.
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (failure: SecurityException) {
        throw AccessDeniedException(id, null, failure.message).apply { initCause(failure) }
    } catch (failure: RuntimeException) {
        throw IOException(failure.message ?: failure.javaClass.simpleName, failure)
    }

    companion object {
        /** A file's grant from the file picker of the phone's storage, or null when [grant] is not one (AC12). */
        fun of(resolver: ContentResolver, grant: String): ProviderFile? {
            val uri = Uri.parse(grant)
            if (uri.authority != ProviderFolder.AUTHORITY || uri.scheme != ContentResolver.SCHEME_CONTENT) return null
            return ProviderFile(resolver, uri)
        }
    }
}

/**
 * The grants the app holds: the system's persisted ones, which last across restarts and the app's updates (S0), up to
 * Android's limit per app (keething-spec databases.saf AC14). Never throws: a failure is logged, and nothing is held
 * or given back.
 */
internal class PersistedGrants(private val resolver: ContentResolver) : Grants {
    override fun held(): Set<String> = try {
        resolver.persistedUriPermissions.mapTo(HashSet()) { it.uri.toString() }
    } catch (failure: RuntimeException) {
        Log.w(TAG, "the grants held could not be listed: ${failure.javaClass.simpleName}")
        emptySet()
    }

    override fun giveBack(grant: String) {
        try {
            val held = resolver.persistedUriPermissions.firstOrNull { it.uri.toString() == grant } ?: return
            val modes = (if (held.isReadPermission) READ else 0) or (if (held.isWritePermission) WRITE else 0)
            resolver.releasePersistableUriPermission(held.uri, modes)
            Log.i(TAG, "a grant given back")
        } catch (failure: RuntimeException) {
            Log.w(TAG, "a grant could not be given back: ${failure.javaClass.simpleName}")
        }
    }

    /** [grant], just given in a picker, kept across restarts: read and write for a folder, read for a key file. */
    fun take(grant: Uri, write: Boolean) {
        resolver.takePersistableUriPermission(grant, READ or (if (write) WRITE else 0))
    }

    private companion object {
        const val TAG = "Grants" // not user text
        const val READ = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        const val WRITE = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
