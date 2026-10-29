package dev.keething.android.storage

import dev.keething.domain.storage.KeyFileRead
import dev.keething.domain.storage.KeyFileSource
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * A database's key file reached by its path, with all-files access (keething-spec kdbx.key-files AC8, as the Mac and
 * KeePassXC remember one): never copied, read at every unlock.
 */
internal class PathKeyFileSource(private val file: File) : KeyFileSource {
    override val fileName: String = file.name

    override fun read(): KeyFileRead = try {
        KeyFileRead.Found(Files.readAllBytes(file.toPath()))
    } catch (_: NoSuchFileException) {
        KeyFileRead.Missing
    } catch (_: AccessDeniedException) {
        KeyFileRead.AccessDenied
    }
}
