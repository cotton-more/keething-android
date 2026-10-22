package dev.keepassbar.search

import dev.keepassbar.domain.vault.Entry
import java.text.Collator

/** A group of the vault with the number of entries in it and below it. */
public data class GroupFacet(
    /** From the top-level group down; never empty. */
    val path: List<String>,
    val count: Int,
) {
    val name: String get() = path.last()
}

public data class TagFacet(val name: String, val count: Int)

/** An attachment name with the number of entries that have a file by that name. */
public data class AttachmentFacet(val name: String, val count: Int)

/** The groups, tags and attachment names of a vault, for the `@` / `#` / `+` suggestions (Mac `SearchFacets`). */
public class SearchFacets private constructor(
    /**
     * Every group that holds entries (directly or below), and every one of the `groupPaths` given (empty ones
     * too, with 0); parents before children, siblings by name.
     */
    public val groups: List<GroupFacet>,
    /** Every tag by name; tags differing only in case or diacritics are one (the first spelling wins). */
    public val tags: List<TagFacet>,
    public val attachments: List<AttachmentFacet>,
    public val entriesWithAttachments: Int,
) {
    /** Groups whose name starts with [partial] (folded), in tree order. */
    public fun groups(partial: String): List<GroupFacet> = SearchNormalizer.normalize(partial).let { prefix ->
        groups.filter { SearchNormalizer.normalize(it.name).startsWith(prefix) }
    }

    public fun tags(partial: String): List<TagFacet> = SearchNormalizer.normalize(partial).let { prefix ->
        tags.filter { SearchNormalizer.normalize(it.name).startsWith(prefix) }
    }

    public fun attachments(partial: String): List<AttachmentFacet> = SearchNormalizer.normalize(partial).let { prefix ->
        attachments.filter { SearchNormalizer.normalize(it.name).startsWith(prefix) }
    }

    public companion object {
        /** [groupPaths]: the vault's groups, so that a group without entries is there too (the Groups screen). */
        public fun of(
            entries: List<Entry>,
            collator: Collator = Collator.getInstance(),
            groupPaths: List<List<String>> = emptyList(),
        ): SearchFacets {
            val sorter = (collator.clone() as Collator).apply { strength = Collator.SECONDARY }
            val groupCounts = LinkedHashMap<List<String>, Int>()
            for (path in groupPaths) if (path.isNotEmpty()) groupCounts[path] = 0
            for (entry in entries) {
                for (depth in entry.groupPath.indices) {
                    val path = entry.groupPath.subList(0, depth + 1).toList()
                    groupCounts[path] = (groupCounts[path] ?: 0) + 1
                }
            }
            val groups = groupCounts.map { GroupFacet(it.key, it.value) }.sortedWith { a, b -> treeOrder(a.path, b.path, sorter) }
            val tags = counted(entries.map { it.tags }, sorter).map { TagFacet(it.first, it.second) }
            val attachments = counted(entries.map { e -> e.attachments.map { it.name } }, sorter).map { AttachmentFacet(it.first, it.second) }
            return SearchFacets(groups, tags, attachments, entries.count { it.attachments.isNotEmpty() })
        }

        /** Names counted once per entry, folded together (the first spelling wins), sorted by name. */
        private fun counted(namesPerEntry: List<List<String>>, sorter: Collator): List<Pair<String, Int>> {
            val counts = LinkedHashMap<String, Pair<String, Int>>()
            for (names in namesPerEntry) {
                for (folded in names.map(SearchNormalizer::normalize).toSet()) {
                    val spelling = names.first { SearchNormalizer.normalize(it) == folded }
                    val current = counts[folded] ?: (spelling to 0)
                    counts[folded] = current.first to current.second + 1
                }
            }
            return counts.values.sortedWith { a, b -> sorter.compare(a.first, b.first) }
        }

        private fun treeOrder(a: List<String>, b: List<String>, sorter: Collator): Int {
            for ((x, y) in a.zip(b)) {
                val byName = sorter.compare(x, y)
                if (byName != 0) return byName
                if (x != y) return x.compareTo(y)
            }
            return a.size.compareTo(b.size)
        }
    }
}
