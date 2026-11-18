package dev.keething.android.editor

import dev.keething.android.R
import dev.keething.android.session.SizeTexts
import dev.keething.android.ui.UiText
import dev.keething.android.ui.afterColon
import dev.keething.android.ui.uiListed
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiPlural
import dev.keething.android.ui.uiText
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
internal fun SaveFailure.describe(file: File): UiText = when (this) {
    SaveFailure.ChangedElsewhere -> uiText(R.string.editor_save_failure_changed_elsewhere)
    SaveFailure.EntryNotFound -> uiText(R.string.editor_save_failure_entry_not_found)
    SaveFailure.GroupNotFound -> uiText(R.string.editor_save_failure_group_not_found)
    SaveFailure.RecycleBinDisabled -> uiText(R.string.editor_save_failure_recycle_bin_disabled)
    SaveFailure.WrongPlace -> uiText(R.string.editor_save_failure_wrong_place)
    SaveFailure.VersionGone -> uiText(R.string.editor_save_failure_version_gone)
    is SaveFailure.VerificationFailed -> uiText(R.string.editor_save_failure_verification_failed, detail)
    is SaveFailure.BreaksFormat -> uiText(R.string.editor_save_failure_breaks_format, what)
    SaveFailure.ViewOnly -> uiText(R.string.editor_save_failure_view_only)
    is SaveFailure.CopyNotKept -> uiText(R.string.editor_save_failure_copy_not_kept, detail)
    SaveFailure.CopyGone -> uiText(R.string.editor_save_failure_copy_gone)
    is SaveFailure.CopyNotOpened -> when {
        failure.isWrongKey -> uiText(R.string.editor_save_failure_copy_wrong_key)
        else -> uiText(R.string.editor_save_failure_copy_not_opened, openFailureInSentence(failure, file))
    }
    SaveFailure.BackupGone -> uiText(R.string.editor_save_failure_backup_gone)
    SaveFailure.BackupDamaged -> uiText(R.string.editor_save_failure_backup_damaged)
    is SaveFailure.BackupNotOpened -> uiText(R.string.editor_save_failure_backup_not_opened, openFailureInSentence(failure, file))
    SaveFailure.FileKeepsChanging -> uiText(R.string.editor_save_failure_file_keeps_changing)
    is SaveFailure.WriteFailed -> uiText(R.string.editor_save_failure_write_failed, detail)
    SaveFailure.OutOfMemory -> SizeTexts.OUT_OF_MEMORY
    is SaveFailure.TooLarge -> SizeTexts.saveRefused(alone)
    SaveFailure.Locked -> uiText(R.string.editor_save_failure_locked)
    SaveFailure.InvalidName -> uiText(R.string.editor_save_failure_invalid_name)
    SaveFailure.GroupNameTaken -> uiText(R.string.editor_save_failure_group_name_taken)
    SaveFailure.GroupNotChangeable -> uiText(R.string.editor_save_failure_group_not_changeable)
    SaveFailure.GroupNotEmpty -> uiText(R.string.editor_save_failure_group_not_empty)
    is SaveFailure.CannotOpen -> when (failure) {
        OpenFailure.WrongPassword -> uiText(R.string.editor_save_failure_password_changed)
        else -> uiText(R.string.editor_save_failure_cannot_open, openFailureInSentence(failure, file))
    }
}

/** Why the file did not open, as the second half of a sentence. It comes from the unlock screen's texts. */
private fun openFailureInSentence(failure: OpenFailure, file: File): UiText =
    failure.describe(file.path).afterColon()

/** A save that did not happen, said the same way on every screen; null when it saved. */
internal fun SaveOutcome.problem(file: File): UiText? = when (this) {
    is SaveOutcome.Saved -> null
    is SaveOutcome.Failed -> failure.describe(file)
    SaveOutcome.Interrupted -> uiText(R.string.editor_save_interrupted)
}

/**
 * What a lock kept out of the file, said after the next unlock (keething-spec kdbx.hardening AC3): each change by its
 * entry's title or its group's path as [vault] has them now (the session kept their ids only). Null for none.
 */
internal fun unsavedText(changes: List<UnsavedChange>, vault: Vault): UiText? {
    if (changes.isEmpty()) return null
    val entries = (vault.entries + vault.recycleBin).associateBy { it.id }
    val groups = vault.groups.associateBy { it.id }
    fun entry(id: String) = entries[id]?.let { quoted(it.displayTitle) } ?: uiText(R.string.editor_unsaved_an_entry)
    fun group(id: String) = groups[id]?.let { if (it.isRoot) uiText(R.string.editor_unsaved_top_level) else quoted(GroupPathFormatter.text(it.path)) }
        ?: uiText(R.string.editor_unsaved_a_group)
    // Each change once, by what it is and what it names; the first one starts the sentence.
    val what = changes.map { change ->
        when (change.kind) {
            UnsavedChange.Kind.ENTRY_CHANGED, UnsavedChange.Kind.ENTRY_TO_RECYCLE_BIN, UnsavedChange.Kind.ENTRY_RESTORED,
            UnsavedChange.Kind.ENTRY_DELETED, UnsavedChange.Kind.VERSION_RESTORED -> change.kind to entry(change.id)
            UnsavedChange.Kind.GROUP_RENAMED, UnsavedChange.Kind.GROUP_CREATED, UnsavedChange.Kind.GROUP_MOVED,
            UnsavedChange.Kind.GROUP_DELETED -> change.kind to group(change.id)
            UnsavedChange.Kind.ENTRY_CREATED, UnsavedChange.Kind.NAME_AND_COLOR -> change.kind to null
        }
    }.distinct()
    val items = what.mapIndexed { i, (kind, name) ->
        val id = if (i == 0) kind.first else kind.within
        if (name == null) uiText(id) else uiText(id, name)
    }
    return uiPlural(R.plurals.editor_unsaved, items.size, uiListed(items))
}

/** The change as it starts the sentence, and within it. */
private val UnsavedChange.Kind.first: Int
    get() = when (this) {
        UnsavedChange.Kind.ENTRY_CHANGED -> R.string.editor_unsaved_entry_changed_first
        UnsavedChange.Kind.ENTRY_CREATED -> R.string.editor_unsaved_entry_created_first
        UnsavedChange.Kind.ENTRY_TO_RECYCLE_BIN -> R.string.editor_unsaved_entry_to_recycle_bin_first
        UnsavedChange.Kind.ENTRY_RESTORED -> R.string.editor_unsaved_entry_restored_first
        UnsavedChange.Kind.ENTRY_DELETED -> R.string.editor_unsaved_entry_deleted_first
        UnsavedChange.Kind.VERSION_RESTORED -> R.string.editor_unsaved_version_restored_first
        UnsavedChange.Kind.GROUP_RENAMED -> R.string.editor_unsaved_group_renamed_first
        UnsavedChange.Kind.GROUP_CREATED -> R.string.editor_unsaved_group_created_first
        UnsavedChange.Kind.GROUP_MOVED -> R.string.editor_unsaved_group_moved_first
        UnsavedChange.Kind.GROUP_DELETED -> R.string.editor_unsaved_group_deleted_first
        UnsavedChange.Kind.NAME_AND_COLOR -> R.string.editor_unsaved_name_and_color_first
    }

private val UnsavedChange.Kind.within: Int
    get() = when (this) {
        UnsavedChange.Kind.ENTRY_CHANGED -> R.string.editor_unsaved_entry_changed
        UnsavedChange.Kind.ENTRY_CREATED -> R.string.editor_unsaved_entry_created
        UnsavedChange.Kind.ENTRY_TO_RECYCLE_BIN -> R.string.editor_unsaved_entry_to_recycle_bin
        UnsavedChange.Kind.ENTRY_RESTORED -> R.string.editor_unsaved_entry_restored
        UnsavedChange.Kind.ENTRY_DELETED -> R.string.editor_unsaved_entry_deleted
        UnsavedChange.Kind.VERSION_RESTORED -> R.string.editor_unsaved_version_restored
        UnsavedChange.Kind.GROUP_RENAMED -> R.string.editor_unsaved_group_renamed
        UnsavedChange.Kind.GROUP_CREATED -> R.string.editor_unsaved_group_created
        UnsavedChange.Kind.GROUP_MOVED -> R.string.editor_unsaved_group_moved
        UnsavedChange.Kind.GROUP_DELETED -> R.string.editor_unsaved_group_deleted
        UnsavedChange.Kind.NAME_AND_COLOR -> R.string.editor_unsaved_name_and_color
    }

/** A name in quotes: “GitHub”. */
private fun quoted(name: String): UiText = uiText(R.string.editor_quoted, name)

/** The session takes no save while locked (a change waits for the one before it: keething-spec kdbx.hardening AC4). */
internal val SAVE_LOCKED: UiText = uiText(R.string.editor_save_locked)

/**
 * A save that went through, as its snackbar says it (SPEC §6.2), with the empty groups it [deleted] (keething-spec
 * groups.entry-group AC7, plans/android-groups.md G4).
 */
internal fun savedText(deleted: List<VaultGroup>): UiText =
    deletedGroupsNote(deleted)?.let { uiText(R.string.editor_saved_with_note, it) } ?: uiText(R.string.editor_saved)

/** "The empty group “Work › Old” was deleted.", by their paths in the order given; null for none. */
internal fun deletedGroupsNote(deleted: List<VaultGroup>): UiText? {
    if (deleted.isEmpty()) return null
    val names = deleted.map { quoted(GroupPathFormatter.text(it.path)) }
    return uiPlural(R.plurals.editor_deleted_groups, names.size, uiListed(names))
}
