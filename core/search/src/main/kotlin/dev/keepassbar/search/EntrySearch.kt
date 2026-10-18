package dev.keepassbar.search

import dev.keepassbar.domain.vault.Entry
import java.text.CollationKey
import java.text.Collator

/**
 * Entries prepared for searching (Mac `SearchIndex`): the folded searchable text, the fuzzy fields and the
 * title's sort key are computed once per vault, not on every keystroke.
 */
public class SearchIndex internal constructor(internal val items: List<Item>) {
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
    )

    internal companion object {
        fun item(entry: Entry, collator: Collator): Item {
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
            )
        }
    }
}

/**
 * Filters and ranks entries for a query (Mac `EntrySearch`): terms (exact, then fuzzy), `@group`, `#tag` and
 * `+attachment` filters; case, diacritics and `ё` folded; secrets never searched.
 */
public object EntrySearch {
    /**
     * Prepares entries for searching; build it once per loaded vault. [collator] sorts titles (the
     * phone's language); it is used only here.
     */
    public fun index(entries: List<Entry>, collator: Collator = defaultCollator()): SearchIndex {
        val copy = (collator.clone() as Collator).apply { strength = Collator.SECONDARY }
        return SearchIndex(entries.map { SearchIndex.item(it, copy) })
    }

    /**
     * The entries matching [query], best first. A query without terms (empty, or only filters) gives the
     * entries that pass the filters, by title.
     */
    public fun filter(index: SearchIndex, query: String): List<Entry> {
        val parsed = SearchQuery(query)
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
