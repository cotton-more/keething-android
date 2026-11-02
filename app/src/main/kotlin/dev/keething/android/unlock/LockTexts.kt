package dev.keething.android.unlock

import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.FingerprintOutcome
import dev.keething.vault.biometric.SetUpAgain
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.session.LockReason
import kotlin.time.Duration

/**
 * What the lock screen says about why it is locked (SPEC §6: the reason of the last lock, as a chip). [fingerprint]: it
 * is about the fingerprint's setting up, and says so with its icon.
 */
internal data class LockNotice(val text: String, val isProblem: Boolean, val fingerprint: Boolean = false)

/** Nothing to say at the start and after the lock button: the user knows. [file]: the database's path, for the words. */
internal fun LockReason.notice(timeout: Duration, file: String): LockNotice? = when (this) {
    LockReason.Initial, LockReason.Manual -> null
    LockReason.Timeout -> LockNotice("Locked after ${timeout.minutesText()} in the background", isProblem = false)
    LockReason.PasswordChanged -> LockNotice("The database password was changed. Enter the new one.", isProblem = true)
    is LockReason.UnlockFailed -> LockNotice(failure.describe(file), isProblem = true)
}

/** What the lock screen says after a fingerprint attempt; null when the reason chip says it, or nothing needs saying. */
internal fun FingerprintOutcome.notice(): LockNotice? = when (this) {
    FingerprintOutcome.Unlocked, FingerprintOutcome.Cancelled, FingerprintOutcome.InProgress, is FingerprintOutcome.UnlockFailed -> null
    is FingerprintOutcome.NotAvailable -> LockNotice("The fingerprint cannot be used: $reason.", isProblem = true)
    FingerprintOutcome.NothingStored -> setUpByTypingNotice
    FingerprintOutcome.KeyInvalidated ->
        LockNotice("The fingerprints on this phone changed. Type the password to set up the fingerprint again.", isProblem = true)
    FingerprintOutcome.StoredPasswordRejected ->
        LockNotice("The database password was changed. Type the new one; the fingerprint will use it.", isProblem = true)
    // The system's own words: "Too many attempts. Try again later." and the like.
    is FingerprintOutcome.AuthenticationFailed -> LockNotice(reason, isProblem = true)
    is FingerprintOutcome.StorageError -> LockNotice("The stored password could not be read ($reason).", isProblem = true)
    FingerprintOutcome.PasswordDue -> null
}

/**
 * Why a database's stored password went (keething-spec databases.not-open AC1): its lock screen says it as its chip does,
 * also when one fingerprint for several said it (plan AD13; unlock.biometric-several AC4).
 */
internal fun SetUpAgain.notice(): LockNotice = when (this) {
    SetUpAgain.FINGERPRINTS_CHANGED -> FingerprintOutcome.KeyInvalidated.notice()
    SetUpAgain.PASSWORD_REJECTED -> FingerprintOutcome.StoredPasswordRejected.notice()
}!!

/** The fingerprint is on for the database and nothing is stored yet: the password typed now sets it up (SPEC §5). */
internal val setUpByTypingNotice = LockNotice("Type the password once to set up the fingerprint.", isProblem = false, fingerprint = true)

/** What the one button beside the password field does (SPEC §6.2; keething-spec unlock.lock-screen-keyboard AC3). */
internal enum class Beside {
    /** The arrow: unlocks with what is typed. */
    UNLOCK,

    /** The fingerprint: asks for it, and the stored password (or the key file alone) opens the database. */
    FINGERPRINT,

    /**
     * The fingerprint, typing or not, to a database whose fingerprint was never chosen: unlocks with what is typed, and
     * whether to use the fingerprint is asked over its list once it opened (unlock.biometric AC4).
     */
    UNLOCK_THEN_ASK,
}

/**
 * The one button beside the password field: the fingerprint while the field is empty and the fingerprint can open the
 * database; the fingerprint, which unlocks with what is typed, while the database's fingerprint was never chosen; else
 * Unlock. [offered]: the screen may offer it at all (its file and key file there, not in the chooser's run).
 */
internal fun besideField(biometric: BiometricState, offered: Boolean, typed: Boolean): Beside = when {
    !offered -> Beside.UNLOCK
    biometric.canUnlock -> if (typed) Beside.UNLOCK else Beside.FINGERPRINT
    biometric.canAsk -> Beside.UNLOCK_THEN_ASK
    else -> Beside.UNLOCK
}

/** The fingerprint waits while the password is due (SPEC §5): the screen says why it is not offered. */
internal fun passwordDueNotice(days: Int): LockNotice =
    LockNotice("It has been $days days: type the master password, so it is not forgotten.", isProblem = false)

/** Why it did not open; [file]: the database's path on the phone, for the words. */
internal fun OpenFailure.describe(file: String): String = when (this) {
    // The file cannot tell a typo from a missing key file (keething-spec unlock.key-file AC4).
    OpenFailure.WrongPassword -> "Wrong password — or this database needs a key file."
    OpenFailure.WrongPasswordOrKeyFile -> "Wrong password or key file."
    OpenFailure.WrongKeyFile -> "This key file does not open the database (or it needs a password too)."
    is OpenFailure.KeyFileMissing -> "The key file $name is not there."
    is OpenFailure.KeyFileUnusable -> "The key file cannot be used: $why."
    OpenFailure.Missing -> "There is no database at $file. Syncthing may be replacing it: try again in a moment."
    OpenFailure.AccessDenied -> "Keething may not read the file any more: locate it to choose its folder again."
    is OpenFailure.Unsupported -> "This database cannot be opened: $what."
    // Damaged is not a wrong password (SPEC §3.2): a sync may have caught the file half-written.
    is OpenFailure.Damaged -> "The file is damaged or not completely synced yet ($detail)."
    OpenFailure.OutOfMemory -> "Not enough memory to open the database."
}

private fun Duration.minutesText(): String {
    val minutes = inWholeMinutes
    return if (minutes == 1L) "1 minute" else "$minutes minutes"
}
