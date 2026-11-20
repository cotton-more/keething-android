package dev.keething.android.groups

import dev.keething.android.R
import dev.keething.android.editor.deletedGroupsNote
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiPlural
import dev.keething.android.ui.uiText
import dev.keething.card.GroupPathFormatter
import dev.keething.card.editing.GroupChanges
import dev.keething.card.editing.GroupChoice
import dev.keething.card.editing.GroupPicker
import dev.keething.card.editing.GroupTree
import dev.keething.domain.vault.VaultGroup

/**
 * What the group picker and the editor's group row say (plans/android-groups.md G2, G3; the agreed mock-ups of
 * AG3): paths with ›, the root group "Top level".
 */
internal object GroupTexts {
    val TOP_LEVEL: UiText = uiText(R.string.groups_top_level)

    /** A group's path from the top; "Top level" for the root group, "" when it is gone. */
    fun path(tree: GroupTree, groupId: String): UiText =
        tree.group(groupId)?.let { if (it.isRoot) TOP_LEVEL else uiPlain(GroupPathFormatter.text(it.path)) } ?: uiPlain("")

    /** Where [choice] goes, its new levels too: "Travel › Japan › Kyoto". */
    fun path(tree: GroupTree, choice: GroupChoice): UiText {
        val levels = tree.levels(choice) ?: return uiPlain("")
        return if (levels.isEmpty()) TOP_LEVEL else uiPlain(GroupPathFormatter.text(levels.map { it.name }))
    }

    /** The picker's "+ New group" row: "New group “qu”", "New groups “Baz › Zed”". */
    fun newGroups(names: List<String>): UiText = uiPlural(R.plurals.groups_picker_new_groups, names.size, GroupPathFormatter.text(names))

    /** Under it: "in Foo › Bar · made when the entry is saved" ([made]: when, for the screen it is on). */
    fun newGroupsPlace(tree: GroupTree, offer: GroupChoice, made: String): UiText =
        uiText(R.string.groups_picker_new_place, path(tree, offer.groupId), made)

    /** Under the search field: the levels before the last that are not groups yet (the level searched is said above the rows). */
    fun hint(view: GroupPicker.View): UiText? = view.missing.takeIf { it.isNotEmpty() }?.let { missing ->
        uiPlural(R.plurals.groups_picker_not_groups_yet, missing.size, GroupPathFormatter.text(missing))
    }

    /**
     * Under a level with no groups inside, or a search that found nothing and has nothing to make ([canMake]: the
     * picker offers new groups; Move to… does not).
     */
    fun empty(tree: GroupTree, view: GroupPicker.View, canMake: Boolean = true): UiText? {
        val level = view.level
        return when {
            view.isLevel && level != null && view.rows.size <= 1 ->
                uiText(if (canMake) R.string.groups_picker_level_empty_make else R.string.groups_picker_level_empty, path(tree, level.id))
            view.isLevel || view.rows.isNotEmpty() || (canMake && (view.offer != null || view.hidden != null)) -> null
            else -> uiText(R.string.groups_picker_none_found, view.last)
        }
    }

    /** "2 groups" under a row whose group has groups inside; null for none. */
    fun subgroups(count: Int): UiText? = if (count == 0) null else uiPlural(R.plurals.groups_subgroups, count)

    /**
     * Under the editor's group row: the new levels made with the save ("“Korea” and “Seoul” are made when the entry is
     * saved", from three on how many, the row shows them; [entries]: several entries are saved, on the screen that saves
     * them), else where a moved entry comes from ("Moves here from Travel › Japan when saved"); null for neither.
     */
    fun note(tree: GroupTree, choice: GroupChoice, movedFrom: String?, entries: Boolean = false): UiText? {
        val names = choice.newNames
        return when {
            names.size == 1 -> uiText(if (entries) R.string.groups_note_one_made_entries else R.string.groups_note_one_made, names[0])
            names.size == 2 -> uiText(if (entries) R.string.groups_note_two_made_entries else R.string.groups_note_two_made, names[0], names[1])
            names.isNotEmpty() -> uiPlural(if (entries) R.plurals.groups_note_many_made_entries else R.plurals.groups_note_many_made, names.size)
            movedFrom != null -> tree.group(movedFrom)?.let { uiText(R.string.groups_note_moved_from, path(tree, movedFrom)) }
                ?: uiText(R.string.groups_note_moved_from_gone)
            else -> null
        }
    }

    // Groups & tags' changes (plans/android-groups.md G5; the AG3 mock-ups; keething-spec groups.manage).

    fun renameTitle(group: VaultGroup): UiText = uiText(R.string.groups_rename_title, group.name)

    fun newGroupTitle(parent: VaultGroup): UiText =
        if (parent.isRoot) uiText(R.string.groups_new_title_top) else uiText(R.string.groups_new_title_in, parent.name)

    /** Under the name's field: why it is refused ("“Work” has a group “Mail” already."); null when it is not. */
    fun nameProblem(name: GroupChanges.Name): UiText? = when (name) {
        GroupChanges.Name.Empty -> uiText(R.string.groups_name_empty)
        is GroupChanges.Name.Taken ->
            if (name.parent.isRoot) uiText(R.string.groups_name_taken_top, name.taken.name)
            else uiText(R.string.groups_name_taken, name.parent.name, name.taken.name)
        is GroupChanges.Name.TakenByHidden -> hiddenTaken(name.parent, name.hidden)
        is GroupChanges.Name.Ok, GroupChanges.Name.Unchanged -> null
    }

    /** "Top level has the recycle bin, “Recycle Bin”, which is not shown among the groups.": the name is that hidden group's. */
    private fun hiddenTaken(parent: VaultGroup, hidden: GroupTree.Hidden): UiText = when {
        hidden.isRecycleBin && parent.isRoot -> uiText(R.string.groups_name_taken_bin_top, hidden.group.name)
        hidden.isRecycleBin -> uiText(R.string.groups_name_taken_bin, parent.name, hidden.group.name)
        parent.isRoot -> uiText(R.string.groups_name_taken_templates_top, hidden.group.name)
        else -> uiText(R.string.groups_name_taken_templates, parent.name, hidden.group.name)
    }

    /** In the picker, in place of New group: the recycle bin or the templates group has the name there; null otherwise. */
    fun hiddenTaken(tree: GroupTree, view: GroupPicker.View): UiText? {
        val hidden = view.hidden ?: return null
        val parent = tree.group(hidden.parentId) ?: return null
        return uiText(R.string.groups_picker_hidden_taken, hiddenTaken(parent, hidden))
    }

    /** Move to…'s picker: "Move “Japan” to…", under it the group's path and what goes with it. */
    fun moveTitle(group: VaultGroup): UiText = uiText(R.string.groups_move_title, group.name)

    fun moveSubtitle(group: VaultGroup, total: Int): UiText {
        val path = GroupPathFormatter.text(group.path)
        return if (total == 0) uiText(R.string.groups_move_subtitle_empty, path) else uiPlural(R.plurals.groups_move_subtitle, total, path, total)
    }

    /** Under a target greyed in Move to…, why [group] may not go there. */
    fun moveRefusal(group: VaultGroup, refusal: GroupChanges.MoveRefusal): UiText = when (refusal) {
        GroupChanges.MoveRefusal.Itself -> uiText(R.string.groups_move_refused_itself)
        GroupChanges.MoveRefusal.Inside -> uiText(R.string.groups_move_refused_inside, group.name)
        GroupChanges.MoveRefusal.HereNow -> uiText(R.string.groups_move_refused_here_now)
        is GroupChanges.MoveRefusal.NameTaken ->
            if (refusal.target.isRoot) uiText(R.string.groups_move_refused_taken_top, refusal.taken.name)
            else uiText(R.string.groups_move_refused_taken, refusal.target.name, refusal.taken.name)
        is GroupChanges.MoveRefusal.NameTakenByHidden -> {
            val (target, hidden) = refusal.target to refusal.hidden
            when {
                hidden.isRecycleBin && target.isRoot -> uiText(R.string.groups_move_refused_taken_bin_top, hidden.group.name)
                hidden.isRecycleBin -> uiText(R.string.groups_move_refused_taken_bin, target.name, hidden.group.name)
                target.isRoot -> uiText(R.string.groups_move_refused_taken_templates_top, hidden.group.name)
                else -> uiText(R.string.groups_move_refused_taken_templates, target.name, hidden.group.name)
            }
        }
    }

    /**
     * Under a group's path on its sheet: its database, then what it holds ("Personal · 12 entries · 3 here"); "empty"
     * only when it can be deleted ([deletable]), else "nothing shown in it" (the bin, the templates group or the Mac
     * app's settings entry is in it).
     */
    fun sheetMeta(database: String, total: Int, here: Int, subgroups: Int, deletable: Boolean): UiText = when {
        total == 0 && subgroups == 0 && deletable -> uiText(R.string.groups_sheet_meta_empty, database)
        total == 0 && subgroups == 0 -> uiText(R.string.groups_sheet_meta_nothing_shown, database)
        total == 0 -> uiPlural(R.plurals.groups_sheet_meta_no_entries, subgroups, database, subgroups)
        here == total -> uiPlural(R.plurals.groups_sheet_meta_entries, total, database, total)
        here == 0 -> uiPlural(R.plurals.groups_sheet_meta_entries_none_here, total, database, total)
        else -> uiPlural(R.plurals.groups_sheet_meta_entries_here, total, database, total, here)
    }

    // The snackbars, once saved and checked.

    fun renamed(name: String): UiText = uiText(R.string.groups_renamed, name)

    fun made(name: String, parent: VaultGroup): UiText =
        if (parent.isRoot) uiText(R.string.groups_made_top, name) else uiText(R.string.groups_made_in, name, parent.name)

    fun moved(group: VaultGroup, target: VaultGroup): UiText =
        if (target.isRoot) uiText(R.string.groups_moved_top, group.name) else uiText(R.string.groups_moved_into, group.name, target.name)

    fun deleted(group: VaultGroup): UiText = uiText(R.string.groups_deleted, GroupPathFormatter.text(group.path))

    /** The card's Move to group… (G6): where it went ([tree] as before the move), and the empty groups the move deleted. */
    fun entryMoved(tree: GroupTree, choice: GroupChoice, deleted: List<VaultGroup>): UiText {
        val note = deletedGroupsNote(deleted)
        return if (note == null) uiText(R.string.groups_entry_moved, path(tree, choice)) else uiText(R.string.groups_entry_moved_with_note, path(tree, choice), note)
    }
}
