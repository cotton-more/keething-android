package dev.keething.vault.saving

import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.time.Clock
import dev.keething.vault.session.RestoreOutcome

/**
 * Restores a backup of a database without a session (not in the window; keething-spec databases.backups-window AC6,
 * AC8): as a locked session's restore, the file replaced and nothing read. Blocks: call it off the main thread.
 */
public class BackupRestorer(store: DatabaseStore, backups: DatabaseBackups, private val clock: Clock) {
    private val saver = VaultSaver(store, backups)

    public fun restore(name: String): RestoreOutcome = when (val result = saver.restore(name, key = null, clock.now())) {
        is RestoreResult.Restored -> RestoreOutcome.Restored(result.beforeRestoring, olderKey = false, mayNotSync = result.mayNotSync)
        is RestoreResult.Failed -> RestoreOutcome.Failed(result.failure)
    }
}
