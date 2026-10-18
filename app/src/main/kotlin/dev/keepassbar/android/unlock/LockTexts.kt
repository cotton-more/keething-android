package dev.keepassbar.android.unlock

import dev.keepassbar.vault.opening.OpenFailure
import dev.keepassbar.vault.session.LockReason
import java.io.File
import kotlin.time.Duration

/** What the lock screen says about why it is locked (SPEC §6: the reason of the last lock, as a chip). */
internal data class LockNotice(val text: String, val isProblem: Boolean)

/** Nothing to say at the start and after the lock button: the user knows. */
internal fun LockReason.notice(timeout: Duration, file: File): LockNotice? = when (this) {
    LockReason.Initial, LockReason.Manual -> null
    LockReason.Timeout -> LockNotice("Locked after ${timeout.minutesText()} in the background", isProblem = false)
    LockReason.PasswordChanged -> LockNotice("The database password was changed. Enter the new one.", isProblem = true)
    is LockReason.UnlockFailed -> LockNotice(failure.describe(file), isProblem = true)
}

internal fun OpenFailure.describe(file: File): String = when (this) {
    OpenFailure.WrongPassword -> "Wrong password."
    OpenFailure.Missing -> "There is no database at ${file.path}. Syncthing may be replacing it: try again in a moment."
    OpenFailure.AccessDenied -> "KeePassBar may not read the file: allow all-files access."
    is OpenFailure.Unsupported -> "This database cannot be opened: $what."
    // Damaged is not a wrong password (SPEC §3.2): a sync may have caught the file half-written.
    is OpenFailure.Damaged -> "The file is damaged or not completely synced yet ($detail)."
    OpenFailure.OutOfMemory -> "Not enough memory to open the database."
}

private fun Duration.minutesText(): String {
    val minutes = inWholeMinutes
    return if (minutes == 1L) "1 minute" else "$minutes minutes"
}
