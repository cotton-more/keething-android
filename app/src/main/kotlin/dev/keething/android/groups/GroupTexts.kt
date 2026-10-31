package dev.keething.android.groups

import dev.keething.card.GroupPathFormatter
import dev.keething.card.editing.GroupChoice
import dev.keething.card.editing.GroupPicker
import dev.keething.card.editing.GroupTree

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

    /** Under a level with no groups inside, or a search that found nothing and has nothing to make. */
    fun empty(tree: GroupTree, view: GroupPicker.View): String? {
        val level = view.level
        return when {
            view.isLevel && level != null && view.rows.size <= 1 -> "No groups in “${path(tree, level.id)}” yet. Type a name to make one."
            view.isLevel || view.rows.isNotEmpty() || view.offer != null -> null
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
}
