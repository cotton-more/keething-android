package dev.keething.vault.databases

import dev.keething.domain.secret.Secret
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.creating.DerivedKey
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.UnlockOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The list's current database and its session (plan AD4; the Mac's `DatabaseListModel`): another chosen, a file opened,
 * a file located, a key file chosen and Remove from List. Each acts on the list at once, and [sessions] follow it. One
 * open at a time from the screens until plan AD9: the window holds the current database alone, so choosing another
 * locks the one shown.
 *
 * Each database keeps its own stored password for the fingerprint (plan AD5): choosing another keeps each one's, and
 * Remove from List deletes the removed one's ([biometric]).
 *
 * Call it on [scope]'s thread (the main thread).
 */
public class CurrentDatabase(
    private val list: DatabaseList,
    private val scope: CoroutineScope,
    private val sessions: VaultSessions,
    private val biometric: BiometricSetup,
) {
    init {
        list.currentId?.let { sessions.showAlone(it, LockReason.Initial) }
    }

    public val current: StateFlow<DatabaseSession?> = sessions.current

    /** The lock screen's sheet: this database's lock screen; the one shown before closes (one open at a time). */
    public fun choose(id: DatabaseId) {
        if (id == list.currentId || list.record(id) == null) return
        sessions.showAlone(id, LockReason.Initial)
    }

    /**
     * Open a database… / Open other…: the file joins the list (once: the same file is the same database), its
     * fingerprint off until it is turned on for it (the owner's choice at plan AD5), and becomes current. Its id.
     */
    public fun open(path: String): DatabaseId {
        val record = list.add(path)
        sessions.showAlone(record.id, LockReason.Initial)
        return record.id
    }

    /**
     * Locate…: the file moved; the database stays, with its id, its stored password and its backups. Its session is the
     * new file's: open, it locks first (its vault was the old file's); locked, the old file's reason goes. Why not, when
     * that file is another database of the list.
     */
    public fun locate(id: DatabaseId, path: String): String? {
        val standardized = DatabaseList.standardized(path)
        val other = list.records.firstOrNull { it.id != id && DatabaseList.standardized(it.path) == standardized }
        if (other != null) return "That file is already in the list as “${other.displayName}”."
        list.update(id) { it.copy(path = standardized) }
        return null
    }

    /**
     * Choose…, Change… or Forget (null) a database's key file. Whether it opened without a password was the old key's.
     * Locked, what its lock screen said goes (a "wrong password" said without the key file, a key file not there); a
     * stored password it rejects is kept until something opens it.
     */
    public fun setKeyFile(id: DatabaseId, path: String?) {
        list.update(id) { it.copy(keyFilePath = path?.let { DatabaseList.standardized(it) }, opensWithoutPassword = false) }
        biometric.keyFileChanged(id)
        if (sessions.session(id)?.session?.state?.value is SessionState.Locked) sessions.lock(id, LockReason.Initial)
    }

    /**
     * Remove from List: the database is forgotten on this phone with its stored password; the file stays. Its session
     * locks first and goes; the current one removed, the first left becomes current. Why the stored password could not
     * be deleted, or null.
     */
    public suspend fun remove(id: DatabaseId): String? {
        list.remove(id)
        return biometric.forget(id)
    }

    /**
     * A database just made (keething-spec databases.new-database AC8–AC10; the Mac's `DatabaseListModel.created`). A
     * record of the list at its path (its file gone, even the current one, which closes) is another database: it goes
     * first with its stored password, so the new one takes nothing of it (its id, backups, stored password). The new
     * one joins the list with its key file and its Use fingerprint, becomes current and opens with [key], the key it was
     * made with, as a typed unlock of [password] (its days start; stored for the fingerprint when chosen). A lock since
     * Create was tapped ([lockedSince], asked once the list has it) leaves it locked and wipes [key]. The caller may
     * wipe [password] as soon as this returns.
     */
    public suspend fun created(
        path: String,
        keyFilePath: String?,
        useBiometric: Boolean,
        key: DerivedKey,
        password: Secret,
        lockedSince: () -> Boolean,
    ): Created {
        val standardized = DatabaseList.standardized(path)
        val old = list.records.firstOrNull { DatabaseList.standardized(it.path) == standardized }
        var problem: String? = null
        if (old != null) {
            list.remove(old.id)
            problem = biometric.forget(old.id)
        }
        val record = list.add(standardized, useBiometric = useBiometric)
        list.update(record.id) { it.copy(keyFilePath = keyFilePath?.let(DatabaseList::standardized)) }
        sessions.showAlone(record.id, LockReason.Initial)
        val database = current.value?.takeIf { it.id == record.id }
        if (database == null || lockedSince()) {
            key.wipe()
            return Created(record.id, opened = null, problem)
        }
        return Created(record.id, database.biometric.unlockCreated(key, password), problem)
    }

    /** What [created] did: the new database's [id], how its opening ends (null: not opened), why an old one's stored password stayed. */
    public class Created(public val id: DatabaseId, public val opened: Deferred<UnlockOutcome>?, public val problem: String?)

    /**
     * The typed unlock of [database] (nothing typed with a key file too), as [BiometricUnlock.unlock]. With a key file
     * its record learns whether it opens with the key file alone: opened with nothing typed it does, opened with a
     * password it does not, and nothing typed rejected it does not (a password was added elsewhere). A key file not
     * there teaches nothing, nor does an unlock whose database stopped being current or whose key file changed
     * meanwhile. The caller may wipe [password] as soon as this returns. False when nothing was started.
     */
    public fun unlock(database: DatabaseSession, password: Secret): Boolean {
        val keyFile = list.record(database.id)?.keyFilePath
        val nothingTyped = password.isEmpty()
        val outcome = database.biometric.unlock(password) ?: return false
        if (keyFile == null) return true
        scope.launch {
            val ended = outcome.await()
            if (current.value !== database || list.record(database.id)?.keyFilePath != keyFile) return@launch
            when {
                ended == UnlockOutcome.Opened -> list.update(database.id) { it.copy(opensWithoutPassword = nothingTyped) }
                nothingTyped && ended is UnlockOutcome.Failed && ended.failure.isWrongKey ->
                    list.update(database.id) { it.copy(opensWithoutPassword = false) }
            }
        }
        return true
    }
}
