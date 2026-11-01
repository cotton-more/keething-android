package dev.keething.android.groups

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
internal class GroupsOverview(val groups: List<GroupRow>, val recycleBin: Int, val tags: List<TagRow>) {
    /** A group outside the bin, in tree order (parents first, siblings by name); the root group first. */
    data class GroupRow(
        val id: String,
        val name: String,
        /** From the top-level group down; empty for the root group. */
        val path: List<String>,
        /** Entries right in it. */
        val here: Int,
        /** Entries in it and below it. */
        val total: Int,
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
            val facets = SearchFacets.of(entries, collator, groupPaths = vault.groups.map { it.path })
            val here = entries.groupingBy { it.groupId }.eachCount()
            val byPath = vault.groups.groupBy { it.path }
            val groups = buildList {
                vault.groups.firstOrNull { it.isRoot }?.let { add(GroupRow(it.id, it.name, it.path, here[it.id] ?: 0, entries.size)) }
                for (facet in facets.groups) {
                    for (group in byPath[facet.path].orEmpty()) add(GroupRow(group.id, group.name, group.path, here[group.id] ?: 0, facet.count))
                }
            }
            val tags = facets.tags.map { tag ->
                val folded = SearchNormalizer.normalize(tag.name)
                val others = entries.asSequence().flatMap { it.tags }
                    .filter { it != tag.name && SearchNormalizer.normalize(it) == folded }
                    .distinct().toList()
                TagRow(tag.name, others, tag.count)
            }
            return GroupsOverview(groups, vault.recycleBin.size, tags)
        }
    }
}
