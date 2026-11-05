package dev.keething.android.editor

import dev.keething.android.unlock.describe
import dev.keething.card.GroupPathFormatter
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.session.SaveOutcome
import dev.keething.vault.session.UnsavedChange
import java.io.File

/** Why a save wrote nothing, as the screens say it (Mac `SaveFailure.message`). */
internal fun SaveFailure.describe(file: File): String = when (this) {
    SaveFailure.ChangedElsewhere ->
        "This entry was changed elsewhere (KeePassXC or another device) after it was loaded. Nothing was saved; " +
            "the new version is loaded now."
    SaveFailure.EntryNotFound -> "This entry no longer exists in the file (deleted elsewhere). Nothing was saved."
    SaveFailure.GroupNotFound -> "That group no longer exists in the file (deleted elsewhere). Nothing was saved; choose another."
    SaveFailure.RecycleBinDisabled ->
        "This database has the recycle bin turned off. Nothing was deleted; delete the entry for good from its card."
    SaveFailure.WrongPlace -> "This entry was moved elsewhere (into or out of the recycle bin). Nothing was saved."
    is SaveFailure.VerificationFailed -> "The new file did not check out ($detail), so the database was not replaced."
    is SaveFailure.BreaksFormat ->
        "The change would leave the file in a form KeePass does not accept ($what), so the database was not replaced."
    SaveFailure.ViewOnly -> "This database is KDBX 3.1, which Keething opens to view only. Nothing was saved."
    SaveFailure.FileKeepsChanging -> "The database file keeps changing (a sync?). Nothing was saved; try again in a moment."
    is SaveFailure.WriteFailed -> "Could not write the database: $detail."
    SaveFailure.OutOfMemory -> "Not enough memory to write the database. Nothing was saved."
    SaveFailure.Locked -> "The database locked before the change was saved. Nothing was saved; the file is as it was."
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

/**
 * What a lock kept out of the file, said after the next unlock (keething-spec kdbx.hardening AC3): each change by its
 * entry's title or its group's path as [vault] has them now (the session kept their ids only). Null for none.
 */
internal fun unsavedText(changes: List<UnsavedChange>, vault: Vault): String? {
    if (changes.isEmpty()) return null
    val entries = (vault.entries + vault.recycleBin).associateBy { it.id }
    val groups = vault.groups.associateBy { it.id }
    fun entry(id: String) = entries[id]?.let { "“${it.displayTitle}”" } ?: "an entry"
    fun group(id: String) = groups[id]?.let { if (it.isRoot) "the top level" else "“${GroupPathFormatter.text(it.path)}”" } ?: "a group"
    val what = changes.map { change ->
        when (change.kind) {
            UnsavedChange.Kind.ENTRY_CHANGED -> "the change to ${entry(change.id)}"
            UnsavedChange.Kind.ENTRY_CREATED -> "a new entry"
            UnsavedChange.Kind.ENTRY_TO_RECYCLE_BIN -> "moving ${entry(change.id)} to the recycle bin"
            UnsavedChange.Kind.ENTRY_RESTORED -> "restoring ${entry(change.id)}"
            UnsavedChange.Kind.ENTRY_DELETED -> "deleting ${entry(change.id)} for good"
            UnsavedChange.Kind.GROUP_RENAMED -> "renaming the group ${group(change.id)}"
            UnsavedChange.Kind.GROUP_CREATED -> "a new group in ${group(change.id)}"
            UnsavedChange.Kind.GROUP_MOVED -> "moving the group ${group(change.id)}"
            UnsavedChange.Kind.GROUP_DELETED -> "deleting the group ${group(change.id)}"
        }
    }.distinct()
    val list = if (what.size == 1) what[0] else what.dropLast(1).joinToString(", ") + " and " + what.last()
    val verb = if (what.size == 1) "was" else "were"
    return list.replaceFirstChar(Char::uppercaseChar) + " $verb not saved: the database locked while it was being saved. " +
        "The file is as it was; make the change again."
}

/** The session takes no save while locked (a change waits for the one before it: keething-spec kdbx.hardening AC4). */
internal const val SAVE_LOCKED = "The database is locked. Nothing was saved."

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
