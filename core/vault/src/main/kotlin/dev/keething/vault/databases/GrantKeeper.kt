package dev.keething.vault.databases

import dev.keething.domain.storage.Grants
import dev.keething.domain.storage.StoragePlace.Companion.folded
import dev.keething.vault.storage.SaveNotes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gives back each grant the list no longer needs (keething-spec databases.saf AC14), so the app never comes near
 * Android's limit of grants per app: a folder's or a key file's grant goes once no database or key file of the list is
 * reached through it (Remove from List, Locate… elsewhere, a key file changed). A grant the user gave for the list that
 * it did not take ([offered]: the same file already in the list, a key file in a folder the list already reaches) goes
 * at the list's next change. The notes of the saves of a database that left the list go with it, once they leave
 * nothing to put right ([SaveNotes.forget]); a grant whose folder a save still has to put right stays until it is.
 *
 * Follows [list] from its thread (the main thread); the giving back runs on [io], one at a time, and asks the list
 * again first: a grant the list took again meanwhile stays.
 */
public class GrantKeeper(
    private val list: DatabaseList,
    private val grants: Grants,
    private val notes: SaveNotes,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private var inUse: Set<String> = list.grantsInUse
    private var databases: Set<FileLocation.InFolder> = databaseFiles()
    private val offered = mutableSetOf<String>()
    private val running = Mutex()

    init {
        list.observe(::changed)
    }

    /**
     * [grant], just given by the user in a picker for the list (a folder for a database, a key file): given back after the
     * list's next change unless the list is reached through it then. Call it on the list's thread, before the change.
     */
    public fun offered(grant: String) {
        offered += grant
    }

    /**
     * At the app's start, once stopped saves were put right: every grant held that the list is not reached through (a
     * picker left in the middle, a process ended before it gave one back), and the notes of databases not in the list.
     */
    public suspend fun sweep(): Unit = withContext(io) {
        running.withLock {
            val databases = databaseFiles()
            notes.forgetAllBut { folder, path -> databases.any { it.grant == folder && folded(it.path) == folded(path) } }
            giveBack(grants.held() - list.grantsInUse)
        }
    }

    private fun changed() {
        val nowInUse = list.grantsInUse
        val nowDatabases = databaseFiles()
        val gone = (inUse + offered) - nowInUse
        val left = databases - nowDatabases
        inUse = nowInUse
        databases = nowDatabases
        offered.clear()
        if (gone.isEmpty() && left.isEmpty()) return
        scope.launch(io) {
            running.withLock {
                for (database in left) {
                    if (list.recordOf(database) == null) runCatching { notes.forget(database.grant, database.path) }
                }
                giveBack(gone)
            }
        }
    }

    /** Each of [candidates] the list is still not reached through and no save has to put right. */
    private fun giveBack(candidates: Set<String>) {
        for (grant in candidates) {
            if (grant in list.grantsInUse || notes.unsettled(grant)) continue
            grants.giveBack(grant)
        }
    }

    private fun databaseFiles(): Set<FileLocation.InFolder> = list.records.mapNotNull { it.file as? FileLocation.InFolder }.toSet()
}
