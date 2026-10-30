package dev.keething.vault.databases

import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.time.Clock
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.VaultSession
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * A database's session in the window: what the screens unlock, show and save through. [number] tells the sessions of
 * this process apart: a database put in the window again, or its file located, starts a new one, and a screen must not
 * keep the old.
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
     * [keyFilePath] is the database's key file as the list has it at each unlock; [scope] ends when the session is
     * dropped (for watching it).
     */
    public fun start(record: DatabaseRecord, number: Int, keyFilePath: () -> String?, scope: CoroutineScope): DatabaseSession
}

/** The app's state over the window's databases (the Mac's `SessionsState`): what the screens show. */
public sealed interface SessionsState {
    /** None is open or opening; [reason]: why the last one locked, or did not open. */
    public data class NothingOpen(val reason: LockReason) : SessionsState

    /** None is open, and one or more are opening. */
    public data object Opening : SessionsState

    /** The open databases' vaults, in the window's order; others may be opening meanwhile. */
    public data class Open(val vaults: List<OpenVault>) : SessionsState
}

/** An open database's vault. */
public data class OpenVault(val id: DatabaseId, val vault: Vault)

/**
 * An entry of an open database as its own vault has it ([entry] with its own id), and the [database] whose session
 * reads and saves it (plan AD9; keething-spec databases.one-list AC2).
 */
public class OpenEntry(public val database: DatabaseSession, public val entry: Entry, public val inRecycleBin: Boolean)

/**
 * The window's databases, each with its own session, and the app's state over them (plan AD8; the Mac's D5
 * `VaultSessions`).
 *
 * - Which databases are the window's is the list's ([DatabaseRecord.inWindow], kept with it), and the list's current
 *   database is always one of them: it goes in when it is not (keething-spec databases.several-open AC9). A session is
 *   made when a database joins the window, and dropped (locked, and never opened again) when it leaves the window or
 *   the list, or when its file moved (Locate…: a new session for the new file). Each session is its file's own — its
 *   key, vault, attachments, reloads and saves — and is wiped at its own lock: nothing is shared (AC1).
 * - Lock and the background timeout lock all (AC3); one database can be locked or closed alone (AC4). A reload that
 *   finds a database's password changed locks that one only (AC6): its session does.
 * - One background timeout for the app (SPEC §4, AC5): it runs while the app is in the background and a database is
 *   open or opening, from the moment the app left (or the first database began to open there) until the last one
 *   locks. Coming back is the activity that postpones it for all; the time is checked again then and at every wake-up
 *   ([checkBackgroundTime]), since Android freezes a background app and its timer with it.
 * - The app's [state] is told once per change: another database opening, or failing to, while one is open shows the
 *   same.
 *
 * Call it on [scope]'s thread (the main thread).
 */
public class VaultSessions(
    private val list: DatabaseList,
    private val scope: CoroutineScope,
    private val factory: SessionFactory,
    private val clock: Clock,
    /** How long the app may stay in the background with a database open (SPEC §4); read when it is needed. */
    private val lockTimeout: () -> Duration,
    private val now: () -> Instant = Instant::now,
) {
    /** A database's session, and the scope that ends with it. */
    private class Started(val database: DatabaseSession, val scope: CoroutineScope)

    private val sessions = mutableMapOf<DatabaseId, Started>()
    private var made = 0

    /** The databases open since their last lock: an unlock is when a database was last opened, a reload is not. */
    private val opened = mutableSetOf<DatabaseId>()

    private val mutableState = MutableStateFlow<SessionsState>(SessionsState.NothingOpen(LockReason.Initial))

    /** Nothing open (and why), opening, or the open vaults. */
    public val state: StateFlow<SessionsState> = mutableState.asStateFlow()

    private val mutableCurrent = MutableStateFlow<DatabaseSession?>(null)

    /** The session of the list's current database: the one the lock screen shows. */
    public val current: StateFlow<DatabaseSession?> = mutableCurrent.asStateFlow()

    /** Why the last database that locked did so: the app's reason while nothing is open. */
    private var lastReason: LockReason = LockReason.Initial

    /** Several sessions lock at once: the app's state is told once, at the end. */
    private var batching = false
    private val lockObservers = mutableListOf<(LockReason) -> Unit>()
    private val timerObservers = mutableListOf<(Duration) -> Unit>()

    /** When the app left, or the first database began to open since; null in the foreground. */
    private var backgroundSince: Duration? = null
    private var timeoutJob: Job? = null

    /** A database was open or opening when the timer was last looked at. */
    private var active = false

    init {
        list.observe(::follow)
        follow()
    }

    /** The window's databases, in the list's order. */
    public val windowIds: List<DatabaseId> get() = list.window.map { it.id }

    /** The open ones, in the window's order. */
    public val openIds: List<DatabaseId> get() = windowIds.filter { sessions[it]?.database?.session?.state?.value is SessionState.Unlocked }

    /** A database's session, while it is in the window. */
    public fun session(id: DatabaseId): DatabaseSession? = sessions[id]?.database

    /** The entry of [ref] in its own database, while that one is open (in its recycle bin too); null otherwise. */
    public fun entry(ref: EntryRef): OpenEntry? {
        val database = sessions[ref.database]?.database ?: return null
        val vault = (database.session.state.value as? SessionState.Unlocked)?.vault ?: return null
        vault.entries.firstOrNull { it.id == ref.entry }?.let { return OpenEntry(database, it, inRecycleBin = false) }
        return vault.recycleBin.firstOrNull { it.id == ref.entry }?.let { OpenEntry(database, it, inRecycleBin = true) }
    }

    /** Calls [handler] after every Lock and its kin, also one that found nothing to lock. */
    public fun observeLocks(handler: (LockReason) -> Unit) {
        lockObservers += handler
    }

    /**
     * Calls [handler] each time the background timer starts, with the time it has: the app sets its alarm then, since
     * the timer does not run while Android has the app frozen.
     */
    public fun observeTimer(handler: (Duration) -> Unit) {
        timerObservers += handler
    }

    // The window

    /** Into the window: its session is made, locked. */
    public fun addToWindow(id: DatabaseId) {
        list.setInWindow(id, true)
    }

    /**
     * Out of the window, still in the list: it locks with [reason], which also replaces what it said while locked, and
     * its session goes. The list's current database stays in the window (another is made current first, plan AD12):
     * closing it only locks it.
     */
    public fun close(id: DatabaseId, reason: LockReason = LockReason.Manual) {
        sessions[id]?.database?.session?.let { session ->
            session.lock(reason)
            lastReason = reason // also when it was locked so already
        }
        list.setInWindow(id, false)
    }

    // Locking

    /** Locks every database open or opening (Lock, the timeout); told to [observeLocks] also when none was. */
    public fun lockAll(reason: LockReason = LockReason.Manual) {
        val locking = sessions.values.map { it.database.session }.filter { it.state.value !is SessionState.Locked }
        batching = true
        try {
            locking.forEach { it.lock(reason) }
        } finally {
            batching = false
        }
        if (locking.isNotEmpty()) publish()
        lockObservers.toList().forEach { it(reason) }
    }

    /** One database alone; its [reason] replaces what it said while locked (its key file chosen, say). */
    public fun lock(id: DatabaseId, reason: LockReason = LockReason.Manual) {
        val session = sessions[id]?.database?.session ?: return
        session.lock(reason)
        lastReason = reason
        publish()
    }

    // The background timeout

    /** The app went to the background (turning the screen off does that too). */
    public fun movedToBackground() {
        backgroundSince = clock.elapsed()
        startTimer()
    }

    /**
     * The app came back. The timer may not have run (a phone in deep sleep), so the time in the background is checked
     * here too; then each open database's file is checked for a new version.
     */
    public fun movedToForeground() {
        val timedOut = backgroundTimedOut()
        backgroundSince = null
        stopTimer()
        if (timedOut) return lockAll(LockReason.Timeout)
        sessions.values.toList().forEach { it.database.session.checkFile() }
    }

    /** A wake-up while in the background (the lock alarm, a fill request): locks all when the timeout has passed. */
    public fun checkBackgroundTime() {
        if (backgroundTimedOut()) lockAll(LockReason.Timeout)
    }

    private fun isActive(): Boolean = sessions.values.any { it.database.session.state.value !is SessionState.Locked }

    private fun backgroundTimedOut(): Boolean {
        val since = backgroundSince ?: return false
        return isActive() && clock.elapsed() - since >= lockTimeout()
    }

    private fun startTimer() {
        stopTimer()
        val since = backgroundSince ?: return
        if (!isActive()) return
        val left = lockTimeout() - (clock.elapsed() - since)
        timeoutJob = scope.launch {
            delay(left)
            if (isActive()) lockAll(LockReason.Timeout)
        }
        timerObservers.toList().forEach { it(left) }
    }

    private fun stopTimer() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    /** The first database to open or begin opening in the background starts the timer; the last one locked ends it. */
    private fun followActivity() {
        val now = isActive()
        if (now && !active && backgroundSince != null) {
            backgroundSince = clock.elapsed()
            startTimer()
        }
        if (!now) stopTimer()
        active = now
    }

    // Following the list and the sessions

    /** A session for each database of the window; one that left it or the list, or whose file moved, is dropped. */
    private fun follow() {
        val current = list.current
        if (current != null && !current.inWindow) {
            list.setInWindow(current.id, true) // told again, from within
            return
        }
        val window = list.window
        var dropped = false
        for ((id, started) in sessions.toList()) {
            if (window.any { it.id == id && it.path == started.database.file.path }) continue
            drop(id, started)
            dropped = true
        }
        for (record in window) if (record.id !in sessions) sessions[record.id] = start(record)
        mutableCurrent.value = list.currentId?.let { sessions[it]?.database }
        if (dropped) {
            followActivity()
            publish()
        }
    }

    private fun start(record: DatabaseRecord): Started {
        val watching = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        val id = record.id
        val database = factory.start(record, ++made, { list.record(id)?.keyFilePath }, watching)
        database.session.onStateChange = { changed(id, it) }
        return Started(database, watching)
    }

    /** Locked if it was not (the app says so), then closed: a fingerprint prompt still up, or a screen of before, cannot open it. */
    private fun drop(id: DatabaseId, started: Started) {
        val session = started.database.session
        if (session.state.value !is SessionState.Locked) session.lock(LockReason.Manual)
        session.onStateChange = null
        session.close()
        started.scope.cancel()
        sessions.remove(id)
        opened.remove(id)
    }

    private fun changed(id: DatabaseId, new: SessionState) {
        when (new) {
            is SessionState.Locked -> {
                lastReason = new.reason
                opened.remove(id)
            }
            is SessionState.Unlocked -> recordOpened(id, new)
            SessionState.Unlocking -> Unit
        }
        followActivity()
        if (!batching) publish()
    }

    /**
     * What the file says of itself, at every open and reload, so the lock screen and the sheet say it while it is locked
     * (keething-spec databases.name-colour AC2); and, at an unlock, when it was last opened (Settings says it of a file
     * not found). The list is not touched when nothing changed.
     */
    private fun recordOpened(id: DatabaseId, unlocked: SessionState.Unlocked) {
        val unlock = opened.add(id)
        list.update(id) {
            it.copy(
                name = DatabaseRecord.nameFromFile(unlocked.vault.name),
                color = DatabaseRecord.colorFromFile(unlocked.vault.color),
                lastOpened = if (unlock) now() else it.lastOpened,
            )
        }
    }

    /** The app's state, told when it changed. */
    private fun publish() {
        val vaults = list.window.mapNotNull { record ->
            (sessions[record.id]?.database?.session?.state?.value as? SessionState.Unlocked)?.let { OpenVault(record.id, it.vault) }
        }
        mutableState.value = when {
            vaults.isNotEmpty() -> SessionsState.Open(vaults)
            sessions.values.any { it.database.session.state.value == SessionState.Unlocking } -> SessionsState.Opening
            else -> SessionsState.NothingOpen(lastReason)
        }
    }
}
