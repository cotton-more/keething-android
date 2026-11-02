package dev.keething.vault.storage

import dev.keething.domain.storage.GrantedFile
import dev.keething.domain.storage.GrantedFolder
import dev.keething.domain.storage.KeyFileRead
import dev.keething.domain.storage.KeyFileSource
import dev.keething.vault.databases.FileLocation
import java.nio.file.AccessDeniedException

/**
 * How the app reaches a key file wherever the list has it (keething-spec kdbx.key-files AC8, databases.saf AC9): never
 * copied, read again at every unlock. In a granted folder by its path there ([folder]), granted alone through its own
 * grant ([file]), from before databases.saf by its path ([byPath]). A grant that cannot be reached (null: gone, or the
 * app's data cleared) reads as [KeyFileRead.AccessDenied] (AC10).
 */
public class KeyFiles(
    private val folder: (grant: String) -> GrantedFolder?,
    private val file: (grant: String) -> GrantedFile?,
    private val byPath: (path: String) -> KeyFileSource,
) {
    public fun at(location: FileLocation): KeyFileSource = when (location) {
        is FileLocation.ByPath -> byPath(location.path)
        is FileLocation.InFolder -> Granted(location.fileName) {
            (folder(location.grant) ?: throw AccessDeniedException(location.fileName)).read(location.path)?.bytes
        }
        is FileLocation.Granted -> Granted(location.fileName) {
            (file(location.grant) ?: throw AccessDeniedException(location.fileName)).read()
        }
    }

    /** A key file read through a grant: null bytes, no file there. */
    private class Granted(override val fileName: String, private val bytes: () -> ByteArray?) : KeyFileSource {
        override fun read(): KeyFileRead = try {
            bytes()?.let(KeyFileRead::Found) ?: KeyFileRead.Missing
        } catch (_: AccessDeniedException) {
            KeyFileRead.AccessDenied
        }
    }
}
