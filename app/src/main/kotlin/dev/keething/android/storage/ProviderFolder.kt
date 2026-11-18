package dev.keething.android.storage

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.StructStat
import android.util.Log
import dev.keething.domain.storage.FileVersion
import dev.keething.domain.storage.FolderItem
import dev.keething.domain.storage.FolderRead
import dev.keething.domain.storage.FolderReading
import dev.keething.domain.storage.FolderWatch
import dev.keething.domain.storage.GrantedFolder
import dev.keething.domain.storage.StoragePlace
import dev.keething.domain.storage.folderOf
import dev.keething.domain.storage.nameOf
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.time.Instant

/**
 * A folder the user granted in the system's folder picker (keething-spec databases.saf AC2), reached through the
 * phone's storage provider with [DocumentsContract], never by a file path: [tree] is the grant's address. The
 * provider's document ids are paths ("primary:Sync/KeePass/Personal.kdbx", stage S0), so a file inside the folder is
 * addressed by the folder's id and its path, and a file a sync replaced is the file at that path. Only the phone's own
 * storage and its cards (AC12): another provider's ids are not paths.
 *
 * A [SecurityException] (the grant gone: the folder renamed or deleted on any device, or given back) becomes an
 * [AccessDeniedException], any other failure of the provider an [IOException].
 */
internal class ProviderFolder(private val resolver: ContentResolver, private val tree: Uri) : GrantedFolder {
    override val id: String = tree.toString()

    private val treeId: String = DocumentsContract.getTreeDocumentId(tree)

    /** Where the folder is on the phone's storage: its document id, the volume before the colon. */
    val place: StoragePlace = placeOf(treeId)

    override fun list(folder: String): List<FolderItem>? = provider(folder) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId(folder))
        val columns = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        // A folder not there: no cursor (the provider's FileNotFoundException, logged), or refused (below the grant).
        orMissing(folder, { null }) { resolver.query(children, columns, null, null, null) }?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(FolderItem(cursor.getString(0), cursor.getString(1) == Document.MIME_TYPE_DIR))
                }
            }
        }
    }

    override fun version(path: String): FileVersion? = provider(path) {
        open(path, "r")?.use { descriptor -> versionOf(fstat(descriptor)) }
    }

    override fun read(path: String): FolderRead? = provider(path) {
        open(path, "r")?.use { descriptor ->
            // The version of the one file the descriptor holds; the store checks the name still leads to it after.
            val version = versionOf(fstat(descriptor))
            FolderRead(FileInputStream(descriptor.fileDescriptor).readBytes(), version)
        }
    }

    override fun <T> read(path: String, reading: (InputStream) -> T): FolderReading<T>? = try {
        provider(path) {
            open(path, "r")?.use { descriptor ->
                val version = versionOf(fstat(descriptor))
                val result = try {
                    reading(FileInputStream(descriptor.fileDescriptor))
                } catch (failure: Throwable) {
                    throw ReadingFailed(failure)
                }
                FolderReading(result, version)
            }
        }
    } catch (failed: ReadingFailed) {
        // The reading's own failure as it was, not as the provider's.
        throw failed.failure
    }

    /** Carries a reading's failure past [provider], which would make a runtime exception an [IOException]. */
    private class ReadingFailed(val failure: Throwable) : Exception()

    override fun create(folder: String, name: String): String = provider(folder) {
        val parent = documentUri(folder)
        val created = try {
            orMissing(folder, { throw NoSuchFileException(folder, null, "no folder there") }) { // not user text
                DocumentsContract.createDocument(resolver, parent, MIME_TYPE, name)
            }
        } catch (_: FileNotFoundException) {
            throw NoSuchFileException(folder, null, "no folder there") // not user text
        }
        nameOf(DocumentsContract.getDocumentId(created ?: throw IOException("the provider made no file $name"))) // not user text
    }

    override fun write(path: String, bytes: ByteArray): Unit = provider(path) {
        // Mode "w" does not cut the file's tail: only into a file just made empty (the port's rule).
        val descriptor = open(path, "w") ?: throw NoSuchFileException(path)
        descriptor.use {
            FileOutputStream(it.fileDescriptor).run {
                write(bytes)
                fd.sync()
            }
        }
    }

    override fun rename(path: String, name: String): String = provider(path) {
        val renamed = try {
            orMissing(path, { throw NoSuchFileException(path) }) { DocumentsContract.renameDocument(resolver, documentUri(path), name) }
        } catch (_: FileNotFoundException) {
            throw NoSuchFileException(path)
        }
        // Null only for a failure the platform kept to itself: an id that did not change gives the same address back.
        nameOf(DocumentsContract.getDocumentId(renamed ?: throw IOException("the provider did not rename $path"))) // not user text
    }

    override fun delete(path: String): Boolean = provider(path) {
        try {
            orMissing(path, { false }) { DocumentsContract.deleteDocument(resolver, documentUri(path)) }
        } catch (_: FileNotFoundException) {
            false
        }
    }

    override fun watch(folder: String, onChange: () -> Unit): FolderWatch = provider(folder) {
        val client = resolver.acquireUnstableContentProviderClient(tree.authority ?: throw IOException("no provider")) // not user text
            ?: throw IOException("the storage provider is not there") // not user text
        try {
            orMissing(folder, { throw NoSuchFileException(folder, null, "no folder there") }) { // not user text
                ProviderWatch(client, DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId(folder)), documentUri(folder), onChange)
            }
        } catch (failure: Exception) {
            client.close()
            throw failure
        }
    }

    /**
     * The provider watches a folder only while a cursor of its children is open (S0: an observer of the children's
     * address alone never hears anything), so the watch holds one. Through an unstable client: a cursor held through
     * the resolver ties the app's process to the provider's, and the system ends the app when the provider's ends.
     * Then the notices stop without a word: [isAlive] says so (AC7). Each notice is given at once and again about a
     * second later, as the storage may still show the file as it was at the notice (android SPEC §3.2); a burst of
     * notices (one change brings several) gives one call at once and one after the last.
     */
    private class ProviderWatch(
        private val client: ContentProviderClient,
        children: Uri,
        private val folder: Uri,
        private val onChange: () -> Unit,
    ) : FolderWatch {
        private val cursor: Cursor = client.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?: throw NoSuchFileException(children.toString(), null, "no folder there") // not user text
        private val thread = HandlerThread("folder-watch").apply { start() }
        private val handler = Handler(thread.looper)
        private val now = Runnable { onChange() }
        private val later = Runnable { onChange() }
        private val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "notice")
                if (!handler.hasCallbacks(now)) handler.post(now)
                handler.removeCallbacks(later)
                handler.postDelayed(later, SETTLE_MILLIS)
            }
        }

        @Volatile private var closed = false

        init {
            cursor.registerContentObserver(observer)
        }

        override fun isAlive(): Boolean {
            if (closed) return false
            // A dead provider's binder fails every call made through this client from then on.
            return try {
                client.getType(folder)
                true
            } catch (failure: Exception) {
                Log.i(TAG, "the provider stopped answering: ${failure.javaClass.simpleName}")
                false
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            handler.removeCallbacksAndMessages(null)
            runCatching { cursor.unregisterContentObserver(observer) }
            runCatching { cursor.close() }
            client.close()
            // Not joined: the thread may be waiting for a save in onChange.
            thread.quitSafely()
        }
    }

    /** The document id of [path] inside the folder ("" the folder itself). */
    private fun documentId(path: String): String = when {
        path.isEmpty() -> treeId
        // A card's top: "1234-5678:".
        treeId.endsWith(':') -> treeId + path
        else -> "$treeId/$path" // not user text
    }

    private fun documentUri(path: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId(path))

    /** The descriptor of the file at [path], or null when there is none. */
    private fun open(path: String, mode: String): ParcelFileDescriptor? = try {
        orMissing(path, { null }) { resolver.openFileDescriptor(documentUri(path), mode) }
    } catch (_: FileNotFoundException) {
        null
    }

    /**
     * [operation] on [path], or [whenMissing] when the provider refused it because nothing is there: before any call
     * on a document below the granted folder it checks that the document is inside the tree, and that check fails with
     * an [IllegalArgumentException] ("Failed to determine if … is child of …") when there is no file (measured on the
     * Pixel 6). Whether the path is there is then asked of its folder's listing, case aside.
     */
    private inline fun <T> orMissing(path: String, whenMissing: () -> T, operation: () -> T): T = try {
        operation()
    } catch (failure: IllegalArgumentException) {
        if (path.isNotEmpty() && isMissing(path)) whenMissing() else throw failure
    }

    /** Nothing at [path]: not in its folder's listing, or its folder is not there either. */
    private fun isMissing(path: String): Boolean =
        list(folderOf(path))?.none { it.name.equals(nameOf(path), ignoreCase = true) } ?: true

    private fun fstat(descriptor: ParcelFileDescriptor): StructStat = try {
        Os.fstat(descriptor.fileDescriptor)
    } catch (failure: ErrnoException) {
        throw IOException("the file's attributes could not be read", failure) // not user text
    }

    /** The real file's size, time to the nanosecond and identity, as `Os.fstat` gives them through the provider (AC8, S0). */
    private fun versionOf(stat: StructStat): FileVersion =
        FileVersion(stat.st_size, Instant.ofEpochSecond(stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec), "${stat.st_dev}:${stat.st_ino}")

    /** [operation] with the provider's failures as the port names them. */
    private inline fun <T> provider(path: String, operation: () -> T): T = try {
        operation()
    } catch (failure: SecurityException) {
        throw AccessDeniedException(path, null, failure.message).apply { initCause(failure) }
    } catch (failure: IOException) {
        throw failure
    } catch (failure: RuntimeException) {
        // The provider's own failures come back as runtime exceptions (IllegalStateException "Failed to rename"…).
        throw IOException(failure.message ?: failure.javaClass.simpleName, failure)
    } catch (failure: RemoteException) {
        // Through the unstable client: the provider's process ended.
        throw IOException(failure.message ?: failure.javaClass.simpleName, failure)
    }

    companion object {
        private const val TAG = "ProviderFolder" // not user text

        /** The phone's own storage and its cards (keething-spec databases.saf AC12). */
        const val AUTHORITY = "com.android.externalstorage.documents"

        /** Every file the app makes: the database's temporary files, a new database, a new key file (S0: names kept). */
        private const val MIME_TYPE = "application/octet-stream"

        private const val SETTLE_MILLIS = 1_000L

        /** A folder's grant from the folder picker, or null when [grant] is not one of the phone's storage. */
        fun of(resolver: ContentResolver, grant: String): ProviderFolder? {
            val uri = Uri.parse(grant)
            if (uri.authority != AUTHORITY || !DocumentsContract.isTreeUri(uri)) return null
            return ProviderFolder(resolver, uri)
        }

        /** "primary:Sync/KeePass" as a place: the volume before the colon, the path after it. */
        fun placeOf(documentId: String): StoragePlace =
            StoragePlace(documentId.substringBefore(':'), documentId.substringAfter(':', missingDelimiterValue = "").trim('/'))
    }
}

/** A grant that is not of the phone's storage (AC12): nothing in it can be reached, as with a grant gone (AC10). */
internal class UnreachableFolder(override val id: String) : GrantedFolder {
    private fun denied(path: String): Nothing = throw AccessDeniedException(path, null, "not a folder of the phone's storage") // not user text

    override fun list(folder: String): List<FolderItem>? = denied(folder)

    override fun version(path: String): FileVersion? = denied(path)

    override fun read(path: String): FolderRead? = denied(path)

    override fun create(folder: String, name: String): String = denied(folder)

    override fun write(path: String, bytes: ByteArray): Unit = denied(path)

    override fun rename(path: String, name: String): String = denied(path)

    override fun delete(path: String): Boolean = denied(path)

    override fun watch(folder: String, onChange: () -> Unit): FolderWatch = denied(folder)
}
