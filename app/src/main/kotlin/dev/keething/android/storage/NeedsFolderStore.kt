package dev.keething.android.storage

import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import java.nio.file.AccessDeniedException

/**
 * A database the list kept by its path before keething-spec databases.saf: never read by it (AC1). It reads as no
 * access until its folder is chosen (AC13), which gives the database a store through that folder.
 */
internal class NeedsFolderStore(override val fileName: String) : DatabaseStore {
    override fun read(): DatabaseRead = DatabaseRead.AccessDenied

    override fun version(): FileVersion? = null

    override fun replace(bytes: ByteArray, expected: FileVersion?): Unit = throw AccessDeniedException(fileName, null, "its folder is not chosen yet") // not user text

    override fun conflictCopies(): List<ConflictCopy> = emptyList()

    override fun watch(onChange: () -> Unit): AutoCloseable = AutoCloseable {}
}
