package dev.keething.vault.session

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.DatabaseBackups
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
 * key in memory.
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
    /** How long the app may stay in the background before it locks (SPEC §4). */
    private val lockTimeout: () -> Duration,
    /** How long the folder must be quiet after a change before the file is read again (SPEC §3.2). */
    private val settleTime: Duration = 1.5.seconds,
    /** How long a missing file is waited for: Syncthing in the middle of a replace (SPEC §3.2). */
    private val missingPatience: Duration = 10.seconds,
    private val missingRetry: Duration = 1.seconds,
    /** The database's key file, null for none: asked for at every unlock and read then, never at a reload. */
    private val keyFile: () -> KeyFileSource? = { null },
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Locked(LockReason.Initial))
    public val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val saver = VaultSaver(store, backups)

    /** Reloads and saves take turns, each up to the vault it adopts: an older read never replaces a newer save. */
    private val fileWork = Mutex()
    private var saving = false

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
    private var timeoutJob: Job? = null
    private var backgroundSince: Duration? = null
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
        mutableState.value = SessionState.Unlocking
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
                mutableState.value = SessionState.Locked(LockReason.UnlockFailed(failure))
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
        mutableState.value = SessionState.Unlocking
        val outcome = CompletableDeferred<UnlockOutcome>()
        scope.launch {
            val waiting = ArrayDeque(listOf(Attempt(key, OpenFailure.WrongPassword)))
            try {
                val failure = tryInTurn(waiting, token, outcome) ?: return@launch
                if (token != generation) return@launch
                mutableState.value = SessionState.Locked(LockReason.UnlockFailed(failure))
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
            KeyFileRead.AccessDenied -> return OpenFailure.KeyFileUnusable("Keething may not read ${source.fileName} (allow all-files access)")
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
            val result = open(attempt.key)
            val copies = if (result is OpenResult.Opened) conflictCopies() else emptyList()
            waiting.removeFirst()
            if (token != generation) {
                discard(result, attempt.key)
                return null
            }
            when (result) {
                is OpenResult.Opened -> {
                    key = attempt.key
                    adopt(result, copies)
                    watch = store.watch { scope.launch { fileChanged() } }
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

    public fun lock(reason: LockReason = LockReason.Manual) {
        generation++
        settleJob?.cancel()
        timeoutJob?.cancel()
        watch?.close()
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
        mutableState.value = SessionState.Locked(reason)
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
        val result = try {
            withContext(io) { opener.history(store, attempt, entryId) }
        } finally {
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

    /** The folder watcher saw a change: read the file again once the folder is quiet. */
    public fun fileChanged() {
        if (mutableState.value !is SessionState.Unlocked) return
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(settleTime)
            checkForChanges()
        }
    }

    /** The app went to the background (turning the screen off does that too). */
    public fun movedToBackground() {
        backgroundSince = clock.elapsed()
        if (mutableState.value is SessionState.Locked) return
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(lockTimeout())
            if (mutableState.value !is SessionState.Locked) lock(LockReason.Timeout)
        }
    }

    /**
     * The app came back. The timer may not have run (a phone in deep sleep), so the time in the background
     * is checked here too; then the file is checked for a new version.
     */
    public fun movedToForeground() {
        timeoutJob?.cancel()
        val timedOut = backgroundTimedOut()
        backgroundSince = null
        if (mutableState.value is SessionState.Locked) return
        if (timedOut) return lock(LockReason.Timeout)
        if (mutableState.value is SessionState.Unlocked) scope.launch { checkForChanges() }
    }

    /**
     * A wake-up while in the background (an alarm): locks when the timeout has passed. Android freezes a
     * background app, so the timer of [movedToBackground] may not run until the app comes back.
     */
    public fun checkBackgroundTime() {
        if (mutableState.value !is SessionState.Locked && backgroundTimedOut()) lock(LockReason.Timeout)
    }

    private fun backgroundTimedOut(): Boolean {
        val since = backgroundSince ?: return false
        return clock.elapsed() - since >= lockTimeout()
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
            mutableState.value = unlocked.copy(conflictCopies = copies)
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
        var result = open(attempt)
        var waited = Duration.ZERO
        while (result.failure == OpenFailure.Missing && waited < missingPatience && token == generation) {
            delay(missingRetry)
            waited += missingRetry
            if (token == generation) result = open(attempt)
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
                mutableState.value = unlocked.copy(conflictCopies = copies, reloadFailure = result.failure)
            }
        }
        true
    }

    /**
     * Writes [edit] into the file (SPEC §3.3), with the key in memory; the vault then becomes the file read back.
     * One save at a time: null while one runs, and when not unlocked. Refused because the entry changed or went
     * elsewhere, the vault is read again; refused because the password changed elsewhere, the session locks.
     * The save reads [edit]'s secrets until the result is complete: wipe them only then.
     */
    public fun save(edit: VaultEdit): Deferred<SaveOutcome>? {
        if (mutableState.value !is SessionState.Unlocked || saving) return null
        val attempt = key?.copy() ?: return null
        saving = true
        val token = generation
        val outcome = CompletableDeferred<SaveOutcome>()
        scope.launch {
            try {
                fileWork.withLock {
                    val result = withContext(io) { saver.save(edit, attempt, clock.now()) }
                    val copies = conflictCopies()
                    if (token != generation) {
                        attempt.wipe()
                        if (result is SaveResult.Saved) discard(OpenResult.Opened(result.opened), attempt)
                        return@withLock
                    }
                    when (result) {
                        is SaveResult.Saved -> {
                            key?.wipe()
                            key = attempt
                            adopt(OpenResult.Opened(result.opened), copies)
                            outcome.complete(SaveOutcome.Saved)
                        }
                        is SaveResult.Failed -> {
                            attempt.wipe()
                            outcome.complete(SaveOutcome.Failed(result.failure))
                            afterRefusal(result.failure)
                        }
                    }
                }
            } finally {
                saving = false
                outcome.complete(SaveOutcome.Interrupted)
            }
        }
        return outcome
    }

    private fun afterRefusal(failure: SaveFailure) {
        when {
            failure == SaveFailure.CannotOpen(OpenFailure.WrongPassword) -> lock(LockReason.PasswordChanged)
            failure == SaveFailure.ChangedElsewhere || failure == SaveFailure.EntryNotFound -> reload()
        }
    }

    private fun adopt(result: OpenResult.Opened, copies: List<ConflictCopy>) {
        val opened = result.opened
        attachments?.wipe()
        attachments = opened.attachments
        (mutableState.value as? SessionState.Unlocked)?.vault?.let(retiredVaults::add)
        loadedVersion = opened.version
        triedVersion = opened.version
        mutableState.value = SessionState.Unlocked(opened.vault, copies, keyDerivation = opened.keyDerivation)
    }

    private suspend fun open(key: DatabaseKey): OpenResult = withContext(io) { opener.open(store, key) }

    private suspend fun conflictCopies(): List<ConflictCopy> =
        withContext(io) { runCatching { store.conflictCopies() }.getOrDefault(emptyList()) }

    private fun discard(result: OpenResult, attempt: DatabaseKey) {
        attempt.wipe()
        if (result is OpenResult.Opened) {
            result.opened.vault.wipe()
            result.opened.attachments.wipe()
        }
    }

    private val OpenResult.failure: OpenFailure? get() = (this as? OpenResult.Failed)?.failure
}

/** Why a key file cannot be used, as the lock screen says it (the Mac's `KeyFileLoader.reason`). */
internal fun KeyFileException.reason(): String = when (this) {
    is KeyFileException.Empty -> "the file is empty"
    is KeyFileException.UnsupportedVersion -> "a key file of version $version, which KeePass does not write"
    is KeyFileException.Malformed -> "a key file whose key cannot be read ($why)"
    is KeyFileException.HashMismatch -> "a key file whose key does not match its checksum (changed or damaged)"
}
