package dev.keething.android.storage

import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import java.nio.file.AccessDeniedException

/**
 * A database the app does not read through what the list has: kept by its path before keething-spec databases.saf
 * (AC1), or reached through a grant to its file alone (app.upgrade-safety AC3). It reads as no access until its folder
 * is chosen or the file located (AC13), which gives the database a store through that folder.
 */
internal class NoAccessStore(override val fileName: String) : DatabaseStore {
    override fun read(): DatabaseRead = DatabaseRead.AccessDenied

    override fun version(): FileVersion? = null

    override fun replace(bytes: ByteArray, expected: FileVersion?): Unit = throw AccessDeniedException(fileName, null, "not reached through a folder") // not user text

    override fun conflictCopies(): List<ConflictCopy> = emptyList()

    override fun watch(onChange: () -> Unit): AutoCloseable = AutoCloseable {}
}
