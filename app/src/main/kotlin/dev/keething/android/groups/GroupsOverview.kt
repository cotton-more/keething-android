package dev.keething.android.groups

import dev.keething.card.editing.GroupTree
import dev.keething.domain.vault.Vault
import dev.keething.search.FilterCompletion
import dev.keething.search.FilterKind
import dev.keething.search.SearchFacets
import dev.keething.search.SearchNormalizer
import java.text.Collator

/**
 * What the Groups & tags screen shows (SPEC §6.2; the Mac's Groups & Tags window, its changes in [GroupsViewModel]): the
 * group tree with how many entries are in each group, the recycle bin, the tags with their counts. Each row
 * has the search that lists its entries. The counts are those of the list: the Mac app's settings entry is
 * not one of them.
 */
internal class GroupsOverview(
    val groups: List<GroupRow>,
    val recycleBin: Int,
    val tags: List<TagRow>,
    /** The templates group's name and how many entries it holds, never shown (keething-spec groups.templates AC3). */
    val templates: Pair<String, Int>? = null,
) {
    /**
     * A group outside the bin, in tree order (parents first, siblings by name); the root group first. Groups go by id
     * ([GroupTree]): of two groups of one name in one parent each has its own counts and its own groups under it.
     */
    data class GroupRow(
        val id: String,
        val name: String,
        /** From the top-level group down; empty for the root group. */
        val path: List<String>,
        /** Entries right in it. */
        val here: Int,
        /** Entries in it and below it. */
        val total: Int,
        /** Its parent has another group of its name, case and accents aside (made by another app), as the picker marks it. */
        val hasNamesake: Boolean = false,
    ) {
        val isRoot: Boolean get() = path.isEmpty()
        val depth: Int get() = path.size

        /** The root group: the whole list; another: `@name`, as the card's group chip searches. */
        val search: String get() = if (isRoot) "" else FilterCompletion.token(FilterKind.GROUP, name) + " "
    }

    /** A tag (spellings differing only in case or diacritics are one, the first one its name) and its entries. */
    data class TagRow(val name: String, val otherSpellings: List<String>, val count: Int) {
        val search: String get() = FilterCompletion.token(FilterKind.TAG, name) + " "
    }

    companion object {
        /** The search that lists the recycle bin. */
        const val RECYCLE_BIN_SEARCH: String = "-"

        fun of(vault: Vault, collator: Collator = Collator.getInstance()): GroupsOverview {
            val entries = vault.entries
            val facets = SearchFacets.of(entries, collator)
            val here = entries.groupingBy { it.groupId }.eachCount()
            val groups = GroupTree.of(vault, collator).nodes.map { node ->
                val group = node.group
                GroupRow(group.id, group.name, group.path, here[group.id] ?: 0, node.total, node.hasNamesake)
            }
            val tags = facets.tags.map { tag ->
                val folded = SearchNormalizer.normalize(tag.name)
                val others = entries.asSequence().flatMap { it.tags }
                    .filter { it != tag.name && SearchNormalizer.normalize(it) == folded }
                    .distinct().toList()
                TagRow(tag.name, others, tag.count)
            }
            return GroupsOverview(groups, vault.recycleBin.size, tags, vault.templatesGroup?.let { it.name to vault.templates.size })
        }
    }
}
