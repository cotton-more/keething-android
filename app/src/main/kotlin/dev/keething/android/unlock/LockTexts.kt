package dev.keething.android.unlock

import androidx.annotation.StringRes
import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiPlural
import dev.keething.android.ui.uiText
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
internal data class LockNotice(val text: UiText, val isProblem: Boolean, val fingerprint: Boolean = false)

/**
 * Nothing to say at the start and after the lock button: the user knows. [file]: the database's path, for the words;
 * [wrongInARow]: the wrong keys typed in a row on its lock screen.
 */
internal fun LockReason.notice(timeout: Duration, file: String, wrongInARow: Int = 1): LockNotice? = when (this) {
    // After a quick task the reminder locked it again: nothing warns of its question ahead (unlock.reminder-not-a-lock AC1).
    LockReason.Initial, LockReason.Manual, LockReason.AfterQuickTask -> null
    LockReason.Timeout -> timeout.inWholeMinutes.toInt().let { minutes ->
        LockNotice(uiPlural(R.plurals.unlock_locked_after_minutes, minutes), isProblem = false)
    }
    LockReason.PasswordChanged -> LockNotice(uiText(R.string.unlock_password_changed), isProblem = true)
    LockReason.RestoredOlderKey -> LockNotice(uiText(R.string.unlock_restored_older_key), isProblem = true)
    is LockReason.UnlockFailed -> LockNotice(failure.describe(file, wrongInARow), isProblem = true)
}

/** What the lock screen says after a fingerprint attempt; null when the reason chip says it, or nothing needs saying. */
internal fun FingerprintOutcome.notice(): LockNotice? = when (this) {
    FingerprintOutcome.Unlocked, FingerprintOutcome.Cancelled, FingerprintOutcome.InProgress, is FingerprintOutcome.UnlockFailed -> null
    is FingerprintOutcome.NotAvailable -> LockNotice(uiText(R.string.unlock_fingerprint_not_available, reason), isProblem = true)
    FingerprintOutcome.NothingStored -> setUpByTypingNotice
    FingerprintOutcome.KeyInvalidated -> LockNotice(uiText(R.string.unlock_fingerprint_key_invalidated), isProblem = true)
    FingerprintOutcome.StoredPasswordRejected -> LockNotice(uiText(R.string.unlock_fingerprint_password_rejected), isProblem = true)
    // The system's own words: "Too many attempts. Try again later." and the like.
    is FingerprintOutcome.AuthenticationFailed -> LockNotice(uiPlain(reason), isProblem = true)
    is FingerprintOutcome.StorageError -> LockNotice(uiText(R.string.unlock_fingerprint_storage_error, reason), isProblem = true)
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
internal val setUpByTypingNotice = LockNotice(uiText(R.string.unlock_fingerprint_set_up_by_typing), isProblem = false, fingerprint = true)

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

/**
 * Why it did not open; [file]: the database's path on the phone, for the words. From the second wrong key in a row
 * ([wrongInARow]) a hardware key is named: the file does not tell that one is needed, and a first one is likelier a typo
 * (keething-spec app.first-start AC5).
 */
internal fun OpenFailure.describe(file: String, wrongInARow: Int = 1): UiText = when (this) {
    // The file cannot tell a typo from a missing key file (keething-spec unlock.key-file AC4).
    OpenFailure.WrongPassword ->
        wrongKey(wrongInARow, R.string.unlock_failure_wrong_password, R.string.unlock_failure_wrong_password_hardware_key)
    OpenFailure.WrongPasswordOrKeyFile ->
        wrongKey(wrongInARow, R.string.unlock_failure_wrong_password_or_key_file, R.string.unlock_failure_wrong_password_or_key_file_hardware_key)
    OpenFailure.WrongKeyFile ->
        wrongKey(wrongInARow, R.string.unlock_failure_wrong_key_file, R.string.unlock_failure_wrong_key_file_hardware_key)
    is OpenFailure.KeyFileMissing -> uiText(R.string.unlock_failure_key_file_missing, name)
    is OpenFailure.KeyFileUnusable -> uiText(R.string.unlock_failure_key_file_unusable, why)
    OpenFailure.Missing -> uiText(R.string.unlock_failure_missing, file)
    OpenFailure.AccessDenied -> uiText(R.string.unlock_failure_access_denied)
    is OpenFailure.Unsupported -> describe()
    // Damaged is not a wrong password (SPEC §3.2): a sync may have caught the file half-written.
    is OpenFailure.Damaged -> uiText(R.string.unlock_failure_damaged, detail)
    OpenFailure.OutOfMemory -> uiText(R.string.unlock_failure_out_of_memory)
    OpenFailure.Locked -> uiText(R.string.unlock_failure_locked)
}

/** A wrong key; from the second in a row, with the sentence naming a hardware key. */
private fun wrongKey(wrongInARow: Int, @StringRes first: Int, @StringRes again: Int): UiText = uiText(if (wrongInARow < 2) first else again)

/** What it is and, where something can be done, what to do (keething-spec app.first-start AC3, AC4). */
private fun OpenFailure.Unsupported.describe(): UiText = when (kind) {
    OpenFailure.Unsupported.Kind.NOT_KEEPASS -> uiText(R.string.unlock_unsupported_not_keepass)
    OpenFailure.Unsupported.Kind.OLD_FORMAT -> uiText(R.string.unlock_unsupported_old_format, what)
    OpenFailure.Unsupported.Kind.NEWER_FORMAT -> uiText(R.string.unlock_unsupported_newer_format, what)
    OpenFailure.Unsupported.Kind.TWOFISH -> uiText(R.string.unlock_unsupported_twofish)
    OpenFailure.Unsupported.Kind.CIPHER -> uiText(R.string.unlock_unsupported_cipher, what)
    OpenFailure.Unsupported.Kind.KEY_DERIVATION -> uiText(R.string.unlock_unsupported_key_derivation, what.removePrefix(KDF))
    OpenFailure.Unsupported.Kind.MEMORY -> uiText(R.string.unlock_unsupported_memory, what.removePrefix(KDF))
    OpenFailure.Unsupported.Kind.INNER_STREAM -> uiText(R.string.unlock_unsupported_inner_stream, what)
}

/** The same in a few words, for a row of the chooser ("Cannot be read: …"). */
internal fun OpenFailure.Unsupported.named(): UiText = when (kind) {
    OpenFailure.Unsupported.Kind.NOT_KEEPASS -> uiText(R.string.unlock_unsupported_named_not_keepass)
    OpenFailure.Unsupported.Kind.OLD_FORMAT -> uiText(R.string.unlock_unsupported_named_old_format, what)
    OpenFailure.Unsupported.Kind.NEWER_FORMAT -> uiText(R.string.unlock_unsupported_named_newer_format, what)
    OpenFailure.Unsupported.Kind.TWOFISH -> uiText(R.string.unlock_unsupported_named_twofish)
    OpenFailure.Unsupported.Kind.MEMORY -> uiText(R.string.unlock_unsupported_named_memory)
    OpenFailure.Unsupported.Kind.CIPHER, OpenFailure.Unsupported.Kind.INNER_STREAM -> uiText(R.string.unlock_unsupported_named_unknown, what)
    // The file layer's own words.
    OpenFailure.Unsupported.Kind.KEY_DERIVATION -> uiPlain(what)
}

/** How the file layer's words for a key derivation begin. */
private const val KDF = "key derivation " // not user text
