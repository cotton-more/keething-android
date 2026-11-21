package dev.keething.vault.databases

import dev.keething.domain.secret.Secret
import dev.keething.domain.text.UserText
import dev.keething.vault.VaultText
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.creating.DerivedKey
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SaveOutcome
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.UnlockOutcome
import dev.keething.domain.storage.StoragePlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
    /** The grants the app holds now (keething-spec databases.saf AC10); null: every one counts. */
    private val held: () -> Set<String>? = { null },
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

    /** [place] through a folder of the list whose grant is held ([DatabaseList.inGrantedFolder]). */
    private fun reach(place: StoragePlace, prefer: FileLocation? = null, held: Set<String>? = this.held()): FileLocation.InFolder? =
        list.inGrantedFolder(place, prefer, held)

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
    public fun locate(id: DatabaseId, file: FileLocation): UserText? {
        file.grant?.let(offered)
        val other = list.records.firstOrNull { it.id != id && it.file.isSameFile(file) }
        if (other != null) return UserText.of(VaultText.LOCATE_IN_LIST_ALREADY, other.displayName)
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
        val reached = (keyFile as? FileLocation.Granted)?.let { reach(it.at, prefer = record.file) } ?: keyFile
        list.update(id) { it.copy(keyFile = reached, opensWithoutPassword = false) }
        biometric.keyFileChanged(id)
        if (sessions.session(id)?.session?.state?.value is SessionState.Locked) sessions.lock(id, LockReason.Initial)
        adoptOldRecords()
    }

    /**
     * Change master key… (keething-spec databases.change-master-key AC4-AC6): [id]'s open database written with the key
     * of [password] (null: none) and [keyFileKey], the 32-byte key of the key file at [keyFile] (both null: none; the
     * one it has, to keep it). Once saved, its record has that key file (reached as [setKeyFile] reaches one) and whether
     * it opens with the key file alone, and it counts as a typed unlock ([BiometricUnlock.keyChanged]). The caller may
     * wipe [password] and [keyFileKey] as soon as this returns. How it ended; null when the database is not open.
     */
    public fun changeKey(id: DatabaseId, password: Secret?, keyFile: FileLocation?, keyFileKey: Secret?): Deferred<KeyChanged>? {
        require((keyFile == null) == (keyFileKey == null)) { "a key file comes with its key" }
        val database = sessions.session(id)?.takeIf { it.session.state.value is SessionState.Unlocked } ?: return null
        val outcome = database.session.changeKey(DatabaseKey.of(password, keyFileKey)) ?: return null
        val stored = password?.copy()
        return scope.async {
            try {
                val saved = outcome.await()
                if (saved !is SaveOutcome.Saved || list.record(id) == null) return@async KeyChanged(saved, null)
                val reached = (keyFile as? FileLocation.Granted)?.let { reach(it.at, prefer = list.record(id)?.file) } ?: keyFile
                list.update(id) { it.copy(keyFile = reached, opensWithoutPassword = password == null) }
                // Only now: offered while the save ran, a change of the list meanwhile would give it back (saf AC14).
                keyFile?.grant?.let(offered)
                adoptOldRecords()
                KeyChanged(saved, database.biometric.keyChanged(stored))
            } finally {
                stored?.wipe()
            }
        }
    }

    /** How [changeKey] ended: the save's [outcome], and why the new password could not be stored for the fingerprint. */
    public class KeyChanged(public val outcome: SaveOutcome, public val fingerprintProblem: String?)

    /**
     * An old record's one question (keething-spec databases.saf AC13): the folder chosen for a database the list kept by
     * its path, granted as [grant] at [folder] on the storage. It must hold the file at that path; the database is then
     * reached through it, with its id, its stored password, its backups and its choices, and so is its key file when a
     * folder of the list holds that one too. Every other old record the folder holds follows ([adoptOldRecords]). Its
     * session follows, as at [locate]. Why not, or null.
     */
    public fun folderGranted(id: DatabaseId, grant: String, folder: StoragePlace): UserText? {
        offered(grant)
        val record = list.record(id) ?: return UserText.of(VaultText.LOCATE_DATABASE_GONE)
        val before = record.file as? FileLocation.ByPath ?: return null
        val place = before.place ?: return UserText.of(VaultText.LOCATE_NOT_ON_STORAGE, record.fileName)
        val path = place.inside(folder)?.takeIf { it.isNotEmpty() } ?: return UserText.of(VaultText.LOCATE_FOLDER_LACKS_FILE, record.fileName)
        val file = FileLocation.InFolder(grant, folder, path)
        val keyFile = when (val key = record.keyFile) {
            is FileLocation.ByPath -> key.place?.let { reach(it, prefer = file) } ?: key
            else -> key
        }
        list.update(id) { it.copy(file = file, keyFile = keyFile) }
        adoptOldRecords()
        return null
    }

    /**
     * Old records through the folders the list is reached through (keething-spec databases.saf AC3, AC13): each database
     * or key file the list kept by its path that such a folder holds is reached through it, with everything else kept,
     * and nothing is asked for it. So is each one whose grant is gone (its folder renamed or moved away and back, AC10)
     * when a folder whose grant is held holds it: one folder granted again takes over all it holds. After each folder
     * the list takes, and at the app's start.
     */
    public fun adoptOldRecords() {
        val held = held()
        fun lost(location: FileLocation) = location is FileLocation.ByPath || held != null && location.grant !in held
        for (record in list.records) {
            val file = record.file.takeIf(::lost)?.place?.let { reach(it, held = held) } ?: record.file
            val keyFile = record.keyFile?.let { key -> key.takeIf(::lost)?.place?.let { reach(it, prefer = file, held = held) } ?: key }
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
     * opens with [key], the key it was made with, as a typed unlock of [password] (its days start; stored for the fingerprint when chosen,
     * the app's switch turned on with it when it was never set). A lock since
     * Create was tapped ([lockedSince], asked once the list has it) leaves it locked and wipes [key]. The caller may
     * wipe [password] as soon as this returns. [fingerprintChosen]: the form offered the fingerprint, so it was chosen
     * either way and its lock screen does not ask it (keething-spec unlock.biometric AC4).
     */
    public suspend fun created(
        file: FileLocation,
        keyFile: FileLocation?,
        useBiometric: Boolean,
        key: DerivedKey,
        password: Secret,
        fingerprintChosen: Boolean = useBiometric,
        lockedSince: () -> Boolean,
    ): Created {
        listOfNotNull(file.grant, keyFile?.grant).forEach(offered)
        // Checked on the form while the app's switch was never set: the same Yes as the question's, the app's on too
        // (keething-spec unlock.biometric AC4). A leftover that could not be deleted keeps it off: nothing is stored then.
        val switchProblem = if (useBiometric && !biometric.state.value.enabled && !biometric.state.value.set) biometric.setEnabled(true) else null
        val reached = (keyFile as? FileLocation.Granted)?.let { reach(it.at, prefer = file) } ?: keyFile
        // One change: the old record out and the new one in with its key file, so no grant looks unused in between.
        val (record, old) = list.replace(file, reached, useBiometric, fingerprintAsked = fingerprintChosen)
        show(record.id)
        adoptOldRecords()
        val problem = listOfNotNull(switchProblem, old?.let { biometric.forget(it.id) }).joinToString("; ").ifEmpty { null }
        val database = current.value?.takeIf { it.id == record.id }
        if (database == null || lockedSince()) {
            key.wipe()
            return Created(record.id, opened = null, problem, old?.id)
        }
        return Created(record.id, database.biometric.unlockCreated(key, password), problem, old?.id)
    }

    /**
     * What [created] did: the new database's [id], how its opening ends (null: not opened), why an old one's stored
     * password stayed or a leftover kept the app's switch off ([problem]), and the record it [replaced] (its backups are
     * the caller's to delete).
     */
    public class Created(public val id: DatabaseId, public val opened: Deferred<UnlockOutcome>?, public val problem: String?, public val replaced: DatabaseId? = null)

    /**
     * The typed unlock of [database] (nothing typed with a key file too), as [BiometricUnlock.unlock]. With a key file
     * its record learns whether it opens with the key file alone: opened with nothing typed it does, opened with a
     * password it does not, and nothing typed rejected it does not (a password was added elsewhere). A key file not
     * there teaches nothing, nor does an unlock whose key file changed meanwhile (one whose session went, its file
     * located say, ends interrupted); another database chosen meanwhile does not stop it. The caller may wipe
     * [password] as soon as this returns. How it ends; null when nothing was started (the vault was not locked).
     */
    public fun unlock(database: DatabaseSession, password: Secret, askFingerprint: Boolean = false): Deferred<UnlockOutcome>? {
        val keyFile = list.record(database.id)?.keyFile
        val nothingTyped = password.isEmpty()
        val outcome = database.biometric.unlock(password, askFingerprint) ?: return null
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
     * ([fileExists], [FilesThere]), looked at when the run reaches it.
     */
    public fun run(ids: List<DatabaseId>, locks: () -> Int, fileExists: suspend (FileLocation) -> Boolean): UnlockRun {
        val began = locks()
        return UnlockRun(
            ids = ids,
            cannotOpen = { id -> whyTypingCannotOpen(id, fileExists) },
            isOpen = { id -> sessions.session(id)?.session?.state?.value is SessionState.Unlocked },
        ) { id, password ->
            if (locks() != began) return@UnlockRun UnlockRun.Attempt.Stopped
            val database = sessions.session(id) ?: return@UnlockRun UnlockRun.Attempt.CannotOpen(UserText.of(VaultText.WHY_CLOSED))
            // The session keeps its own copy: the typed one goes at once, not after the key derivation.
            // The chooser shows the list after it: a database never asked about the fingerprint is asked over it.
            val outcome = unlock(database, password, askFingerprint = true).also { password.wipe() }
                ?: return@UnlockRun if (database.session.state.value is SessionState.Unlocked) UnlockRun.Attempt.Opened else UnlockRun.Attempt.CannotOpen(UserText.of(VaultText.WHY_OPENING))
            val ended = outcome.await()
            when {
                locks() != began -> UnlockRun.Attempt.Stopped
                sessions.session(id) !== database -> UnlockRun.Attempt.CannotOpen(UserText.of(VaultText.WHY_CLOSED))
                ended == UnlockOutcome.Opened -> UnlockRun.Attempt.Opened
                ended is UnlockOutcome.Failed -> UnlockRun.Attempt.Failed(ended.failure)
                else -> UnlockRun.Attempt.Stopped
            }
        }
    }

    /** Why typing cannot open [id] now: it is skipped by itself in the run; null: it can be asked. */
    private suspend fun whyTypingCannotOpen(id: DatabaseId, fileExists: suspend (FileLocation) -> Boolean): UserText? {
        val record = list.record(id) ?: return UserText.of(VaultText.WHY_NOT_IN_LIST)
        if (!record.inWindow) return UserText.of(VaultText.WHY_CLOSED)
        if (sessions.session(id)?.session?.state?.value == SessionState.Unlocking) return UserText.of(VaultText.WHY_OPENING)
        if (!fileExists(record.file)) return UserText.of(VaultText.WHY_NOT_FOUND)
        record.keyFile?.let { if (!fileExists(it)) return UserText.of(VaultText.WHY_KEY_FILE_NOT_THERE, it.fileName) }
        return null
    }
}
