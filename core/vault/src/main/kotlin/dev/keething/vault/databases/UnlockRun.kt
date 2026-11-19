package dev.keething.vault.databases

import dev.keething.domain.secret.Secret
import dev.keething.domain.text.UserText
import dev.keething.vault.VaultText
import dev.keething.vault.opening.OpenFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The chooser's run (plan AD13; keething-spec databases.chooser AC5–AC8; the Mac's `UnlockRun`): the checked databases
 * asked one after another ("Unlocking 2 of 3"), each with its own password — a typed password is never tried on another
 * database. A wrong one stays to be typed again or skipped (Skip); a database typing cannot open (not found, its key file
 * not there, closed meanwhile, or a try that fails so) is skipped by itself; one opened meanwhile counts as opened. At
 * the end what happened ([Result]); a lock or Stop ends it with nothing to tell.
 *
 * [cannotOpen]: why typing cannot open a database now, or null; it looks at the files, off the main thread. [isOpen]: it
 * is open already. [tryUnlock]: one try with a typed password (the lock screen's typed path). Call it on the main thread.
 * While the run looks at the next database's files, a try or a Skip waits for it: what was typed for one database is
 * never tried on the one after.
 */
public class UnlockRun(
    public val ids: List<DatabaseId>,
    private val cannotOpen: suspend (DatabaseId) -> UserText?,
    private val isOpen: (DatabaseId) -> Boolean,
    private val tryUnlock: suspend (DatabaseId, Secret) -> Attempt,
) {
    /** How one try ended. */
    public sealed interface Attempt {
        public data object Opened : Attempt

        public data class Failed(val failure: OpenFailure) : Attempt

        /** Typing cannot open it now (it left the window): skipped by itself, with [why]. */
        public data class CannotOpen(val why: UserText) : Attempt

        /** A lock meanwhile: the run ends. */
        public data object Stopped : Attempt
    }

    /** A segment of the bar over the run: opened, skipped, the one asked, or one to come. */
    public enum class Segment { OPENED, SKIPPED, CURRENT, WAITING }

    /** A database skipped by itself, and why ("not found", "work.keyx not there", "cannot be read", "closed"). */
    public data class Problem(val id: DatabaseId, val why: UserText)

    /** What the run did. */
    public class Result(public val opened: List<DatabaseId>, public val skipped: List<DatabaseId>, public val notOpened: List<Problem>, private val count: Int) {
        /** The list's notice: "2 of 3 open · Family Shared: skipped · Crypto: not found". */
        public fun notice(label: (DatabaseId) -> String): UserText = UserText.Joined(
            listOf(UserText.counted(VaultText.RUN_OPEN, opened.size, opened.size, count)) +
                skipped.map { UserText.of(VaultText.RUN_SKIPPED, label(it)) } +
                notOpened.map { UserText.of(VaultText.RUN_NOT_OPENED, label(it.id), it.why) },
            " · ",
        )
    }

    /** The run as the screen shows it: the database asked, why its last password did not open it, a try going on. */
    public data class State(val index: Int, val failure: OpenFailure?, val unlocking: Boolean, val ended: Boolean)

    private val mutableState = MutableStateFlow(State(0, failure = null, unlocking = false, ended = false))
    public val state: StateFlow<State> = mutableState.asStateFlow()

    /** Held while the run moves on (looking at the files of those it reaches) and while a try or a Skip is decided. */
    private val moving = Mutex()

    /** The run ended: what it did, or null when it stopped (Stop, a lock). Called once. */
    public var onEnd: ((Result?) -> Unit)? = null

    private val openedIds = mutableListOf<DatabaseId>()
    private val skipped = mutableListOf<DatabaseId>()
    private val notOpened = mutableListOf<Problem>()

    /** The database asked now; null once the run ended. */
    public val current: DatabaseId? get() = state.value.takeUnless { it.ended }?.index?.let(ids::getOrNull)

    /** "Unlocking 2 of 3". */
    public val step: UserText get() = UserText.of(VaultText.RUN_STEP, minOf(state.value.index, ids.size - 1) + 1, ids.size)

    /** Those opened so far, in the run's order. */
    public val opened: List<DatabaseId> get() = openedIds.toList()

    public val segments: List<Segment>
        get() = ids.mapIndexed { index, id ->
            when {
                id in openedIds -> Segment.OPENED
                id in skipped || notOpened.any { it.id == id } -> Segment.SKIPPED
                index == state.value.index -> Segment.CURRENT
                else -> Segment.WAITING
            }
        }

    /** Asks for the first database typing can open. */
    public suspend fun start() {
        moving.withLock { settle() }
    }

    /**
     * Unlock: [id], the database the screen asks for, with what was typed; nothing when the run asks another by now (a
     * Skip, the run moving on by itself) — a password typed for one is never tried on another. [password] is wiped.
     */
    public suspend fun unlock(id: DatabaseId, password: Secret) {
        moving.withLock {
            if (id != current || state.value.unlocking) {
                password.wipe()
                return
            }
            change { it.copy(unlocking = true, failure = null) }
        }
        val attempt = try {
            tryUnlock(id, password)
        } finally {
            password.wipe()
            change { it.copy(unlocking = false) }
        }
        moving.withLock { tried(id, attempt) }
    }

    /** What [attempt] on [id] does to the run. Under [moving]. */
    private suspend fun tried(id: DatabaseId, attempt: Attempt) {
        if (current != id) return // stopped meanwhile
        when (attempt) {
            Attempt.Opened -> {
                openedIds += id
                advance()
            }
            is Attempt.Failed -> if (attempt.failure.isWrongKey) {
                change { it.copy(failure = attempt.failure) }
            } else {
                // Typing cannot fix it: skipped by itself, with why.
                notOpened += Problem(id, why(attempt.failure))
                advance()
            }
            is Attempt.CannotOpen -> {
                notOpened += Problem(id, attempt.why)
                advance()
            }
            Attempt.Stopped -> stop()
        }
    }

    /** Skip: [id], the database the screen asks for, stays locked, said so; nothing when the run asks another by now. */
    public suspend fun skip(id: DatabaseId) {
        moving.withLock {
            if (id != current || state.value.unlocking) return
            skipped += id
            advance()
        }
    }

    /** The window's databases changed (one closed, one opened by its fingerprint): the one asked now is looked at again. */
    public suspend fun recheck() {
        moving.withLock {
            if (current == null || state.value.unlocking) return
            settle()
        }
    }

    /** Stop, or a lock: the run ends where it is, nothing told. */
    public fun stop() {
        if (state.value.ended) return
        change { it.copy(ended = true, failure = null) }
        onEnd?.invoke(null)
    }

    /** Under [moving]. */
    private suspend fun advance() {
        change { it.copy(index = it.index + 1, failure = null) }
        settle()
    }

    /**
     * Counts what is open already, skips by itself what typing cannot open, then asks for the next; past the last, the
     * end. Under [moving]; a Stop while it looks at a file ends it there.
     */
    private suspend fun settle() {
        var index = state.value.index
        while (index < ids.size) {
            val id = ids[index]
            if (isOpen(id)) {
                openedIds += id
            } else {
                val why = cannotOpen(id)
                if (state.value.ended) return
                // Opened meanwhile (its fingerprint): counted as opened.
                if (isOpen(id)) continue
                notOpened += Problem(id, why ?: break)
            }
            index++
        }
        if (index != state.value.index) change { it.copy(index = index, failure = null) }
        if (index >= ids.size) finish()
    }

    private fun finish() {
        change { it.copy(ended = true) }
        onEnd?.invoke(Result(openedIds.toList(), skipped.toList(), notOpened.toList(), ids.size))
    }

    private fun change(update: (State) -> State) {
        mutableState.value = update(mutableState.value)
    }

    private companion object {
        fun why(failure: OpenFailure): UserText = when (failure) {
            OpenFailure.Missing -> UserText.of(VaultText.WHY_NOT_FOUND)
            is OpenFailure.KeyFileMissing -> UserText.of(VaultText.WHY_KEY_FILE_NOT_THERE, failure.name)
            is OpenFailure.KeyFileUnusable -> UserText.of(VaultText.WHY_KEY_FILE_UNUSABLE)
            else -> UserText.of(VaultText.WHY_CANNOT_BE_READ)
        }
    }
}
