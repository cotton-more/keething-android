package dev.keething.search

import dev.keething.domain.vault.Entry
import java.text.CollationKey
import java.text.Collator

/**
 * Entries prepared for searching (Mac `SearchIndex`): the folded searchable text, the fuzzy fields and the
 * title's sort key are computed once per vault, not on every keystroke.
 */
public class SearchIndex internal constructor(
    internal val items: List<Item>,
    /** Sorts names (the phone's language), secondary strength. */
    internal val sorter: Collator,
    /**
     * The databases' labels, folded: a `!` filter equal to one of them is that database only — also one without
     * entries here (an empty bin), so that `!Work` does not fall back to keeping Workshop's.
     */
    internal val databaseLabels: Set<String>,
) {
    internal class Item(
        val entry: Entry,
        /** Folded text of everything searchable (secrets excluded), for exact matching. */
        val haystack: String,
        val normalizedTitle: String,
        /** The short fields fuzzy matching may look at, each separately (never notes or custom values). */
        val fuzzyFields: List<FuzzyText>,
        val groups: List<String>,
        val tags: List<String>,
        val attachments: List<String>,
        val titleKey: CollationKey,
        /**
         * The label of the entry's database (as given, for the facets) and folded, for `!database` filters; null: the
         * entries were indexed without databases (one vault).
         */
        val database: String? = null,
        val normalizedDatabase: String? = null,
    )

    internal companion object {
        fun item(entry: Entry, collator: Collator, database: String? = null, normalizedDatabase: String? = null): Item {
            val host = entry.urlHost ?: entry.url
            val fields = listOf(entry.title, entry.username, host) + entry.groupPath + entry.tags
            return Item(
                entry = entry,
                haystack = SearchNormalizer.normalize(SearchableText.of(entry)),
                normalizedTitle = SearchNormalizer.normalize(entry.title),
                fuzzyFields = fields.filter { it.isNotEmpty() }.map(::FuzzyText),
                groups = entry.groupPath.map(SearchNormalizer::normalize),
                tags = entry.tags.map(SearchNormalizer::normalize),
                attachments = entry.attachments.map { SearchNormalizer.normalize(it.name) },
                titleKey = collator.getCollationKey(entry.displayTitle),
                database = database,
                normalizedDatabase = normalizedDatabase,
            )
        }
    }
}

/**
 * Filters and ranks entries for a query (Mac `EntrySearch`): terms (exact, then fuzzy), `@group`, `#tag`,
 * `+attachment` and `!database` filters; case, diacritics and `ё` folded; secrets never searched.
 */
public object EntrySearch {
    /**
     * Prepares entries for searching; build it once per loaded vault. [collator] sorts titles and names (the
     * phone's language). [databases]: each entry's database label, one per entry, for the `!database` filter
     * (keething-spec search.database-filter); null: one vault, no labels. [labels]: every open database's label, those
     * without entries here too (a whole label keeps that database only, AC1).
     */
    public fun index(
        entries: List<Entry>,
        collator: Collator = defaultCollator(),
        databases: List<String>? = null,
        labels: Collection<String> = emptyList(),
    ): SearchIndex {
        require(databases == null || databases.size == entries.size) { "one database label per entry" }
        val copy = (collator.clone() as Collator).apply { strength = Collator.SECONDARY }
        val folded = HashMap<String, String>() // a few databases, thousands of entries
        val items = entries.mapIndexed { i, entry ->
            val database = databases?.get(i)
            SearchIndex.item(entry, copy, database, database?.let { folded.getOrPut(it) { SearchNormalizer.normalize(it) } })
        }
        return SearchIndex(items, copy, folded.values.toSet() + labels.map(SearchNormalizer::normalize))
    }

    /** Nothing to search for: only blanks and bare signs (`@`, `#`, `!`), which filter nothing (search.database-filter AC2). */
    public fun isEmpty(query: String): Boolean = SearchQuery(query).isEmpty

    /**
     * The query has `!database` filters and a database of [label] passes them ([labels]: the open databases'): the
     * search is in that database (where a new entry starts, keething-spec databases.one-list AC4).
     */
    public fun names(label: String, query: String, labels: List<String>): Boolean {
        val parsed = SearchQuery(query).resolving(labels.mapTo(HashSet(), SearchNormalizer::normalize))
        return parsed.databaseFilters.isNotEmpty() && parsed.passesDatabase(SearchNormalizer.normalize(label))
    }

    /**
     * The groups, tags, attachments and databases of the entries that pass the [filters]' filters, its terms aside:
     * what the suggestions after a sign offer (keething-spec search.suggestions AC6). A name that no such entry has is
     * not among them, so nothing is offered that would leave the list empty.
     */
    public fun facets(index: SearchIndex, filters: String): SearchFacets {
        val parsed = SearchQuery(filters).resolving(index.databaseLabels)
        return SearchFacets.of(if (parsed.hasFilters) index.items.filter(parsed::passesFilters) else index.items, index.sorter)
    }

    /**
     * The entries matching [query], best first. A query without terms (empty, or only filters) gives the
     * entries that pass the filters, by title.
     */
    public fun filter(index: SearchIndex, query: String): List<Entry> {
        val parsed = SearchQuery(query).resolving(index.databaseLabels)
        if (parsed.terms.isEmpty()) {
            return index.items.filter(parsed::passesFilters).sortedWith(byTitle).map { it.entry }
        }
        class Hit(val item: SearchIndex.Item, val rank: SearchRank, val score: Int, val position: Int)
        val hits = index.items.mapIndexedNotNull { position, item ->
            when (val match = parsed.match(item)) {
                null -> null
                SearchMatch.Exact -> Hit(item, SearchRank.of(item.normalizedTitle, parsed), 0, position)
                is SearchMatch.Fuzzy -> Hit(item, SearchRank.FUZZY, match.score, position)
            }
        }
        return hits.sortedWith(
            compareBy<Hit> { it.rank }
                .thenByDescending { it.score } // fuzzy: better alignment first
                .thenComparator { a, b -> byTitle.compare(a.item, b.item) }
                .thenBy { it.position },
        ).map { it.item.entry }
    }

    /**
     * A query starting with `-` ("minus an entry") searches the recycle bin with the rest: `-` alone lists
     * it, `-shop` or `- @Work shop` search it. Null for any other query.
     */
    public fun recycleBinQuery(query: String): String? {
        val trimmed = query.trimStart()
        return if (trimmed.startsWith("-")) trimmed.substring(1) else null
    }

    private val byTitle = Comparator<SearchIndex.Item> { a, b -> a.titleKey.compareTo(b.titleKey) }

    private fun defaultCollator(): Collator = Collator.getInstance()
}
