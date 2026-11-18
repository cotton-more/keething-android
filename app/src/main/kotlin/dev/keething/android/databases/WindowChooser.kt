package dev.keething.android.databases

import dev.keething.android.session.Visit
import dev.keething.android.ui.UiText
import dev.keething.android.ui.ui
import dev.keething.domain.secret.Secret
import dev.keething.vault.biometric.FingerprintOutcome
import dev.keething.vault.biometric.WindowFingerprintResult
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.UnlockRun
import dev.keething.vault.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The chooser and its run as the app keeps them, across screens and rotations (plan AD13; keething-spec databases.chooser;
 * the Mac's `WindowDatabasesModel` and `WindowScreenModel`):
 * - The run of the checked databases ([start]), after one fingerprint for those of them it would open; a lock ends it with nothing said, also one with nothing open yet
 *   ([lockCount]: the app's, a stay away past the timeout counted); Stop too. Its end is told over the list ([tell]) when
 *   any opened, else said over the chooser ([note]); the vault then shows on what opened ([showOpened]).
 * - Those it skipped say Skipped on their chips until they do something else ([skipped]) or another run starts.
 * - The fingerprint for every database that can ([unlockWithFingerprint]), offered by itself once per visit that began
 *   locked when one would open ([offer]); when it opens none, the chooser says why, until a lock or a database opens.
 *   No run starts while it is offered or asked ([busy]).
 *
 * Call it on [scope]'s thread (the main thread).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WindowChooser(
    private val list: DatabaseList,
    private val startRun: (List<DatabaseId>) -> UnlockRun,
    /** The fingerprint for every database of the window that can, of those given only (null: every one). */
    private val fingerprint: suspend (Collection<DatabaseId>?) -> WindowFingerprintResult,
    private val canUnlockAny: suspend (Collection<DatabaseId>?) -> Boolean,
    /** Once per visit that began locked, for the app ([dev.keething.android.session.FingerprintOffers]). */
    private val offers: (Visit) -> Boolean,
    private val label: (DatabaseId) -> String,
    /** The vault on the window's first open database, unless the current one is open. */
    private val showOpened: () -> Unit,
    private val tell: (UiText) -> Unit,
    /** A database's session state now, while it is in the window. */
    private val sessionState: (DatabaseId) -> SessionState?,
    private val scope: CoroutineScope,
    window: Flow<List<WindowDatabase>>,
    private val lockCount: StateFlow<Int>,
) {
    private val mutableRun = MutableStateFlow<UnlockRun?>(null)

    /** The run while it goes. */
    val run: StateFlow<UnlockRun?> = mutableRun.asStateFlow()

    /** A run and the database it asks now. */
    data class Asked(val run: UnlockRun, val id: DatabaseId)

    /**
     * The database the run asks now, with its run (one value: the screen never pairs a run with another's database);
     * the screen shows its lock screen; null without a run.
     */
    val asked: StateFlow<Asked?> = run
        .flatMapLatest { run -> run?.state?.map { run.current?.let { Asked(run, it) } } ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val mutableBusy = MutableStateFlow(false)

    /** The fingerprint is offered or asked: the chooser waits, and no run starts under its prompt. */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val mutableNote = MutableStateFlow<UiText?>(null)

    /** Over the chooser, before how it goes: why the fingerprint opened none, or what the last run did when it opened none. */
    val note: StateFlow<UiText?> = mutableNote.asStateFlow()

    private val mutableSkipped = MutableStateFlow(emptySet<DatabaseId>())

    /** Skipped in the last run, until they do something else or another run starts. */
    val skipped: StateFlow<Set<DatabaseId>> = mutableSkipped.asStateFlow()

    /**
     * The chooser is on screen ([offer] to [left]): an offer prompts only while it is. Back from the system picker of
     * Open other… is a new visit, and the file picked shows its lock screen in the chooser's place (found on the phone).
     */
    private var onScreen = false

    /** Each skipped database's session state when the run ended: one that differs since did something else. */
    private var skippedStates: Map<DatabaseId, SessionState?> = emptyMap()

    init {
        var locks = lockCount.value
        scope.launch {
            lockCount.collect {
                if (it == locks) return@collect
                locks = it
                // A lock ends the run where it is: the lock screen or the chooser comes, there is nothing to tell.
                mutableRun.value?.stop()
                mutableNote.value = null
            }
        }
        scope.launch { window.collect(::windowChanged) }
    }

    /** A database's check, remembered at once (keething-spec databases.chooser AC4). */
    fun toggle(id: DatabaseId) {
        list.update(id) { it.copy(checkedInChooser = !it.checkedInChooser) }
    }

    /**
     * Unlock N databases: [ids], the checked ones. First one fingerprint for those of them it would open (decided
     * without reading any stored password), then the rest one after another; those it opened count as opened
     * (keething-spec databases.chooser AC5). A fingerprint cancelled or failed leaves them to their passwords, nothing
     * said; a lock under its prompt starts no run.
     */
    fun start(ids: List<DatabaseId>) {
        if (mutableRun.value != null || mutableBusy.value || ids.isEmpty()) return
        mutableSkipped.value = emptySet()
        mutableNote.value = null
        mutableBusy.value = true
        scope.launch {
            val locks = lockCount.value
            try {
                if (canUnlockAny(ids)) fingerprint(ids)
            } finally {
                mutableBusy.value = false
            }
            if (lockCount.value == locks && mutableRun.value == null) startNow(ids)
        }
    }

    private fun startNow(ids: List<DatabaseId>) {
        val run = startRun(ids)
        run.onEnd = { ended(run, it) }
        mutableRun.value = run
        run.start()
    }

    /**
     * The run's field: [id], the database its lock screen shows, with what was typed; nothing when the run asks another
     * by now. [password] is wiped.
     */
    fun type(id: DatabaseId, password: Secret) {
        val run = mutableRun.value ?: return password.wipe()
        scope.launch { run.unlock(id, password) }
    }

    /** The run shows in [visit]: its one fingerprint offer is used, so Lock all or the run's end brings no prompt. */
    fun showingRun(visit: Visit) {
        offers(visit)
    }

    /** Skip: [id], the database its lock screen shows; nothing when the run asks another by now. */
    fun skip(id: DatabaseId) {
        mutableRun.value?.skip(id)
    }

    /** Stop: the run ends where it is, nothing said. */
    fun stop() {
        mutableRun.value?.stop()
    }

    /**
     * The chooser's Unlock with the fingerprint: every database that can (keething-spec unlock.biometric-several). In
     * [scope]: the screen going (the vault showing on the first that opened) does not end it.
     */
    fun unlockWithFingerprint() {
        if (mutableRun.value != null || mutableBusy.value) return
        mutableBusy.value = true
        scope.launch {
            try {
                fingerprintNow()
            } finally {
                mutableBusy.value = false
            }
        }
    }

    /**
     * The chooser shows in [visit]: once in a visit that began locked, the fingerprint for every database that can, when
     * one would open by it (decided without reading any stored password; keething-spec databases.chooser AC1).
     */
    fun offer(visit: Visit) {
        onScreen = true
        // The visit's offer is used whatever else goes on (a run, a fingerprint still asked): never one later in it.
        if (!offers(visit) || mutableRun.value != null || mutableBusy.value) return
        mutableNote.value = null
        mutableBusy.value = true
        scope.launch {
            try {
                if (canUnlockAny(null) && mutableRun.value == null && onScreen) fingerprintNow()
            } finally {
                mutableBusy.value = false
            }
        }
    }

    /** The chooser gave way to another screen: an offer not yet prompting asks nothing. */
    fun left() {
        onScreen = false
    }

    private suspend fun fingerprintNow() {
        mutableNote.value = null
        val locks = lockCount.value
        val result = fingerprint(null)
        // A lock meanwhile: the plain chooser comes, with nothing said of a fingerprint asked before it.
        if (lockCount.value != locks) return
        when (result.outcome) {
            FingerprintOutcome.InProgress -> Unit
            FingerprintOutcome.Unlocked -> showOpened()
            else -> mutableNote.value = ChooserRows.note(result.outcome)
        }
    }

    private fun ended(run: UnlockRun, result: UnlockRun.Result?) {
        if (mutableRun.value === run) mutableRun.value = null
        if (result == null) {
            if (run.opened.isNotEmpty()) showOpened()
            return
        }
        mutableSkipped.value = result.skipped.toSet()
        skippedStates = result.skipped.associateWith(sessionState)
        val notice = result.notice(label).ui()
        if (result.opened.isEmpty()) {
            mutableNote.value = notice
        } else {
            showOpened()
            tell(notice)
        }
    }

    /**
     * A database of the window changed: what it did since is newer than its skip (its own lock screen's wrong password,
     * an unlock), as its session says now (what is told may be a moment late); one open makes what the chooser said
     * stale; the run looks again at the database it asks (one opened by its fingerprint, one closed).
     */
    private fun windowChanged(window: List<WindowDatabase>) {
        if (window.any { it.session is SessionState.Unlocked }) mutableNote.value = null
        val run = mutableRun.value
        if (run == null && mutableSkipped.value.isNotEmpty()) {
            val changed = mutableSkipped.value.filterTo(HashSet()) { id -> sessionState(id) != skippedStates[id] }
            if (changed.isNotEmpty()) mutableSkipped.value -= changed
        }
        run?.recheck()
    }
}
