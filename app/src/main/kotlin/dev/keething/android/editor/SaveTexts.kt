package dev.keething.android.editor

import dev.keething.android.unlock.describe
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.session.SaveOutcome
import java.io.File

/** Why a save wrote nothing, as the screens say it (Mac `SaveFailure.message`). */
internal fun SaveFailure.describe(file: File): String = when (this) {
    SaveFailure.ChangedElsewhere ->
        "This entry was changed elsewhere (KeePassXC or another device) after it was loaded. Nothing was saved; " +
            "the new version is loaded now."
    SaveFailure.EntryNotFound -> "This entry no longer exists in the file (deleted elsewhere). Nothing was saved."
    SaveFailure.GroupNotFound -> "That group no longer exists in the file (deleted elsewhere). Nothing was saved; choose another."
    SaveFailure.RecycleBinDisabled ->
        "This database has the recycle bin turned off. Nothing was deleted; delete the entry in KeePassXC or on the Mac."
    SaveFailure.WrongPlace -> "This entry was moved elsewhere (into or out of the recycle bin). Nothing was saved."
    is SaveFailure.VerificationFailed -> "The new file did not check out ($detail), so the database was not replaced."
    is SaveFailure.BreaksFormat ->
        "The change would leave the file in a form KeePass does not accept ($what), so the database was not replaced."
    SaveFailure.FileKeepsChanging -> "The database file keeps changing (a sync?). Nothing was saved; try again in a moment."
    is SaveFailure.WriteFailed -> "Could not write the database: $detail."
    SaveFailure.OutOfMemory -> "Not enough memory to write the database. Nothing was saved."
    is SaveFailure.CannotOpen -> when (failure) {
        OpenFailure.WrongPassword -> "The database's password was changed elsewhere. Nothing was saved."
        else -> "Nothing was saved: " + failure.describe(file).replaceFirstChar(Char::lowercase)
    }
}

/** A save that did not happen, said the same way on every screen; null when it saved. */
internal fun SaveOutcome.problem(file: File): String? = when (this) {
    SaveOutcome.Saved -> null
    is SaveOutcome.Failed -> failure.describe(file)
    SaveOutcome.Interrupted -> "The database was locked; the change may not have been saved."
}

/** The session saves one change at a time. */
internal const val SAVE_BUSY = "Another change is still being saved. Nothing was saved; try again in a moment."
