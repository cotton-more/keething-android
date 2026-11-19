package dev.keething.vault.databases

import dev.keething.domain.storage.GrantedFile
import dev.keething.domain.storage.GrantedFolder
import java.io.IOException
import java.nio.file.AccessDeniedException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Whether a database's file or key file is there, looked at through its grant on [io] before anything asks for it: no
 * fingerprint is asked while one is not there (keething-spec unlock.key-file AC3; SPEC §5), and the chooser's run skips
 * such a database by itself (databases.chooser AC6). The files are never read: a folder's file by its version, a key
 * file granted alone by its provider's word.
 */
public class FilesThere(
    private val folder: (grant: String) -> GrantedFolder?,
    private val file: (grant: String) -> GrantedFile?,
    private val io: CoroutineContext,
) {
    /**
     * Not there: not found, its grant gone, or kept by its path (which needs its folder first, databases.saf AC1, AC13).
     * A provider failing otherwise tells nothing: there, and the read that follows says what is wrong.
     */
    public suspend fun isThere(location: FileLocation): Boolean = withContext(io) {
        try {
            when (location) {
                is FileLocation.ByPath -> false
                is FileLocation.InFolder -> folder(location.grant)?.version(location.path) != null
                is FileLocation.Granted -> file(location.grant)?.isThere() == true
            }
        } catch (_: AccessDeniedException) {
            false
        } catch (_: IOException) {
            true
        }
    }
}
