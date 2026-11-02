package dev.keething.vault.databases

import dev.keething.domain.secret.Secret
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.creating.DerivedKey
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.UnlockOutcome
import dev.keething.domain.storage.StoragePlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The list's current database and its session (plan AD4; the Mac's `DatabaseListModel`): another chosen, a file opened,
 * a file located, a key file chosen and Remove from List. Each acts on the list at once, and [sessions] follow it.
 * Choosing, opening or making a database puts it in the window and makes it current: nothing closes, the databases
 * already open stay open (plan AD9; keething-spec databases.list AC4).
 *
 * Each database keeps its own stored password for the fingerprint (plan AD5): choosing another keeps each one's, and
 * Remove from List deletes the removed one's ([biometric]).
 *
 * A grant the user gives for a file or folder here is told to [offered] first ([GrantKeeper.offered]): given back when
 * the list does not take it (keething-spec databases.saf AC14).
 *
 * Call it on [scope]'s thread (the main thread).
 */
public class CurrentDatabase(
    private val list: DatabaseList,
    private val scope: CoroutineScope,
    private val sessions: VaultSessions,
    private val biometric: BiometricSetup,
    private val offered: (grant: String) -> Unit = {},
) {
    public val current: StateFlow<DatabaseSession?> = sessions.current

    /** The lock screen's sheet, its Back: this database's lock screen (or its vault, open); nothing closes. */
    public fun choose(id: DatabaseId) {
        if (list.record(id) == null) return
        show(id)
    }

    /**
     * Open a database… / Open other…: the file joins the list (once: the same file is the same database, and one the
     * list kept by its path is reached as opened from now on) and the window, its fingerprint off until it is turned on
     * for it (the owner's choice at plan AD5), and becomes current; nothing closes. Its id.
     */
    public fun open(file: FileLocation): DatabaseId {
        file.grant?.let(offered)
        // An old record's own file, opened through a folder: that record, reached through it from now on (AC13).
        list.recordOf(file)?.takeIf { it.file is FileLocation.ByPath && file !is FileLocation.ByPath }?.let { old ->
            list.update(old.id) { it.copy(file = file) }
        }
        val record = list.add(file)
        show(record.id)
        adoptOldRecords()
        return record.id
    }

    /**
     * Close (keething-spec databases.several-open AC4, databases.not-open AC4): out of the window, locked, still in the
     * list with its stored password. The window keeps one: its last is not closed (false). The current one closed, the
     * window's first open one becomes current (the vault stays), else its first left.
     */
    public fun close(id: DatabaseId): Boolean {
        val window = list.window
        if (window.none { it.id == id } || window.size <= 1) return false
        if (list.currentId == id) {
            val left = window.filter { it.id != id }
            val next = left.firstOrNull { sessions.session(it.id)?.session?.state?.value is SessionState.Unlocked } ?: left.first()
            list.setCurrent(next.id)
        }
        sessions.close(id)
        return true
    }

    /** Undo of a Close, or a database of Recent: back into the window, locked; nothing else changes. */
    public fun putBack(id: DatabaseId) {
        if (list.record(id) == null) return
        sessions.addToWindow(id)
    }

    /** Lock one alone; the others stay open (keething-spec databases.several-open AC4). */
    public fun lock(id: DatabaseId) {
        sessions.lock(id)
    }

    /** Into the window, current. */
    private fun show(id: DatabaseId) {
        sessions.addToWindow(id)
        list.setCurrent(id)
    }

    /**
     * Locate…: the file moved; the database stays, with its id, its stored password and its backups. Its session is the
     * new file's: open, it locks first (its vault was the old file's); locked, the old file's reason goes. Why not, when
     * that file is another database of the list.
     */
    public fun locate(id: DatabaseId, file: FileLocation): String? {
        file.grant?.let(offered)
        val other = list.records.firstOrNull { it.id != id && it.file.isSameFile(file) }
        if (other != null) return "That file is already in the list as “${other.displayName}”."
        list.update(id) { it.copy(file = file) }
        adoptOldRecords()
        return null
    }

    /**
     * Choose…, Change… or Forget (null) a database's key file. A key file granted alone in a folder the list already
     * reaches is reached through that folder, the database's own first (keething-spec databases.saf AC9): its own grant
     * is not kept. Whether it opened without a password was the old key's. Locked, what its lock screen said goes (a
     * "wrong password" said without the key file, a key file not there); a stored password it rejects is kept until
     * something opens it.
     */
    public fun setKeyFile(id: DatabaseId, keyFile: FileLocation?) {
        keyFile?.grant?.let(offered)
        val record = list.record(id) ?: return
        val reached = (keyFile as? FileLocation.Granted)?.let { list.inGrantedFolder(it.at, prefer = record.file) } ?: keyFile
        list.update(id) { it.copy(keyFile = reached, opensWithoutPassword = false) }
        biometric.keyFileChanged(id)
        if (sessions.session(id)?.session?.state?.value is SessionState.Locked) sessions.lock(id, LockReason.Initial)
        adoptOldRecords()
    }

    /**
     * An old record's one question (keething-spec databases.saf AC13): the folder chosen for a database the list kept by
     * its path, granted as [grant] at [folder] on the storage. It must hold the file at that path; the database is then
     * reached through it, with its id, its stored password, its backups and its choices, and so is its key file when a
     * folder of the list holds that one too. Every other old record the folder holds follows ([adoptOldRecords]). Its
     * session follows, as at [locate]. Why not, or null.
     */
    public fun folderGranted(id: DatabaseId, grant: String, folder: StoragePlace): String? {
        offered(grant)
        val record = list.record(id) ?: return "That database is not in the list any more."
        val before = record.file as? FileLocation.ByPath ?: return null
        val place = before.place ?: return "${record.fileName} is not on the phone's storage."
        val path = place.inside(folder)?.takeIf { it.isNotEmpty() } ?: return "That folder does not hold ${record.fileName}."
        val file = FileLocation.InFolder(grant, folder, path)
        val keyFile = when (val key = record.keyFile) {
            is FileLocation.ByPath -> key.place?.let { list.inGrantedFolder(it, prefer = file) } ?: key
            else -> key
        }
        list.update(id) { it.copy(file = file, keyFile = keyFile) }
        adoptOldRecords()
        return null
    }

    /**
     * Old records through the folders the list is reached through (keething-spec databases.saf AC3, AC13): each database
     * or key file the list kept by its path that such a folder holds is reached through it, with everything else kept,
     * and nothing is asked for it. After each folder the list takes, and at the app's start.
     */
    public fun adoptOldRecords() {
        for (record in list.records) {
            val file = (record.file as? FileLocation.ByPath)?.place?.let { list.inGrantedFolder(it) } ?: record.file
            val keyFile = record.keyFile?.let { key -> (key as? FileLocation.ByPath)?.place?.let { list.inGrantedFolder(it, prefer = file) } ?: key }
            if (file != record.file || keyFile != record.keyFile) list.update(record.id) { it.copy(file = file, keyFile = keyFile) }
        }
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
     * record of the list at its path (its file gone, even the current one, which closes) is another database: it goes, in
     * the same change of the list as the new one joins it, and its stored password after, so the new one takes nothing of
     * it (its id, backups, stored password). The new one joins the list and the window with its key file (one granted
     * alone in a folder the list reaches goes through that folder, keething-spec databases.saf AC9) and its Use fingerprint, becomes current (nothing closes) and
     * opens with [key], the key it was made with, as a typed unlock of [password] (its days start; stored for the fingerprint when chosen). A lock since
     * Create was tapped ([lockedSince], asked once the list has it) leaves it locked and wipes [key]. The caller may
     * wipe [password] as soon as this returns.
     */
    public suspend fun created(
        file: FileLocation,
        keyFile: FileLocation?,
        useBiometric: Boolean,
        key: DerivedKey,
        password: Secret,
        lockedSince: () -> Boolean,
    ): Created {
        listOfNotNull(file.grant, keyFile?.grant).forEach(offered)
        val reached = (keyFile as? FileLocation.Granted)?.let { list.inGrantedFolder(it.at, prefer = file) } ?: keyFile
        // One change: the old record out and the new one in with its key file, so no grant looks unused in between.
        val (record, old) = list.replace(file, reached, useBiometric)
        show(record.id)
        adoptOldRecords()
        val problem = old?.let { biometric.forget(it.id) }
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
     * there teaches nothing, nor does an unlock whose key file changed meanwhile (one whose session went, its file
     * located say, ends interrupted); another database chosen meanwhile does not stop it. The caller may wipe
     * [password] as soon as this returns. How it ends; null when nothing was started (the vault was not locked).
     */
    public fun unlock(database: DatabaseSession, password: Secret): Deferred<UnlockOutcome>? {
        val keyFile = list.record(database.id)?.keyFile
        val nothingTyped = password.isEmpty()
        val outcome = database.biometric.unlock(password) ?: return null
        if (keyFile == null) return outcome
        scope.launch {
            val ended = outcome.await()
            if (list.record(database.id)?.keyFile != keyFile) return@launch
            when {
                ended == UnlockOutcome.Opened -> list.update(database.id) { it.copy(opensWithoutPassword = nothingTyped) }
                nothingTyped && ended is UnlockOutcome.Failed && ended.failure.isWrongKey ->
                    list.update(database.id) { it.copy(opensWithoutPassword = false) }
            }
        }
        return outcome
    }

    /**
     * The chooser's run over the checked [ids] (plan AD13; keething-spec databases.chooser AC5–AC8): each asked in turn
     * with the typed path ([unlock]: its password stored for the fingerprint when that is on for it). [locks] is the
     * app's lock count, read now: a lock since ends the run, and nothing typed after it opens anything. Typing cannot
     * open a database out of the window or the list, one opening already, nor one whose file or key file is not there
     * ([fileExists]), looked at when the run reaches it.
     */
    public fun run(ids: List<DatabaseId>, locks: () -> Int, fileExists: (FileLocation) -> Boolean): UnlockRun {
        val began = locks()
        return UnlockRun(
            ids = ids,
            cannotOpen = { id -> whyTypingCannotOpen(id, fileExists) },
            isOpen = { id -> sessions.session(id)?.session?.state?.value is SessionState.Unlocked },
        ) { id, password ->
            if (locks() != began) return@UnlockRun UnlockRun.Attempt.Stopped
            val database = sessions.session(id) ?: return@UnlockRun UnlockRun.Attempt.CannotOpen("closed")
            // The session keeps its own copy: the typed one goes at once, not after the key derivation.
            val outcome = unlock(database, password).also { password.wipe() }
                ?: return@UnlockRun if (database.session.state.value is SessionState.Unlocked) UnlockRun.Attempt.Opened else UnlockRun.Attempt.CannotOpen("opening")
            val ended = outcome.await()
            when {
                locks() != began -> UnlockRun.Attempt.Stopped
                sessions.session(id) !== database -> UnlockRun.Attempt.CannotOpen("closed")
                ended == UnlockOutcome.Opened -> UnlockRun.Attempt.Opened
                ended is UnlockOutcome.Failed -> UnlockRun.Attempt.Failed(ended.failure)
                else -> UnlockRun.Attempt.Stopped
            }
        }
    }

    /** Why typing cannot open [id] now: it is skipped by itself in the run; null: it can be asked. */
    private fun whyTypingCannotOpen(id: DatabaseId, fileExists: (FileLocation) -> Boolean): String? {
        val record = list.record(id) ?: return "not in the list"
        if (!record.inWindow) return "closed"
        if (sessions.session(id)?.session?.state?.value == SessionState.Unlocking) return "opening"
        if (!fileExists(record.file)) return "not found"
        record.keyFile?.takeUnless(fileExists)?.let { return "${it.fileName} not there" }
        return null
    }
}
