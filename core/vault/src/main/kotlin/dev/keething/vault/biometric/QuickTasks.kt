package dev.keething.vault.biometric

import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.VaultSessions
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Unlocks for a quick task — autofill, the codes shortcut, a quick note — are never held up by the reminder
 * (keething-spec unlock.reminder-not-a-lock AC5): a database a task found locked asks nothing while the task lasts, and
 * one whose question still stands when the tasks are done is locked again, so that the next ordinary opening asks.
 * A database open before the task is left as it is. Call it on the main thread.
 */
public class QuickTasks(private val sessions: VaultSessions) {
    private val mutableFound = MutableStateFlow<Set<DatabaseId>>(emptySet())

    /** The window's databases a quick task under way found locked: their questions wait. */
    public val found: StateFlow<Set<DatabaseId>> = mutableFound.asStateFlow()

    /** A quick task begins: what is locked now and opens before the tasks are done asks nothing. */
    public fun begin() {
        val open = sessions.openIds.toSet()
        mutableFound.update { found -> found + sessions.windowIds.filterNot(open::contains) }
    }

    /**
     * The tasks are done (the app left the foreground): each database they found locked that is open now with its
     * question standing locks again ([LockReason.AfterQuickTask]); the others stay as they are.
     */
    public fun end() {
        val found = mutableFound.value
        if (found.isEmpty()) return
        mutableFound.value = emptySet()
        for (id in found) {
            val database = sessions.session(id) ?: continue
            if (database.session.state.value is SessionState.Unlocked && database.biometric.current().reminder) {
                sessions.lock(id, LockReason.AfterQuickTask)
            }
        }
    }
}
