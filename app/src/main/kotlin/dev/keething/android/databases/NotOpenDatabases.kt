package dev.keething.android.databases

import dev.keething.android.unlock.named
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.SetUpAgain
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState

/**
 * What a database of the window that is not open says (keething-spec databases.not-open AC1, AC8; the Mac's
 * `NotOpenState`): its glyph, drawn in the database's colour in place of its dot, and its word, read aloud.
 */
internal sealed interface NotOpenState {
    /** The state's icon (the owner, 2026-11-01): one per state, no other state has it. */
    enum class Glyph {
        LOCK, SPINNER, PAUSE, FINGERPRINT, FOLDER_QUESTION, FOLDER_OFF, FOLDER_NEW, KEY_OFF, WARNING, LOCK_WARNING,
        LOCK_RESET, SKIP,
    }

    val word: String
    val glyph: Glyph

    data object Locked : NotOpenState {
        override val word = "Locked"
        override val glyph = Glyph.LOCK
    }

    data object Opening : NotOpenState {
        override val word = "Opening…"
        override val glyph = Glyph.SPINNER
    }

    /** Its password is due: the fingerprint waits until it is typed. */
    data class FingerprintPaused(val days: Int) : NotOpenState {
        override val word = "Fingerprint paused"
        override val glyph = Glyph.PAUSE
    }

    /** [why] its stored password went; null: nothing was stored yet. */
    data class FingerprintToSetUp(val why: SetUpAgain?) : NotOpenState {
        override val word = "Fingerprint to set up"
        override val glyph = Glyph.FINGERPRINT
    }

    /** Its file cannot be reached: not found, no access any more, or its folder not chosen yet ([reach]). */
    data class NotFound(val reach: Reach = Reach.NOT_FOUND) : NotOpenState {
        override val word = when (reach) {
            Reach.NO_ACCESS -> "No access"
            Reach.NEEDS_FOLDER -> "Needs its folder"
            else -> "Not found"
        }
        override val glyph = when (reach) {
            Reach.NO_ACCESS -> Glyph.FOLDER_OFF
            Reach.NEEDS_FOLDER -> Glyph.FOLDER_NEW
            else -> Glyph.FOLDER_QUESTION
        }
    }

    /** Its key file [name] is not there, or cannot be used ([unusable]: why). */
    data class NeedsKeyFile(val name: String, val unusable: String?) : NotOpenState {
        override val word = "Needs its key file"
        override val glyph = Glyph.KEY_OFF
    }

    data class CannotBeRead(val failure: OpenFailure) : NotOpenState {
        override val word = "Cannot be read"
        override val glyph = Glyph.WARNING
    }

    data object WrongPassword : NotOpenState {
        override val word = "Wrong password"
        override val glyph = Glyph.LOCK_WARNING
    }

    data object PasswordChanged : NotOpenState {
        override val word = "Password changed"
        override val glyph = Glyph.LOCK_RESET
    }

    /** Skipped in the chooser's run (plan AD13). */
    data object Skipped : NotOpenState {
        override val word = "Skipped"
        override val glyph = Glyph.SKIP
    }
}

/** A database of the window as its session and its fingerprint have it now. */
internal class WindowDatabase(val id: DatabaseId, val session: SessionState, val fingerprint: BiometricState)

/** A database of the window that is not open, with its [line] (label, colour, folder, files) and [state]. */
internal data class NotOpenDatabase(val line: DatabaseLine, val state: NotOpenState) {
    val id: DatabaseId get() = line.id

    /** Its whole say: the chip's long press, its row in ⋮ › Databases (the Mac's help tag, "tap" for "⏎"). */
    val say: String
        get() = when (val state = state) {
            NotOpenState.Locked -> "Locked · tap to type its password"
            NotOpenState.Opening -> "Opening…"
            is NotOpenState.FingerprintPaused -> "Fingerprint paused: type the password (every ${state.days} days)"
            is NotOpenState.FingerprintToSetUp -> when (state.why) {
                SetUpAgain.FINGERPRINTS_CHANGED -> "Fingerprint to set up again: the fingerprints changed"
                SetUpAgain.PASSWORD_REJECTED -> "Fingerprint to set up again: the stored password was not accepted"
                null -> "Fingerprint to set up: type its password once"
            }
            is NotOpenState.NotFound -> when (state.reach) {
                Reach.NO_ACCESS -> "No access to ${line.folder} any more: locate ${line.fileName} to choose its folder again"
                Reach.NEEDS_FOLDER -> "Needs its folder: choose ${line.folder} once"
                else -> "Not found at ${line.folder}/${line.fileName}"
            }
            is NotOpenState.NeedsKeyFile ->
                state.unusable?.let { "Its key file ${state.name} cannot be used: $it" } ?: "Needs its key file: ${state.name} is not there"
            is NotOpenState.CannotBeRead -> "Cannot be read: " + when (val failure = state.failure) {
                is OpenFailure.Damaged -> "damaged or not completely synced yet (${failure.detail})"
                is OpenFailure.Unsupported -> failure.named()
                OpenFailure.AccessDenied -> "Keething may not read the file any more: locate it"
                OpenFailure.OutOfMemory -> "not enough memory to open it"
                else -> "it did not open"
            }
            NotOpenState.WrongPassword -> "Wrong password · tap to type it again"
            NotOpenState.PasswordChanged -> "Its password was changed elsewhere · tap to type the new one"
            NotOpenState.Skipped -> "Skipped · tap to type its password"
        }
}

/** The databases of the window not open (keething-spec databases.not-open; the Mac's `WindowDatabasesModel`). */
internal object NotOpenDatabases {
    /** The window's databases not open, in the window's order, each with its state; [lines] give their files as they are now. */
    fun of(window: List<WindowDatabase>, lines: List<DatabaseLine>, skipped: Set<DatabaseId>): List<NotOpenDatabase> {
        val byId = lines.associateBy { it.id }
        return window.mapNotNull { database ->
            if (database.session is SessionState.Unlocked) return@mapNotNull null
            val line = byId[database.id] ?: return@mapNotNull null
            NotOpenDatabase(line, state(database.session, line, database.fingerprint, database.id in skipped))
        }
    }

    /**
     * The first that applies (AC6): opening; the files as they are now (a file or key file missing wins over an older
     * failure, an old "not found" goes once the file is back); what typing cannot fix; the fingerprint's verdict; skipped
     * in the run; a wrong or changed password; the fingerprint waiting; locked.
     */
    fun state(session: SessionState, line: DatabaseLine, fingerprint: BiometricState, skipped: Boolean): NotOpenState {
        if (session is SessionState.Unlocking) return NotOpenState.Opening
        if (line.isMissing) return NotOpenState.NotFound(line.reach)
        line.keyFile?.takeIf { it.isMissing }?.let { key ->
            return NotOpenState.NeedsKeyFile(key.name, "Keething may not read it any more: choose it again".takeIf { key.reach != Reach.NOT_FOUND })
        }
        val reason = (session as? SessionState.Locked)?.reason
        val failure = (reason as? LockReason.UnlockFailed)?.failure
        when (failure) {
            is OpenFailure.KeyFileUnusable -> return NotOpenState.NeedsKeyFile(line.keyFile?.name.orEmpty(), failure.why)
            is OpenFailure.Unsupported, is OpenFailure.Damaged, OpenFailure.AccessDenied, OpenFailure.OutOfMemory ->
                return NotOpenState.CannotBeRead(failure)
            else -> {}
        }
        val uses = fingerprint.enabled && fingerprint.availability == BiometricAvailability.Available
        val setUp = if (uses && !fingerprint.stored) fingerprint.setUpAgain else null
        if (setUp != null) return NotOpenState.FingerprintToSetUp(setUp)
        if (skipped) return NotOpenState.Skipped
        if (failure?.isWrongKey == true) return NotOpenState.WrongPassword
        if (reason == LockReason.PasswordChanged) return NotOpenState.PasswordChanged
        if (uses) {
            val days = fingerprint.reminderDays
            if (days != null && fingerprint.passwordDue && !fingerprint.passwordless) return NotOpenState.FingerprintPaused(days)
            if (!fingerprint.stored && !fingerprint.passwordless) return NotOpenState.FingerprintToSetUp(null)
        }
        return NotOpenState.Locked
    }

    /**
     * Those of [notOpen] on the line under the list's header (AC1; the owner, 2026-11-07): the ones an unlock was
     * [tried] on since the last Lock or timeout, or [skipped] in the run, and not locked by the user since (Lock it, Lock
     * the others). A database nobody tried to open is under ⋮ › Databases only.
     */
    fun needingAttention(notOpen: List<NotOpenDatabase>, window: List<WindowDatabase>, tried: Set<DatabaseId>, skipped: Set<DatabaseId>): List<NotOpenDatabase> {
        val states = window.associate { it.id to it.session }
        return notOpen.filter { it.id in skipped || it.id in tried && states[it.id] != SessionState.Locked(LockReason.Manual) }
    }

    /**
     * What a database not open is read aloud as besides its state (AC2): "Not searched" while a search is typed. The
     * line has no title (the owner, 2026-11-07): it holds the read-only ones too, which are searched.
     */
    fun readAloud(typing: Boolean): String? = if (typing) "Not searched" else null
}

/** ⋮ › Databases (the agreed mock-up; the Mac's Databases ▸): the open ones, those not open, and Recent. */
internal class DatabasesOverview(
    val open: List<DatabaseLine>,
    val notOpen: List<NotOpenDatabase>,
    /** Those of [notOpen] that need attention: the line under the list's header ([NotOpenDatabases.needingAttention]). */
    val attention: List<NotOpenDatabase> = notOpen,
    /** The list's databases out of the window, the last opened first. */
    val recent: List<DatabaseLine>,
    /** The window keeps one: its last cannot be closed. */
    val canClose: Boolean,
    /** The open ones that are read-only, in the window's order, and why (keething-spec databases.read-only-mark). */
    val readOnly: List<ReadOnlyDatabase> = emptyList(),
) {
    /** Why [id] is read-only; null while it can be changed. */
    fun readOnly(id: DatabaseId): ReadOnlyReason? = readOnly.firstOrNull { it.id == id }?.reason

    companion object {
        fun of(
            lines: List<DatabaseLine>,
            window: List<DatabaseId>,
            open: List<DatabaseId>,
            notOpen: List<NotOpenDatabase>,
            readOnly: List<ReadOnlyDatabase> = emptyList(),
            attention: List<NotOpenDatabase> = notOpen,
        ): DatabasesOverview {
            val byId = lines.associateBy { it.id }
            return DatabasesOverview(
                open = open.mapNotNull { byId[it] },
                notOpen = notOpen,
                attention = attention,
                recent = lines.filter { it.id !in window }.sortedByDescending { it.lastOpened ?: java.time.Instant.MIN },
                canClose = window.size > 1,
                readOnly = open.mapNotNull { id -> readOnly.firstOrNull { it.id == id } },
            )
        }
    }
}
