package dev.keething.android.editor

import dev.keething.android.unlock.describe
import dev.keething.card.GroupPathFormatter
import dev.keething.domain.vault.VaultGroup
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
    SaveFailure.InvalidName -> "A group needs a name. Nothing was saved."
    SaveFailure.GroupNameTaken -> "A group of that name is already there (made elsewhere?). Nothing was saved."
    SaveFailure.GroupNotChangeable ->
        "This group cannot be changed so (the top level, the recycle bin or a group in it, or a move into itself or " +
            "where it is; changed elsewhere?). Nothing was saved."
    SaveFailure.GroupNotEmpty -> "The group is not empty any more (changed elsewhere?). Nothing was deleted."
    is SaveFailure.CannotOpen -> when (failure) {
        OpenFailure.WrongPassword -> "The database's password was changed elsewhere. Nothing was saved."
        else -> "Nothing was saved: " + failure.describe(file.path).replaceFirstChar(Char::lowercase)
    }
}

/** A save that did not happen, said the same way on every screen; null when it saved. */
internal fun SaveOutcome.problem(file: File): String? = when (this) {
    is SaveOutcome.Saved -> null
    is SaveOutcome.Failed -> failure.describe(file)
    SaveOutcome.Interrupted -> "The database was locked; the change may not have been saved."
}

/** The session saves one change at a time. */
internal const val SAVE_BUSY = "Another change is still being saved. Nothing was saved; try again in a moment."

/**
 * A save that went through, as its snackbar says it (SPEC §6.2), with the empty groups it [deleted] (keething-spec
 * groups.entry-group AC7, plans/android-groups.md G4).
 */
internal fun savedText(deleted: List<VaultGroup>): String =
    listOfNotNull("Saved and checked", deletedGroupsNote(deleted)).joinToString(". ")

/** "The empty group “Work › Old” was deleted.", by their paths in the order given; null for none. */
internal fun deletedGroupsNote(deleted: List<VaultGroup>): String? {
    val names = deleted.map { "“${GroupPathFormatter.text(it.path)}”" }
    return when (names.size) {
        0 -> null
        1 -> "The empty group ${names[0]} was deleted."
        else -> "The empty groups ${names.dropLast(1).joinToString(", ")} and ${names.last()} were deleted."
    }
}
