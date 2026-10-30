package dev.keething.search

import dev.keething.domain.vault.Entry
import java.text.Collator

/** How many of a facet's entries are in one database (keething-spec search.suggestions AC8: one name, a count per database). */
public data class DatabaseCount(
    /** The database's label, as the index was given it. */
    val database: String,
    val count: Int,
)

/** A group of the vault with the number of entries in it and below it. */
public data class GroupFacet(
    /** From the top-level group down; never empty. */
    val path: List<String>,
    val count: Int,
    /** The count per database, in the index's order of databases; empty for entries indexed without databases. */
    val databases: List<DatabaseCount> = emptyList(),
) {
    val name: String get() = path.last()
}

public data class TagFacet(val name: String, val count: Int, val databases: List<DatabaseCount> = emptyList())

/** An attachment name with the number of entries that have a file by that name. */
public data class AttachmentFacet(val name: String, val count: Int, val databases: List<DatabaseCount> = emptyList())

/** A database of the index (its label) with the number of its entries. */
public data class DatabaseFacet(val name: String, val count: Int)

/**
 * The groups, tags, attachment names and databases of a set of entries, for the `@` / `#` / `+` / `!` suggestions (Mac
 * `SearchFacets`): a vault's (the Groups screen, the editor), or those of the entries passing a query's filters
 * ([EntrySearch.facets]).
 */
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
    /** The databases the entries are in, in the index's order; empty for entries indexed without databases. */
    public val databases: List<DatabaseFacet>,
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

    /** Databases whose label starts with [partial] (folded), as the `!` filter keeps them. */
    public fun databases(partial: String): List<DatabaseFacet> = SearchNormalizer.normalize(partial).let { prefix ->
        databases.filter { SearchNormalizer.normalize(it.name).startsWith(prefix) }
    }

    /** An entry with its tags and attachment names folded (in the entry's order) and its database's label. */
    private class Counted(val entry: Entry, val tags: List<String>, val attachments: List<String>, val database: String?)

    /** Counts a name: in all, and per database. */
    private class Tally(val name: String) {
        var count = 0
        val byDatabase = HashMap<String, Int>()

        fun add(database: String?) {
            count++
            if (database != null) byDatabase[database] = (byDatabase[database] ?: 0) + 1
        }
    }

    public companion object {
        /** [groupPaths]: the vault's groups, so that a group without entries is there too (the Groups screen). */
        public fun of(
            entries: List<Entry>,
            collator: Collator = Collator.getInstance(),
            groupPaths: List<List<String>> = emptyList(),
        ): SearchFacets = counting(
            entries.map { entry ->
                Counted(entry, entry.tags.map(SearchNormalizer::normalize), entry.attachments.map { SearchNormalizer.normalize(it.name) }, null)
            },
            secondary(collator),
            groupPaths,
        )

        /** The facets of indexed entries, their folding done once by the index; [sorter] already secondary. */
        internal fun of(items: List<SearchIndex.Item>, sorter: Collator): SearchFacets =
            counting(items.map { Counted(it.entry, it.tags, it.attachments, it.database) }, sorter, emptyList())

        private fun secondary(collator: Collator) = (collator.clone() as Collator).apply { strength = Collator.SECONDARY }

        private fun counting(entries: List<Counted>, sorter: Collator, groupPaths: List<List<String>>): SearchFacets {
            val databaseCounts = LinkedHashMap<String, Int>()
            val groupCounts = LinkedHashMap<List<String>, Tally>()
            for (path in groupPaths) if (path.isNotEmpty()) groupCounts[path] = Tally(path.last())
            // Tags and attachment names folded together, the first spelling kept.
            val tagCounts = LinkedHashMap<String, Tally>()
            val attachmentCounts = LinkedHashMap<String, Tally>()
            var withAttachments = 0
            for (counted in entries) {
                val entry = counted.entry
                counted.database?.let { databaseCounts[it] = (databaseCounts[it] ?: 0) + 1 }
                for (depth in entry.groupPath.indices) {
                    val path = entry.groupPath.subList(0, depth + 1).toList()
                    groupCounts.getOrPut(path) { Tally(path[depth]) }.add(counted.database)
                }
                val seenTags = HashSet<String>()
                for ((spelling, tag) in entry.tags.zip(counted.tags)) {
                    if (seenTags.add(tag)) tagCounts.getOrPut(tag) { Tally(spelling) }.add(counted.database)
                }
                val seenNames = HashSet<String>()
                for ((attachment, name) in entry.attachments.zip(counted.attachments)) {
                    if (seenNames.add(name)) attachmentCounts.getOrPut(name) { Tally(attachment.name) }.add(counted.database)
                }
                if (entry.attachments.isNotEmpty()) withAttachments++
            }
            val order = databaseCounts.keys.toList()
            fun perDatabase(tally: Tally) = order.mapNotNull { name -> tally.byDatabase[name]?.let { DatabaseCount(name, it) } }
            val byName = Comparator<Tally> { a, b -> sorter.compare(a.name, b.name) }
            return SearchFacets(
                groups = groupCounts.map { (path, tally) -> GroupFacet(path, tally.count, perDatabase(tally)) }
                    .sortedWith { a, b -> treeOrder(a.path, b.path, sorter) },
                tags = tagCounts.values.sortedWith(byName).map { TagFacet(it.name, it.count, perDatabase(it)) },
                attachments = attachmentCounts.values.sortedWith(byName).map { AttachmentFacet(it.name, it.count, perDatabase(it)) },
                entriesWithAttachments = withAttachments,
                databases = databaseCounts.map { (name, count) -> DatabaseFacet(name, count) },
            )
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
