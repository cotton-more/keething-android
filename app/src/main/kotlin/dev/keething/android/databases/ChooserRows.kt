package dev.keething.android.databases

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
    /** Under its name: "Fingerprint", "Password + work.keyx", "Fingerprint to set up", "Not found". */
    val opensWith: String,
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
}

/** The chooser: nothing open and several in the window (plan AD13; the agreed mock-up "⋮ › Databases, the chooser and its run"). */
internal object ChooserRows {
    /** Under the title, after what happened. */
    const val HOW = "Checked ones are asked one after another."

    /** The window's databases in its order, each as its session, files and fingerprint say; [checked] as at the last unlock. */
    fun of(window: List<WindowDatabase>, lines: List<DatabaseLine>, checked: (DatabaseId) -> Boolean): List<ChooserRow> {
        val byId = lines.associateBy { it.id }
        return window.mapNotNull { database -> byId[database.id]?.let { row(database.session, it, database.fingerprint, checked(database.id)) } }
    }

    /**
     * How [line]'s database opens: open or opening already is not offered; what is not there or cannot be read cannot be
     * checked; then the fingerprint's state while it opens it, the key file, the password.
     */
    fun row(session: SessionState, line: DatabaseLine, fingerprint: BiometricState, checked: Boolean): ChooserRow {
        fun row(text: String, glyph: ChooserRow.Glyph, tone: ChooserRow.Tone, canCheck: Boolean = true) =
            ChooserRow(line, text, glyph, tone, canCheck, isChecked = canCheck && checked)

        if (session is SessionState.Unlocked) return row("Open", ChooserRow.Glyph.OPEN, ChooserRow.Tone.PLAIN, canCheck = false)
        if (session is SessionState.Unlocking) return row("Opening…", ChooserRow.Glyph.OPENING, ChooserRow.Tone.PLAIN, canCheck = false)
        line.reachWord?.let { return row(it.replaceFirstChar(Char::uppercaseChar), ChooserRow.Glyph.NOT_FOUND, ChooserRow.Tone.PROBLEM, canCheck = false) }
        val key = line.keyFile
        if (key != null && key.isMissing) {
            return row("${key.name} " + if (key.reach == Reach.NOT_FOUND) "not there" else "no access", ChooserRow.Glyph.KEY_FILE, ChooserRow.Tone.PROBLEM, canCheck = false)
        }
        when (((session as? SessionState.Locked)?.reason as? LockReason.UnlockFailed)?.failure) {
            is OpenFailure.Unsupported, is OpenFailure.Damaged, OpenFailure.AccessDenied, OpenFailure.OutOfMemory ->
                return row("Cannot be read", ChooserRow.Glyph.UNREADABLE, ChooserRow.Tone.PROBLEM, canCheck = false)
            is OpenFailure.KeyFileUnusable -> return row("${key?.name ?: "Its key file"} cannot be used", ChooserRow.Glyph.KEY_FILE, ChooserRow.Tone.PROBLEM, canCheck = false)
            else -> {}
        }
        if (fingerprint.enabled && fingerprint.availability == BiometricAvailability.Available) {
            val fingerprintRow = { text: String, tone: ChooserRow.Tone -> row(text, ChooserRow.Glyph.FINGERPRINT, tone) }
            if (!fingerprint.stored && fingerprint.setUpAgain != null) return fingerprintRow("Fingerprint to set up again", ChooserRow.Tone.WARNING)
            if (!fingerprint.stored && !fingerprint.passwordless) return fingerprintRow("Fingerprint to set up", ChooserRow.Tone.WARNING)
            return fingerprintRow("Fingerprint", ChooserRow.Tone.READY)
        }
        if (key != null) {
            return row(if (fingerprint.passwordless) "Key file ${key.name}" else "Password + ${key.name}", ChooserRow.Glyph.KEY_FILE, ChooserRow.Tone.PLAIN)
        }
        return row("Password", ChooserRow.Glyph.PASSWORD, ChooserRow.Tone.PLAIN)
    }

    /** The checked ones, in the window's order: the run asks them. */
    fun checked(rows: List<ChooserRow>): List<DatabaseId> = rows.filter { it.isChecked }.map { it.id }

    /** "Unlock 3 databases"; "Unlock" while none is checked. */
    fun unlockTitle(rows: List<ChooserRow>): String = when (val count = checked(rows).size) {
        0 -> "Unlock"
        1 -> "Unlock 1 database"
        else -> "Unlock $count databases"
    }

    /** Under a list that scrolls (past five, four and a half rows show): "9 databases · scroll for the rest". */
    fun countText(rows: List<ChooserRow>): String? = if (rows.size > SHOWN_WHOLE) "${rows.size} databases · scroll for the rest" else null

    /** Unlock with the fingerprint: it would open one of them. */
    fun offersFingerprint(rows: List<ChooserRow>): Boolean = rows.any { it.tone == ChooserRow.Tone.READY }

    /**
     * Over the chooser, why the fingerprint opened none (keething-spec databases.chooser AC2); null when each row says its
     * own (its fingerprint off, its password due or rejected, nothing stored).
     */
    fun note(outcome: FingerprintOutcome): String? = when (outcome) {
        FingerprintOutcome.Cancelled -> "The fingerprint was cancelled."
        is FingerprintOutcome.AuthenticationFailed -> "The fingerprint failed: ${outcome.reason}" + if (outcome.reason.endsWith(".")) "" else "."
        FingerprintOutcome.KeyInvalidated -> "The fingerprints on this phone changed: type the passwords to set up the fingerprint again."
        is FingerprintOutcome.StorageError -> "Could not read the stored passwords: ${outcome.reason}."
        FingerprintOutcome.Unlocked, FingerprintOutcome.InProgress, is FingerprintOutcome.NotAvailable, FingerprintOutcome.NothingStored,
        FingerprintOutcome.StoredPasswordRejected, is FingerprintOutcome.UnlockFailed,
        -> null
    }

    /** As many rows as show whole; more scroll. */
    const val SHOWN_WHOLE = 5
}
