package dev.keepassbar.vault.session

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.storage.ConflictCopy
import dev.keepassbar.domain.storage.DatabaseBackups
import dev.keepassbar.domain.storage.DatabaseStore
import dev.keepassbar.domain.storage.FileVersion
import dev.keepassbar.domain.time.Clock
import dev.keepassbar.domain.vault.Vault
import dev.keepassbar.domain.vault.VaultEdit
import dev.keepassbar.kdbx.key.DatabaseKey
import dev.keepassbar.vault.opening.AttachmentContents
import dev.keepassbar.vault.opening.OpenFailure
import dev.keepassbar.vault.opening.OpenResult
import dev.keepassbar.vault.opening.VaultOpener
import dev.keepassbar.vault.saving.SaveFailure
import dev.keepassbar.vault.saving.SaveResult
import dev.keepassbar.vault.saving.VaultSaver
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
 * While unlocked it holds the vault, its attachments and the database key, and wipes them all on lock. It
 * watches the database's folder and reads the file again when it changed, with the key in memory.
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

    /**
     * Opens the vault with [password]; the caller may wipe its own copy as soon as this returns. The result
     * tells how this unlock ended once it has; null when the vault was not locked.
     */
    public fun unlock(password: Secret): Deferred<UnlockOutcome>? {
        if (mutableState.value !is SessionState.Locked) return null
        val token = generation
        val attempt = DatabaseKey.fromPassword(password)
        mutableState.value = SessionState.Unlocking
        val outcome = CompletableDeferred<UnlockOutcome>()
        scope.launch {
            try {
                val result = open(attempt)
                val copies = conflictCopies()
                if (token != generation) {
                    discard(result, attempt)
                    return@launch
                }
                when (result) {
                    is OpenResult.Opened -> {
                        key = attempt
                        adopt(result, copies)
                        watch = store.watch { scope.launch { fileChanged() } }
                        outcome.complete(UnlockOutcome.Opened)
                    }
                    is OpenResult.Failed -> {
                        attempt.wipe()
                        mutableState.value = SessionState.Locked(LockReason.UnlockFailed(result.failure))
                        outcome.complete(UnlockOutcome.Failed(result.failure))
                    }
                }
            } finally {
                outcome.complete(UnlockOutcome.Interrupted)
            }
        }
        return outcome
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
