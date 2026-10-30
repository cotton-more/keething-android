package dev.keething.android.databases

import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.SetUpAgain
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState

/**
 * What a database of the window that is not open says (keething-spec databases.not-open AC1; the Mac's `NotOpenState`):
 * its word on the chip, its whole say, and its tone (the agreed mock-up's colours).
 */
internal sealed interface NotOpenState {
    /** The chip's colour: the secondary text, the primary, the warning, the error colour. */
    enum class Tone { PLAIN, ACTIVE, WARNING, PROBLEM }

    val word: String
    val tone: Tone

    data object Locked : NotOpenState {
        override val word = "Locked"
        override val tone = Tone.PLAIN
    }

    data object Opening : NotOpenState {
        override val word = "Opening…"
        override val tone = Tone.ACTIVE
    }

    /** Its password is due: the fingerprint waits until it is typed. */
    data class FingerprintPaused(val days: Int) : NotOpenState {
        override val word = "Fingerprint paused"
        override val tone = Tone.WARNING
    }

    /** [why] its stored password went; null: nothing was stored yet. */
    data class FingerprintToSetUp(val why: SetUpAgain?) : NotOpenState {
        override val word = "Fingerprint to set up"
        override val tone = Tone.WARNING
    }

    data object NotFound : NotOpenState {
        override val word = "Not found"
        override val tone = Tone.PROBLEM
    }

    /** Its key file [name] is not there, or cannot be used ([unusable]: why). */
    data class NeedsKeyFile(val name: String, val unusable: String?) : NotOpenState {
        override val word = "Needs its key file"
        override val tone = Tone.PROBLEM
    }

    data class CannotBeRead(val failure: OpenFailure) : NotOpenState {
        override val word = "Cannot be read"
        override val tone = Tone.PROBLEM
    }

    data object WrongPassword : NotOpenState {
        override val word = "Wrong password"
        override val tone = Tone.PROBLEM
    }

    data object PasswordChanged : NotOpenState {
        override val word = "Password changed"
        override val tone = Tone.WARNING
    }

    /** Skipped in the chooser's run (plan AD13). */
    data object Skipped : NotOpenState {
        override val word = "Skipped"
        override val tone = Tone.PLAIN
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
            NotOpenState.NotFound -> "Not found at ${line.folder}/${line.fileName}"
            is NotOpenState.NeedsKeyFile ->
                state.unusable?.let { "Its key file ${state.name} cannot be used: $it" } ?: "Needs its key file: ${state.name} is not there"
            is NotOpenState.CannotBeRead -> "Cannot be read: " + when (val failure = state.failure) {
                is OpenFailure.Damaged -> "damaged or not completely synced yet (${failure.detail})"
                is OpenFailure.Unsupported -> failure.what
                OpenFailure.AccessDenied -> "Keething may not read the file (all-files access)"
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
        if (line.isMissing) return NotOpenState.NotFound
        line.keyFile?.takeIf { it.isMissing }?.let { return NotOpenState.NeedsKeyFile(it.name, null) }
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

    /** The line's title: "Not open", "Not searched" while a search is typed; its count past two. */
    fun title(count: Int, typing: Boolean): String = (if (typing) "Not searched" else "Not open") + if (count > 2) " $count" else ""
}

/** ⋮ › Databases (the agreed mock-up; the Mac's Databases ▸): the open ones, those not open, and Recent. */
internal class DatabasesOverview(
    val open: List<DatabaseLine>,
    val notOpen: List<NotOpenDatabase>,
    /** The list's databases out of the window, the last opened first. */
    val recent: List<DatabaseLine>,
    /** The window keeps one: its last cannot be closed. */
    val canClose: Boolean,
) {
    companion object {
        fun of(lines: List<DatabaseLine>, window: List<DatabaseId>, open: List<DatabaseId>, notOpen: List<NotOpenDatabase>): DatabasesOverview {
            val byId = lines.associateBy { it.id }
            return DatabasesOverview(
                open = open.mapNotNull { byId[it] },
                notOpen = notOpen,
                recent = lines.filter { it.id !in window }.sortedByDescending { it.lastOpened ?: java.time.Instant.MIN },
                canClose = window.size > 1,
            )
        }
    }
}
