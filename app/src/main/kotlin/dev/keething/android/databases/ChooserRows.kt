package dev.keething.android.databases

import dev.keething.android.session.SizeTexts
import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlural
import dev.keething.android.ui.uiText
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.FingerprintOutcome
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState

/**
 * A database of the window in the chooser (plan AD13; keething-spec databases.chooser AC3, AC4; the Mac's `ChooserRow`):
 * how it opens, and whether typing can open it (else it cannot be checked, and says why).
 */
internal data class ChooserRow(
    val line: DatabaseLine,
    /** Under its name: "Fingerprint", "Password + work.keyx", "Fingerprint to set up", "Password changed elsewhere", "Not found". */
    val opensWith: UiText,
    val glyph: Glyph,
    val tone: Tone,
    val canCheck: Boolean,
    val isChecked: Boolean,
) {
    /** The icon before how it opens. */
    enum class Glyph { FINGERPRINT, PASSWORD, KEY_FILE, NOT_FOUND, UNREADABLE, OPENING, OPEN }

    /** How it opens, in colour: the secondary text, the fingerprint opens it, a warning, a problem (the agreed mock-up). */
    enum class Tone { PLAIN, READY, WARNING, PROBLEM }

    val id: DatabaseId get() = line.id

    /** Not found, no access, or its folder to choose: Locate… beside it. */
    val offersLocate: Boolean get() = line.isMissing

    /** It cannot be checked, but its backups can be restored: a tap opens its sheet (keething-spec databases.not-open AC5). */
    val opensSheet: Boolean get() = line.newestBackup != null
}

/** The chooser: nothing open and several in the window (plan AD13; the agreed mock-up "⋮ › Databases, the chooser and its run"). */
internal object ChooserRows {
    /** Under the title, after what happened. */
    val HOW: UiText = uiText(R.string.databases_chooser_how)

    /** The window's databases in its order, each as its session, files and fingerprint say; [checked] as the user left it (keething-spec databases.chooser AC9). */
    fun of(window: List<WindowDatabase>, lines: List<DatabaseLine>, checked: (DatabaseId) -> Boolean): List<ChooserRow> {
        val byId = lines.associateBy { it.id }
        return window.mapNotNull { database -> byId[database.id]?.let { row(database.session, it, database.fingerprint, checked(database.id)) } }
    }

    /**
     * How [line]'s database opens: open or opening already is not offered; what is not there or cannot be read cannot be
     * checked; then the fingerprint's state while it opens it, the key file, the password.
     */
    fun row(session: SessionState, line: DatabaseLine, fingerprint: BiometricState, checked: Boolean): ChooserRow {
        fun row(text: UiText, glyph: ChooserRow.Glyph, tone: ChooserRow.Tone, canCheck: Boolean = true) =
            ChooserRow(line, text, glyph, tone, canCheck, isChecked = canCheck && checked)

        if (session is SessionState.Unlocked) return row(uiText(R.string.databases_chooser_open), ChooserRow.Glyph.OPEN, ChooserRow.Tone.PLAIN, canCheck = false)
        if (session is SessionState.Unlocking) return row(uiText(R.string.databases_chooser_opening), ChooserRow.Glyph.OPENING, ChooserRow.Tone.PLAIN, canCheck = false)
        line.reachTitle?.let { title ->
            val said = if (line.newestBackup != null) uiText(R.string.databases_chooser_not_found_backups) else title
            return row(said, ChooserRow.Glyph.NOT_FOUND, ChooserRow.Tone.PROBLEM, canCheck = false)
        }
        val key = line.keyFile
        if (key != null && key.isMissing) {
            return row(uiText(if (key.reach == Reach.NOT_FOUND) R.string.databases_chooser_key_not_there else R.string.databases_chooser_key_no_access, key.name), ChooserRow.Glyph.KEY_FILE, ChooserRow.Tone.PROBLEM, canCheck = false)
        }
        when (val failure = ((session as? SessionState.Locked)?.reason as? LockReason.UnlockFailed)?.failure) {
            is OpenFailure.Unsupported, OpenFailure.AccessDenied ->
                return row(uiText(R.string.databases_chooser_cannot_be_read), ChooserRow.Glyph.UNREADABLE, ChooserRow.Tone.PROBLEM, canCheck = false)
            // The next try may read it (keething-spec databases.chooser AC4): a sync may have finished it, memory may be free.
            is OpenFailure.Damaged -> return row(uiText(R.string.databases_chooser_damaged), ChooserRow.Glyph.UNREADABLE, ChooserRow.Tone.WARNING)
            is OpenFailure.ReadFailed -> return row(uiText(R.string.databases_chooser_read_failed), ChooserRow.Glyph.UNREADABLE, ChooserRow.Tone.WARNING)
            // Nothing is open beside it while the chooser shows: its size, and the next try may have the memory.
            is OpenFailure.OutOfMemory -> {
                val said = failure.fileBytes?.let { uiText(R.string.databases_chooser_too_large, SizeTexts.mib(it)) } ?: uiText(R.string.databases_chooser_out_of_memory)
                return row(said, ChooserRow.Glyph.UNREADABLE, ChooserRow.Tone.WARNING)
            }
            is OpenFailure.KeyFileUnusable -> return row(key?.let { uiText(R.string.databases_chooser_key_unusable, it.name) } ?: uiText(R.string.databases_chooser_its_key_unusable), ChooserRow.Glyph.KEY_FILE, ChooserRow.Tone.PROBLEM, canCheck = false)
            else -> {}
        }
        // Locked because the password it opens with is another now; its fingerprint's went with it (checks/android.md, 2026-11-13).
        when ((session as? SessionState.Locked)?.reason) {
            LockReason.RestoredOlderKey -> return row(uiText(R.string.databases_chooser_restored_older_key), ChooserRow.Glyph.PASSWORD, ChooserRow.Tone.WARNING)
            LockReason.PasswordChanged -> return row(uiText(R.string.databases_chooser_password_changed), ChooserRow.Glyph.PASSWORD, ChooserRow.Tone.WARNING)
            else -> {}
        }
        if (fingerprint.enabled && fingerprint.availability == BiometricAvailability.Available) {
            val fingerprintRow = { text: UiText, tone: ChooserRow.Tone -> row(text, ChooserRow.Glyph.FINGERPRINT, tone) }
            if (!fingerprint.stored && fingerprint.setUpAgain != null) return fingerprintRow(uiText(R.string.databases_chooser_fingerprint_set_up_again), ChooserRow.Tone.WARNING)
            if (!fingerprint.stored && !fingerprint.passwordless) return fingerprintRow(uiText(R.string.databases_chooser_fingerprint_set_up), ChooserRow.Tone.WARNING)
            return fingerprintRow(uiText(R.string.databases_chooser_fingerprint), ChooserRow.Tone.READY)
        }
        if (key != null) {
            return row(uiText(if (fingerprint.passwordless) R.string.databases_chooser_key_file else R.string.databases_chooser_password_and_key_file, key.name), ChooserRow.Glyph.KEY_FILE, ChooserRow.Tone.PLAIN)
        }
        return row(uiText(R.string.databases_chooser_password), ChooserRow.Glyph.PASSWORD, ChooserRow.Tone.PLAIN)
    }

    /** The checked ones, in the window's order: the run asks them. */
    fun checked(rows: List<ChooserRow>): List<DatabaseId> = rows.filter { it.isChecked }.map { it.id }

    /** "Unlock 3 databases"; "Unlock" while none is checked. */
    fun unlockTitle(rows: List<ChooserRow>): UiText = when (val count = checked(rows).size) {
        0 -> uiText(R.string.databases_chooser_unlock)
        else -> uiPlural(R.plurals.databases_chooser_unlock_count, count)
    }

    /** Under a list that scrolls (past five, four and a half rows show): "9 databases · scroll for the rest". */
    fun countText(rows: List<ChooserRow>): UiText? = if (rows.size > SHOWN_WHOLE) uiPlural(R.plurals.databases_chooser_count, rows.size) else null

    /** Unlock with the fingerprint: it would open one of them. */
    fun offersFingerprint(rows: List<ChooserRow>): Boolean = rows.any { it.tone == ChooserRow.Tone.READY }

    /**
     * Over the chooser, why the fingerprint opened none (keething-spec databases.chooser AC2); null when each row says its
     * own (its fingerprint off, its password due or rejected, nothing stored).
     */
    fun note(outcome: FingerprintOutcome): UiText? = when (outcome) {
        FingerprintOutcome.Cancelled -> uiText(R.string.databases_chooser_fingerprint_cancelled)
        is FingerprintOutcome.AuthenticationFailed ->
            uiText(if (outcome.reason.endsWith(".")) R.string.databases_chooser_fingerprint_failed_sentence else R.string.databases_chooser_fingerprint_failed, outcome.reason)
        FingerprintOutcome.KeyInvalidated -> uiText(R.string.databases_chooser_fingerprints_changed)
        is FingerprintOutcome.StorageError -> uiText(R.string.databases_chooser_storage_error, outcome.reason)
        FingerprintOutcome.Unlocked, FingerprintOutcome.InProgress, is FingerprintOutcome.NotAvailable, FingerprintOutcome.NothingStored,
        FingerprintOutcome.StoredPasswordRejected, is FingerprintOutcome.UnlockFailed,
        -> null
    }

    /** As many rows as show whole; more scroll. */
    const val SHOWN_WHOLE = 5
}
