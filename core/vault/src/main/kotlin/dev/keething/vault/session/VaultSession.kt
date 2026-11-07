package dev.keething.vault.session

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import dev.keething.domain.storage.KeyFileRead
import dev.keething.domain.storage.KeyFileSource
import dev.keething.domain.time.Clock
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.KeyFile
import dev.keething.kdbx.key.KeyFileException
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.opening.AttachmentContents
import dev.keething.vault.opening.HistoryResult
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.saving.SaveResult
import dev.keething.vault.saving.VaultSaver
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The lock / unlock / reload lifecycle of the vault (SPEC §3.2, §4; Mac `VaultSession`).
 *
 * While unlocked it holds the vault, its attachments and the database key (of the password, the key file, or both),
 * and wipes them all on lock. It watches the database's folder and reads the file again when it changed, with the
 * key in memory. The app's coordinator ([dev.keething.vault.databases.VaultSessions]) holds one per database of the
 * window and locks them all at its timeout.
 *
 * Call it from [scope]'s thread (the main thread); file reads and the key derivation run on [io]. A read
 * in progress is never cancelled (the key derivation cannot stop halfway): a lock makes its result be
 * wiped when it arrives. Every read works on a copy of the key, so a lock can wipe the session's own
 * at once.
 */
public class VaultSession(
    private val store: DatabaseStore,
    private val opener: VaultOpener,
    /** Where a save keeps the file it replaces (SPEC §3.3 step 7). */
    backups: DatabaseBackups,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** How long the folder must be quiet after a change before the file is read again (SPEC §3.2). */
    private val settleTime: Duration = 1.5.seconds,
    /** How long a missing file is waited for: Syncthing in the middle of a replace (SPEC §3.2). */
    private val missingPatience: Duration = 10.seconds,
    private val missingRetry: Duration = 1.seconds,
    /** The database's key file, null for none: asked for at every unlock and read then, never at a reload. */
    private val keyFile: () -> KeyFileSource? = { null },
    /**
     * How long a save the lock overtook may still run (keething-spec kdbx.hardening AC3), counted on [scope]'s clock: the
     * main thread's, which stands still while the phone sleeps, so only time awake counts.
     */
    private val saveGrace: Duration = 10.seconds,
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Locked(LockReason.Initial))
    public val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** Told on the main thread of every change refused because the database is viewed only ([save]; keething-spec kdbx.kdbx3 AC6). */
    public var onViewOnlyRefusal: (() -> Unit)? = null

    /** Told on the main thread how long each save took in the file, and whether it was saved (keething-spec app.large-database). */
    public var onSaveTimed: ((took: Duration, saved: Boolean) -> Unit)? = null

    /** Told at once of every state set, before anyone collecting [state] (the coordinator follows it so). */
    internal var onStateChange: ((SessionState) -> Unit)? = null

    private val saver = VaultSaver(store, backups)

    /**
     * Reloads and saves take turns, each up to the vault it adopts: an older read never replaces a newer save. Saves
     * wait for each other in the order they were asked for, so each reads the file the one before it wrote
     * (keething-spec kdbx.hardening AC4).
     */
    private val fileWork = Mutex()

    /** The saves running or waiting their turn, in the order asked for. */
    private val saves = mutableListOf<RunningSave>()

    /** The changes a lock kept out of the file, until an unlock said them (AC3): kept through the lock, ids only. */
    private var unsaved: List<UnsavedChange> = emptyList()

    /**
     * The keys an unlock, a reload or a history read is opening the file with: a lock gives them up at once, so a key
     * derivation running with one stops (keething-spec kdbx.hardening AC6).
     */
    private val reading = mutableSetOf<DatabaseKey>()

    private var key: DatabaseKey? = null
    private var attachments: AttachmentContents? = null

    /** Earlier vaults of this session, wiped at the lock: a screen may still show one of their entries. */
    private val retiredVaults = mutableListOf<Vault>()
    private var loadedVersion: FileVersion? = null

    /** The version a reload was last started for: the same version is not read again (a damaged file). */
    private var triedVersion: FileVersion? = null

    /** Incremented on every lock: results of work started before it are wiped, not used. */
    private var generation = 0

    /** The generation of the running reload; -1 when none runs. */
    private var reloadGeneration = -1

    /** The file changed again while a reload ran: read it once more when that one is done. */
    private var reloadPending = false
    private var watch: AutoCloseable? = null
    private var settleJob: Job? = null
    private var closed = false

    /** Its database is no longer the one shown ([close]): nothing opens it again. */
    public val isClosed: Boolean get() = closed

    /** How many times it was locked: one that came meanwhile changes it. */
    public val locks: Int get() = generation

    /**
     * Opens the vault with [password] and the database's key file, when it has one (keething-spec kdbx.key-files):
     * the key file is read now, and a missing or unusable one fails the unlock before the database is read. Nothing
     * typed with a key file: the key file alone, then once the empty password with it, as one Unlocking (KeePassXC
     * does the same: the file cannot say which it has). The caller may wipe its own [password] as soon as this
     * returns. The result tells how this unlock ended once it has; null when the vault was not locked.
     */
    public fun unlock(password: Secret): Deferred<UnlockOutcome>? {
        if (closed || mutableState.value !is SessionState.Locked) return null
        val token = generation
        val typed = password.copy()
        val source = keyFile()
        set(SessionState.Unlocking)
        val outcome = CompletableDeferred<UnlockOutcome>()
        scope.launch {
            val waiting = ArrayDeque<Attempt>()
            try {
                val noKeys = try {
                    withContext(io) { attempts(typed, source, waiting) }
                } finally {
                    typed.wipe()
                }
                val failure = noKeys ?: tryInTurn(waiting, token, outcome) ?: return@launch
                if (token != generation) return@launch
                set(SessionState.Locked(LockReason.UnlockFailed(failure)))
                outcome.complete(UnlockOutcome.Failed(failure))
            } finally {
                waiting.forEach { it.key.wipe() }
                outcome.complete(UnlockOutcome.Interrupted)
            }
        }
        return outcome
    }

    /**
     * Opens the vault with [key], the very key a new database was just made with (keething-spec databases.new-database
     * AC8): what was derived for the file is in it, so nothing is derived again, and no key file is read. The session
     * takes [key] over: it wipes it at the lock, or at once when it does not open the vault or the vault was not
     * locked (null then).
     */
    internal fun unlockAdopting(key: DatabaseKey): Deferred<UnlockOutcome>? {
        if (closed || mutableState.value !is SessionState.Locked) {
            key.wipe()
            return null
        }
        val token = generation
        set(SessionState.Unlocking)
        val outcome = CompletableDeferred<UnlockOutcome>()
        scope.launch {
            val waiting = ArrayDeque(listOf(Attempt(key, OpenFailure.WrongPassword)))
            try {
                val failure = tryInTurn(waiting, token, outcome) ?: return@launch
                if (token != generation) return@launch
                set(SessionState.Locked(LockReason.UnlockFailed(failure)))
                outcome.complete(UnlockOutcome.Failed(failure))
            } finally {
                waiting.forEach { it.key.wipe() }
                outcome.complete(UnlockOutcome.Interrupted)
            }
        }
        return outcome
    }

    /** A key to try, and what its rejection is called (it names what was given). */
    private class Attempt(val key: DatabaseKey, val rejected: OpenFailure)

    /**
     * Puts the keys to try for [typed] with [source]'s key file, read now, into [waiting] (whoever holds it wipes
     * them); or says why there are none. Blocks.
     */
    private fun attempts(typed: Secret, source: KeyFileSource?, waiting: ArrayDeque<Attempt>): OpenFailure? {
        if (source == null) {
            waiting += Attempt(DatabaseKey.of(typed), OpenFailure.WrongPassword)
            return null
        }
        val read = try {
            source.read()
        } catch (_: IOException) {
            return OpenFailure.KeyFileUnusable("${source.fileName} could not be read")
        } catch (_: OutOfMemoryError) {
            return OpenFailure.KeyFileUnusable("${source.fileName} is too large to read")
        }
        val bytes = when (read) {
            is KeyFileRead.Found -> read.bytes
            KeyFileRead.Missing -> return OpenFailure.KeyFileMissing(source.fileName)
            KeyFileRead.AccessDenied -> return OpenFailure.KeyFileUnusable("Keething may not read ${source.fileName} any more: choose it again")
        }
        val fileKey = try {
            KeyFile.key(bytes)
        } catch (failure: KeyFileException) {
            return OpenFailure.KeyFileUnusable(failure.reason())
        } finally {
            bytes.fill(0)
        }
        try {
            if (typed.isEmpty()) {
                waiting += Attempt(DatabaseKey.of(null, fileKey), OpenFailure.WrongKeyFile)
                waiting += Attempt(DatabaseKey.of(typed, fileKey), OpenFailure.WrongKeyFile)
            } else {
                waiting += Attempt(DatabaseKey.of(typed, fileKey), OpenFailure.WrongPasswordOrKeyFile)
            }
        } finally {
            fileKey.wipe()
        }
        return null
    }

    /**
     * Opens the file with each key of [waiting] in turn while the database rejects them; the first one that opens it
     * becomes the session's. Null when it opened, or when a lock came first (the outcome is complete then); else
     * what the first rejection is called, or the failure that was not a wrong key (not tried again).
     */
    private suspend fun tryInTurn(waiting: ArrayDeque<Attempt>, token: Int, outcome: CompletableDeferred<UnlockOutcome>): OpenFailure? {
        var failure: OpenFailure? = null
        while (waiting.isNotEmpty()) {
            if (token != generation) return null
            // Left in [waiting] until this try is over: the caller wipes what is there, whatever ends the unlock.
            val attempt = waiting.first()
            val result = opening(attempt.key)
            // The watch starts with the conflict copies' read, on [io]: both may be the storage provider's calls.
            val opening = if (result is OpenResult.Opened) withContext(io) { copiesNow() to store.watch { scope.launch { fileChanged() } } } else null
            waiting.removeFirst()
            if (token != generation) {
                opening?.second?.let(::closeWatch)
                discard(result, attempt.key)
                return null
            }
            when (result) {
                is OpenResult.Opened -> {
                    key = attempt.key
                    val (copies, started) = checkNotNull(opening)
                    watch = started
                    adopt(result, copies)
                    // Told at once (the coordinator): a lock that caused wiped it all, and this unlock did not happen.
                    if (token != generation) return null
                    outcome.complete(UnlockOutcome.Opened)
                    return null
                }
                is OpenResult.Failed -> {
                    attempt.key.wipe()
                    val wrongKey = result.failure == OpenFailure.WrongPassword
                    failure = failure ?: if (wrongKey) attempt.rejected else result.failure
                    if (!wrongKey) break
                }
            }
        }
        return failure
    }

    /**
     * Its database is no longer the one shown (another became current, its file moved, it left the list): locked, and
     * nothing opens it again, so a fingerprint prompt still up, or a screen of before, cannot open it behind the next.
     */
    public fun close() {
        closed = true
        lock(LockReason.Initial)
    }

    /**
     * Locks at once: the vault, its attachments and the key are wiped, and no save starts any more. A save already
     * running may finish for [saveGrace] (keething-spec kdbx.hardening AC3); then it stops before the backup and the
     * replace, its key given up and wiped, and the next unlock says which change was not saved.
     */
    public fun lock(reason: LockReason = LockReason.Manual) {
        generation++
        reading.forEach(DatabaseKey::abandon)
        reading.clear()
        for (running in saves) {
            if (running.deadline == null) running.deadline = scope.launch {
                delay(saveGrace)
                running.stop()
            }
        }
        settleJob?.cancel()
        watch?.let(::closeWatch)
        watch = null
        key?.wipe()
        key = null
        attachments?.wipe()
        attachments = null
        (mutableState.value as? SessionState.Unlocked)?.vault?.wipe()
        retiredVaults.forEach(Vault::wipe)
        retiredVaults.clear()
        loadedVersion = null
        triedVersion = null
        reloadPending = false
        set(SessionState.Locked(reason))
    }

    /**
     * The versions of [entryId] (SPEC §6.2): the file read again with the key in memory (no key derivation),
     * keeping that entry's history. Null when not unlocked, or locked before the read was done (its result is
     * wiped then). The caller wipes the versions when it no longer shows them.
     */
    public suspend fun history(entryId: String): HistoryResult? {
        if (mutableState.value !is SessionState.Unlocked) return null
        val token = generation
        val attempt = key?.copy() ?: return null
        reading += attempt
        val result = try {
            withContext(io) { opener.history(store, attempt, entryId) }
        } finally {
            reading -= attempt
            attempt.wipe()
        }
        if (token != generation) {
            (result as? HistoryResult.Read)?.versions?.wipe()
            return null
        }
        return result
    }

    /** A copy of one attachment's bytes (the caller wipes it); null when locked or there is none. */
    public fun attachment(entryId: String, name: String): Secret? = attachments?.bytes(entryId, name)

    /**
     * The app said that a save it did not finish was put right ([SessionState.Unlocked.stoppedSave], keething-spec
     * databases.saf AC5): the store says it no more.
     */
    public fun stoppedSaveSaid() {
        val unlocked = mutableState.value as? SessionState.Unlocked ?: return
        if (unlocked.stoppedSave == null) return
        set(unlocked.copy(stoppedSave = null))
        scope.launch(io) { runCatching { store.stoppedSaveSaid() } }
    }

    /** The folder watcher saw a change: read the file again once the folder is quiet. */
    public fun fileChanged() {
        if (mutableState.value !is SessionState.Unlocked) return
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(settleTime)
            checkForChanges()
        }
    }

    /**
     * The app came back: the file is checked for a new version, which the watcher may have missed while the app was
     * frozen. The lock timeout is the coordinator's.
     */
    public fun checkFile() {
        if (mutableState.value is SessionState.Unlocked) scope.launch { checkForChanges() }
    }

    private suspend fun checkForChanges() {
        val token = generation
        val version = withContext(io) { runCatching { store.version() }.getOrNull() }
        val copies = conflictCopies()
        val unlocked = mutableState.value as? SessionState.Unlocked ?: return
        if (token != generation) return
        if (version != loadedVersion && version != triedVersion) {
            triedVersion = version
            reload()
        } else if (copies != unlocked.conflictCopies) {
            set(unlocked.copy(conflictCopies = copies))
        }
    }

    /** Reads the file again with the key in memory. One asked for while one runs runs right after it. */
    public fun reload() {
        if (mutableState.value !is SessionState.Unlocked) return
        if (reloadGeneration == generation) {
            reloadPending = true
            return
        }
        val token = generation
        reloadGeneration = token
        scope.launch {
            try {
                do {
                    reloadPending = false
                    if (!reloadOnce(token)) return@launch
                } while (reloadPending)
            } finally {
                if (reloadGeneration == token) reloadGeneration = -1
            }
        }
    }

    /** False when the session was locked meanwhile. */
    private suspend fun reloadOnce(token: Int): Boolean = fileWork.withLock {
        val attempt = key?.copy() ?: return false
        var result = opening(attempt)
        var waited = Duration.ZERO
        while (result.failure == OpenFailure.Missing && waited < missingPatience && token == generation) {
            delay(missingRetry)
            waited += missingRetry
            if (token == generation) result = opening(attempt)
        }
        val copies = conflictCopies()
        if (token != generation) {
            discard(result, attempt)
            return false
        }
        when (result) {
            is OpenResult.Opened -> {
                key?.wipe()
                key = attempt
                adopt(result, copies)
            }
            is OpenResult.Failed -> {
                attempt.wipe()
                if (result.failure == OpenFailure.WrongPassword) {
                    lock(LockReason.PasswordChanged)
                    return false
                }
                val unlocked = mutableState.value as? SessionState.Unlocked ?: return false
                set(unlocked.copy(conflictCopies = copies, reloadFailure = result.failure))
            }
        }
        true
    }

    /**
     * Writes [edit] into the file (SPEC §3.3), an entry's or the groups', with the key in memory; the vault then becomes
     * the file read back, and [SaveOutcome.Saved] names the groups the save deleted. Saves run one at a time, each after
     * the one asked for before it, reading the file that one left (keething-spec kdbx.hardening AC4); null when not
     * unlocked. Refused because the entry changed or went elsewhere, or the file's groups no longer allow the change,
     * the vault is read again; refused because the password changed elsewhere, the session locks. A lock lets a running
     * save finish for a while ([lock]); one still waiting its turn then does not start. The save reads [edit]'s secrets
     * until the result is complete: wipe them only then. A database viewed only refuses it at once, the file not read
     * ([SaveFailure.ViewOnly]).
     */
    public fun save(edit: VaultEdit): Deferred<SaveOutcome>? {
        val unlocked = mutableState.value as? SessionState.Unlocked ?: return null
        if (unlocked.viewOnly) {
            onViewOnlyRefusal?.invoke()
            return CompletableDeferred(SaveOutcome.Failed(SaveFailure.ViewOnly))
        }
        return saving(edit) { attempt, running -> saver.save(edit, attempt, clock.now(), running::stopped) }
    }

    /**
     * Whether the database file is KDBX 3.1, told from its first 12 bytes, before any key (keething-spec kdbx.kdbx3 AC5);
     * false when it cannot be read or is no KeePass file. Blocks on the storage: call it off the main thread.
     */
    public fun isKdbx3File(): Boolean {
        val read = runCatching { store.read() }.getOrNull() as? DatabaseRead.Found ?: return false
        return try {
            KdbxReader.isKdbx3(read.bytes)
        } catch (_: KdbxException) {
            false
        }
    }

    /**
     * Upgrades the database viewed only, a KDBX 3.1 one, to KDBX 4.0 (keething-spec kdbx.kdbx3 AC7, AC8): its 3.1 file kept
     * beside it first, then written as KDBX 4.0 like a save, in its turn among the saves; the vault then becomes the
     * new file read back, a KDBX 4 database like any other. Null when not unlocked, or not viewed only.
     */
    public fun upgrade(): Deferred<SaveOutcome>? {
        val unlocked = mutableState.value as? SessionState.Unlocked ?: return null
        if (!unlocked.viewOnly) return null
        return saving(null) { attempt, running -> saver.upgrade(attempt, clock.now(), running::stopped) }
    }

    /** Runs [save] in its turn with a copy of the key ([edit]: what a lock that overtook it kept out of the file). */
    private fun saving(edit: VaultEdit?, save: (DatabaseKey, RunningSave) -> SaveResult): Deferred<SaveOutcome>? {
        val attempt = key?.copy() ?: return null
        val token = generation
        val running = RunningSave(attempt)
        saves += running
        val outcome = CompletableDeferred<SaveOutcome>()
        scope.launch {
            var inFile = false
            try {
                fileWork.withLock {
                    // A lock came while it waited for the save before it: it does not start (AC3).
                    if (token != generation) return@withLock
                    val started = TimeSource.Monotonic.markNow()
                    val result = withContext(io) { save(attempt, running) }
                    onSaveTimed?.invoke(started.elapsedNow(), result is SaveResult.Saved)
                    inFile = result is SaveResult.Saved
                    val copies = conflictCopies()
                    if (token != generation) {
                        if (result is SaveResult.Saved) discard(OpenResult.Opened(result.opened), attempt)
                        return@withLock
                    }
                    when (result) {
                        is SaveResult.Saved -> {
                            key?.wipe()
                            key = attempt
                            running.keyTaken = true
                            adopt(OpenResult.Opened(result.opened), copies)
                            outcome.complete(SaveOutcome.Saved(result.deletedGroups, result.keptCopy))
                        }
                        is SaveResult.Failed -> {
                            outcome.complete(SaveOutcome.Failed(result.failure))
                            afterRefusal(result.failure)
                        }
                    }
                }
            } finally {
                saves -= running
                running.deadline?.cancel()
                if (!running.keyTaken) attempt.wipe()
                // Overtaken by a lock, and the file does not have it: the next unlock says so (this one, if it came first).
                if (token != generation && !inFile && edit != null) {
                    unsaved = unsaved + UnsavedChange.of(edit)
                    (mutableState.value as? SessionState.Unlocked)?.let { set(it.copy(unsaved = unsaved)) }
                }
                outcome.complete(SaveOutcome.Interrupted)
            }
        }
        return outcome
    }

    /** A save running or waiting its turn; [stop] is the lock's grace running out. */
    private inner class RunningSave(val key: DatabaseKey) {
        /** Asked by the saver right before the backup and the replace. */
        @Volatile
        var stopped = false
            private set
        var deadline: Job? = null

        /** The save became the session's: its key is the session's now. */
        var keyTaken = false

        /** Stops it at its next check, its key given up at once and wiped as soon as no derivation holds it. */
        fun stop() {
            stopped = true
            key.abandon()
            scope.launch(io) { key.wipe() }
        }
    }

    /** The app said the changes a lock kept out of the file ([SessionState.Unlocked.unsaved]): they are said no more. */
    public fun unsavedSaid() {
        unsaved = emptyList()
        val unlocked = mutableState.value as? SessionState.Unlocked ?: return
        if (unlocked.unsaved.isNotEmpty()) set(unlocked.copy(unsaved = emptyList()))
    }

    private fun afterRefusal(failure: SaveFailure) {
        when {
            failure == SaveFailure.CannotOpen(OpenFailure.WrongPassword) -> lock(LockReason.PasswordChanged)
            failure == SaveFailure.ChangedElsewhere || failure == SaveFailure.EntryNotFound -> reload()
            // The file's groups are not what the vault shows: a group gone, a name taken, a group no longer empty.
            failure == SaveFailure.GroupNotFound || failure == SaveFailure.GroupNameTaken ||
                failure == SaveFailure.GroupNotChangeable || failure == SaveFailure.GroupNotEmpty -> reload()
        }
    }

    private fun adopt(result: OpenResult.Opened, copies: List<ConflictCopy>) {
        val opened = result.opened
        attachments?.wipe()
        attachments = opened.attachments
        (mutableState.value as? SessionState.Unlocked)?.vault?.let(retiredVaults::add)
        loadedVersion = opened.version
        triedVersion = opened.version
        // A stopped save put right stays said until the app said it, whatever the reads after it say.
        val stoppedSave = opened.stoppedSave ?: (mutableState.value as? SessionState.Unlocked)?.stoppedSave
        val unlocked = SessionState.Unlocked(
            opened.vault, copies, keyDerivation = opened.keyDerivation, stoppedSave = stoppedSave, unsaved = unsaved, viewOnly = opened.viewOnly,
        )
        set(unlocked)
        // The same contents read again: the state keeps the equal one before, so this copy is nobody's.
        if (mutableState.value !== unlocked) opened.vault.wipe()
    }

    private suspend fun open(key: DatabaseKey): OpenResult = withContext(io) { opener.open(store, key) }

    /** [open], [key] given up by a lock meanwhile ([reading]). */
    private suspend fun opening(key: DatabaseKey): OpenResult {
        reading += key
        try {
            return open(key)
        } finally {
            reading -= key
        }
    }

    private suspend fun conflictCopies(): List<ConflictCopy> = withContext(io) { copiesNow() }

    private fun copiesNow(): List<ConflictCopy> = runCatching { store.conflictCopies() }.getOrDefault(emptyList())

    /** Closed on [io]: closing may be the storage provider's call; it never waits for a save. */
    private fun closeWatch(watch: AutoCloseable) {
        scope.launch(io) { watch.close() }
    }

    private fun discard(result: OpenResult, attempt: DatabaseKey) {
        attempt.wipe()
        if (result is OpenResult.Opened) {
            result.opened.vault.wipe()
            result.opened.attachments.wipe()
        }
    }

    private val OpenResult.failure: OpenFailure? get() = (this as? OpenResult.Failed)?.failure

    /** The new state, told at once to [onStateChange] as [state] keeps it (an equal one leaves the one before). */
    private fun set(new: SessionState) {
        mutableState.value = new
        onStateChange?.invoke(mutableState.value)
    }
}

/** Why a key file cannot be used, as the lock screen says it (the Mac's `KeyFileLoader.reason`). */
internal fun KeyFileException.reason(): String = when (this) {
    is KeyFileException.Empty -> "the file is empty"
    is KeyFileException.UnsupportedVersion -> "a key file of version $version, which KeePass does not write"
    is KeyFileException.Malformed -> "a key file whose key cannot be read ($why)"
    is KeyFileException.HashMismatch -> "a key file whose key does not match its checksum (changed or damaged)"
}
