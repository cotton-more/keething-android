package dev.keething.android.groups

import dev.keething.android.editor.deletedGroupsNote
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
    const val TOP_LEVEL = "Top level"

    /** A group's path from the top; "Top level" for the root group, "" when it is gone. */
    fun path(tree: GroupTree, groupId: String): String = tree.group(groupId)?.let { if (it.isRoot) TOP_LEVEL else GroupPathFormatter.text(it.path) }.orEmpty()

    /** Where [choice] goes, its new levels too: "Travel › Japan › Kyoto". */
    fun path(tree: GroupTree, choice: GroupChoice): String {
        val levels = tree.levels(choice) ?: return ""
        return if (levels.isEmpty()) TOP_LEVEL else GroupPathFormatter.text(levels.map { it.name })
    }

    /** The picker's "+ New group" row: "New group “qu”", "New groups “Baz › Zed”". */
    fun newGroups(names: List<String>): String =
        (if (names.size == 1) "New group" else "New groups") + " “${GroupPathFormatter.text(names)}”"

    /** Under it: "in Foo › Bar · made when the entry is saved" ([made]: when, for the screen it is on). */
    fun newGroupsPlace(tree: GroupTree, offer: GroupChoice, made: String): String = "in ${path(tree, offer.groupId)} · $made"

    /** Under the search field: the levels before the last that are not groups yet (the level searched is said above the rows). */
    fun hint(view: GroupPicker.View): String? = view.missing.takeIf { it.isNotEmpty() }?.let { missing ->
        "“${GroupPathFormatter.text(missing)}” " + if (missing.size == 1) "is not a group yet" else "are not groups yet"
    }

    /**
     * Under a level with no groups inside, or a search that found nothing and has nothing to make ([canMake]: the
     * picker offers new groups; Move to… does not).
     */
    fun empty(tree: GroupTree, view: GroupPicker.View, canMake: Boolean = true): String? {
        val level = view.level
        return when {
            view.isLevel && level != null && view.rows.size <= 1 ->
                "No groups in “${path(tree, level.id)}” yet." + if (canMake) " Type a name to make one." else ""
            view.isLevel || view.rows.isNotEmpty() || (canMake && view.offer != null) -> null
            else -> "No group has “${view.last}”."
        }
    }

    /** "2 groups" under a row whose group has groups inside; null for none. */
    fun subgroups(count: Int): String? = when (count) {
        0 -> null
        1 -> "1 group"
        else -> "$count groups"
    }

    /**
     * Under the editor's group row: the new levels made with the save ("“Korea” and “Seoul” are made when the entry is
     * saved", from three on how many, the row shows them; [saved]: when, for the screen it is on), else where a moved entry comes from ("Moves here from
     * Travel › Japan when saved"); null for neither.
     */
    fun note(tree: GroupTree, choice: GroupChoice, movedFrom: String?, saved: String = "the entry is saved"): String? {
        val names = choice.newNames.map { "“$it”" }
        return when {
            names.size == 1 -> "${names[0]} is made when $saved"
            names.size == 2 -> "${names[0]} and ${names[1]} are made when $saved"
            names.isNotEmpty() -> "${names.size} new groups are made when $saved"
            movedFrom != null -> "Moves here from ${path(tree, movedFrom).ifEmpty { "a group no longer there" }} when saved"
            else -> null
        }
    }

    // Groups & tags' changes (plans/android-groups.md G5; the AG3 mock-ups; keething-spec groups.manage).

    fun renameTitle(group: VaultGroup): String = "Rename “${group.name}”"

    fun newGroupTitle(parent: VaultGroup): String = if (parent.isRoot) "New group at the top level" else "New group in “${parent.name}”"

    /** Under the name's field: why it is refused ("“Work” has a group “Mail” already."); null when it is not. */
    fun nameProblem(name: GroupChanges.Name): String? = when (name) {
        GroupChanges.Name.Empty -> "A group needs a name."
        is GroupChanges.Name.Taken -> "${place(name.parent)} has a group “${name.taken.name}” already."
        is GroupChanges.Name.Ok, GroupChanges.Name.Unchanged -> null
    }

    /** Move to…'s picker: "Move “Japan” to…", under it the group's path and what goes with it. */
    fun moveTitle(group: VaultGroup): String = "Move “${group.name}” to…"

    fun moveSubtitle(group: VaultGroup, total: Int): String = GroupPathFormatter.text(group.path) + " · " + when (total) {
        0 -> "empty"
        1 -> "1 entry goes with it"
        else -> "$total entries, all go with it"
    }

    /** Under a target greyed in Move to…, why [group] may not go there. */
    fun moveRefusal(group: VaultGroup, refusal: GroupChanges.MoveRefusal): String = when (refusal) {
        GroupChanges.MoveRefusal.Itself -> "The group itself"
        GroupChanges.MoveRefusal.Inside -> "Inside “${group.name}”"
        GroupChanges.MoveRefusal.HereNow -> "It is here now"
        is GroupChanges.MoveRefusal.NameTaken -> "${place(refusal.target)} has a “${refusal.taken.name}” already"
    }

    /** Under a group's path on its sheet: its database, then what it holds ("Personal · 12 entries · 3 here"). */
    fun sheetMeta(database: String, total: Int, here: Int, subgroups: Int): String {
        val holds = when {
            total == 0 && subgroups == 0 -> "empty"
            total == 0 -> "no entries, " + subgroups(subgroups)
            else -> (if (total == 1) "1 entry" else "$total entries") + when (here) {
                total -> ""
                0 -> " · none here"
                else -> " · $here here"
            }
        }
        return "$database · $holds"
    }

    // The snackbars, once saved and checked.

    fun renamed(name: String): String = "Renamed to “$name”"

    fun made(name: String, parent: VaultGroup): String =
        "Made “$name” " + if (parent.isRoot) "at the top level" else "in “${parent.name}”"

    fun moved(group: VaultGroup, target: VaultGroup): String =
        "Moved “${group.name}” " + if (target.isRoot) "to the top level" else "into “${target.name}”"

    fun deleted(group: VaultGroup): String = "Deleted the empty group “${GroupPathFormatter.text(group.path)}”"

    /** The card's Move to group… (G6): where it went ([tree] as before the move), and the empty groups the move deleted. */
    fun entryMoved(tree: GroupTree, choice: GroupChoice, deleted: List<VaultGroup>): String =
        listOfNotNull("Moved to “${path(tree, choice)}”", deletedGroupsNote(deleted)).joinToString(". ")

    /** A group as the start of a sentence: "“Work”", the root group "Top level". */
    private fun place(group: VaultGroup): String = if (group.isRoot) TOP_LEVEL else "“${group.name}”"
}
