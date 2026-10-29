package dev.keething.vault.databases

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseStore
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.UnlockOutcome
import dev.keething.vault.session.VaultSession
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The current database's session (SPEC §3.1, §4): what the screens unlock, show and save through. [number] tells the
 * sessions of this process apart: the same database chosen again, or its file located, starts a new one, and a screen
 * must not keep the old.
 */
public class DatabaseSession(
    public val number: Int,
    public val id: DatabaseId,
    public val file: File,
    public val store: DatabaseStore,
    public val session: VaultSession,
    public val biometric: BiometricUnlock,
)

/** Builds a database's session; the app picks the file store, the backups and how a key file is read. */
public fun interface SessionFactory {
    /**
     * [keyFilePath] is the database's key file as the list has it at each unlock; [scope] ends when the session is no
     * longer current (for watching it).
     */
    public fun start(record: DatabaseRecord, number: Int, keyFilePath: () -> String?, scope: CoroutineScope): DatabaseSession
}

/**
 * The list's current database and its session, one open at a time (plan AD4; the Mac's D4 `DatabaseListModel`):
 * another chosen, a file opened, a file located, a key file chosen and Remove from List. Each acts on the list at once.
 *
 * Each database keeps its own stored password for the fingerprint (plan AD5): choosing another keeps each one's, and
 * Remove from List deletes the removed one's ([biometric]).
 *
 * Call it on [scope]'s thread (the main thread).
 */
public class CurrentDatabase(
    private val list: DatabaseList,
    private val scope: CoroutineScope,
    private val factory: SessionFactory,
    private val biometric: BiometricSetup,
    private val now: () -> Instant = Instant::now,
) {
    private var started = 0
    private var watching: CoroutineScope? = null
    private val mutableCurrent = MutableStateFlow(list.current?.let(::start))

    public val current: StateFlow<DatabaseSession?> = mutableCurrent.asStateFlow()

    /** The lock screen's sheet: this database's lock screen; the one shown before closes (one open at a time). */
    public fun choose(id: DatabaseId) {
        if (id == list.currentId || list.record(id) == null) return
        list.setCurrent(id)
        follow()
    }

    /**
     * Open a database… / Open other…: the file joins the list (once: the same file is the same database), its
     * fingerprint off until it is turned on for it (the owner's choice at plan AD5), and becomes current. Its id.
     */
    public fun open(path: String): DatabaseId {
        val record = list.add(path)
        list.setCurrent(record.id)
        follow()
        return record.id
    }

    /**
     * Locate…: the file moved; the database stays, with its id, its stored password and its backups. Open, it locks
     * first (its vault was the old file's); locked, the old file's reason goes. Why not, when that file is another
     * database of the list.
     */
    public fun locate(id: DatabaseId, path: String): String? {
        val standardized = DatabaseList.standardized(path)
        val other = list.records.firstOrNull { it.id != id && DatabaseList.standardized(it.path) == standardized }
        if (other != null) return "That file is already in the list as “${other.displayName}”."
        list.update(id) { it.copy(path = standardized) }
        follow()
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
        val database = current.value?.takeIf { it.id == id } ?: return
        if (database.session.state.value is SessionState.Locked) database.session.lock(LockReason.Initial)
    }

    /**
     * Remove from List: the database is forgotten on this phone with its stored password; the file stays. The current
     * one locks first and the first left becomes current. Why the stored password could not be deleted, or null.
     */
    public suspend fun remove(id: DatabaseId): String? {
        val wasCurrent = current.value?.id == id
        list.remove(id)
        if (wasCurrent) follow()
        return biometric.forget(id)
    }

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

    /**
     * The session follows the list's current database: another one, or its file moved, closes the session shown (a
     * fingerprint prompt still up for it opens nothing) and starts the next. Each keeps its stored password.
     */
    private fun follow() {
        val record = list.current
        val previous = current.value
        if (previous?.id == record?.id && previous?.file?.path == record?.path) return
        previous?.session?.close()
        watching?.cancel()
        mutableCurrent.value = record?.let(::start)
    }

    private fun start(record: DatabaseRecord): DatabaseSession {
        val session = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        watching = session
        val id = record.id
        val database = factory.start(record, ++started, { list.record(id)?.keyFilePath }, session)
        // When it was last opened, by any unlock (Settings says it of a file not found).
        session.launch {
            database.session.state.map { it is SessionState.Unlocked }.distinctUntilChanged().filter { it }.collect {
                list.update(id) { it.copy(lastOpened = now()) }
            }
        }
        return database
    }
}
