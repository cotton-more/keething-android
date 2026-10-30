package dev.keething.search

/**
 * A name the last word typed may narrow the search to (Mac `Narrowing`; keething-spec search.narrowing): a database, a
 * group or a tag, with the number of entries its filter would keep.
 */
public data class Narrowing(
    /** [FilterKind.DATABASE], [FilterKind.GROUP] or [FilterKind.TAG]. */
    val kind: FilterKind,
    /** As spelled (a database's whole label), what goes into the query. */
    val name: String,
    val count: Int,
    /** The [count] per database, in the index's order of databases; empty for entries indexed without databases. */
    val databases: List<DatabaseCount> = emptyList(),
)

/**
 * The databases, groups and tags whose name the query's last word matches well — its start, or its initials as the
 * search's (every letter a word's start, the first the name's) — never notes or values, nor a loose fuzzy match.
 * Counted over the entries the rest of the query keeps (its filters and its other words), each as its filter would keep
 * them (`@Bank` keeps Banking too; a name trimmed as the filter trims it): the count is what the list shows after
 * applying it. A name that would keep none or all of them is not offered (it would empty the list, or narrow nothing:
 * so no database with one, nor the filter already typed), nor one with a `"` (its filter cannot be written back), and
 * no database while a `!` filter is typed. Databases first, then groups, then tags; in each, starts before initials,
 * then by count, then by name. At most [limit], each kind's best before a second of another. A word of one letter, or a
 * filter last, offers nothing.
 */
public fun EntrySearch.narrowing(index: SearchIndex, query: String, limit: Int = 4): List<Narrowing> {
    val last = QueryTokenizer.tokens(query).lastOrNull() ?: return emptyList()
    val word = (last.content as? Token.Content.Term)?.text ?: return emptyList()
    val term = SearchNormalizer.normalize(word)
    if (term.codePointCount(0, term.length) < 2) return emptyList() // letters, not UTF-16 units
    val parsed = SearchQuery(query.removeRange(last.start, last.end)).resolving(index.databaseLabels)
    val passing = when {
        parsed.terms.isNotEmpty() -> index.items.filter { parsed.match(it) != null }
        parsed.hasFilters -> index.items.filter(parsed::passesFilters)
        else -> index.items
    }
    if (passing.isEmpty()) return emptyList()

    // Each folded name once, with its first spelling, in the index's order.
    val groups = LinkedHashMap<String, String>()
    val tags = LinkedHashMap<String, String>()
    val databases = LinkedHashMap<String, String>()
    for (item in passing) {
        for ((name, folded) in item.entry.groupPath.zip(item.groups)) groups.putIfAbsent(folded, name)
        for ((name, folded) in item.entry.tags.zip(item.tags)) tags.putIfAbsent(folded, name)
        val database = item.database
        val folded = item.normalizedDatabase
        if (database != null && folded != null) databases.putIfAbsent(folded, database)
    }
    val databaseOrder = databases.values.toList()
    if (parsed.databaseFilters.isNotEmpty()) databases.clear()

    val counting = NarrowingCount(passing, term, databaseOrder, index)
    val kinds = listOf(
        counting.candidates(databases, FilterKind.DATABASE, starts = false) { listOfNotNull(it.normalizedDatabase) },
        counting.candidates(groups, FilterKind.GROUP, starts = true) { it.groups },
        counting.candidates(tags, FilterKind.TAG, starts = true) { it.tags },
    )
    // Each kind's best first, then the rest in order; shown by kind and rank.
    val taken = IntArray(kinds.size)
    var room = limit
    for (kind in kinds.indices) if (room > 0 && kinds[kind].isNotEmpty()) { taken[kind] = 1; room-- }
    for (kind in kinds.indices) {
        val more = minOf(room, kinds[kind].size - taken[kind])
        taken[kind] += more
        room -= more
    }
    return kinds.indices.flatMap { kinds[it].take(taken[it]) }
}

/** The names of one narrowing, counted over its [passing] entries. */
private class NarrowingCount(
    private val passing: List<SearchIndex.Item>,
    private val term: String,
    private val databaseOrder: List<String>,
    private val index: SearchIndex,
) {
    private class Matched(val folded: String, val name: String, val byInitials: Boolean)

    /**
     * The [names] (folded → spelling) matching the word, each with the number of passing entries its filter keeps:
     * those with a name it starts ([starts]: a group's or tag's prefix; a database's whole label is that one only). One
     * pass over the entries, through each distinct name's candidates.
     */
    fun candidates(names: Map<String, String>, kind: FilterKind, starts: Boolean, itemNames: (SearchIndex.Item) -> List<String>): List<Narrowing> {
        val seen = HashSet<String>()
        val matched = names.mapNotNull { (foldedName, spelling) ->
            // As the filter will read it: trimmed (a group "Bank " is `@Bank`, which keeps Bankkonto too).
            val name = if (starts) spelling.trim() else spelling
            val folded = if (starts) foldedName.trim() else foldedName
            when {
                name.isEmpty() || '"' in name || !seen.add(folded) -> null
                folded.startsWith(term) -> Matched(folded, name, byInitials = false)
                initials(term, name) -> Matched(folded, name, byInitials = true)
                else -> null
            }
        }
        if (matched.isEmpty()) return emptyList()
        val keptBy = HashMap<String, List<Int>>()
        for (folded in names.keys) {
            val by = matched.indices.filter { if (starts) folded.startsWith(matched[it].folded) else folded == matched[it].folded }
            if (by.isNotEmpty()) keptBy[folded] = by
        }
        val counts = IntArray(matched.size)
        val counted = IntArray(matched.size) { -1 }
        val byDatabase = Array(matched.size) { HashMap<String, Int>() }
        for ((n, item) in passing.withIndex()) {
            for (name in itemNames(item)) {
                for (c in keptBy[name].orEmpty()) {
                    if (counted[c] == n) continue
                    counted[c] = n
                    counts[c]++
                    item.database?.let { byDatabase[c][it] = (byDatabase[c][it] ?: 0) + 1 }
                }
            }
        }
        class Candidate(val narrowing: Narrowing, val byInitials: Boolean)
        return matched.indices
            .filter { counts[it] > 0 && counts[it] < passing.size }
            .map { i ->
                val perDatabase = databaseOrder.mapNotNull { name -> byDatabase[i][name]?.let { DatabaseCount(name, it) } }
                Candidate(Narrowing(kind, matched[i].name, counts[i], perDatabase), matched[i].byInitials)
            }
            .sortedWith(
                compareBy<Candidate> { it.byInitials }
                    .thenByDescending { it.narrowing.count }
                    .thenComparator { a, b -> index.sorter.compare(a.narrowing.name, b.narrowing.name) },
            )
            .map { it.narrowing }
    }

    companion object {
        /**
         * Every letter of [term] (folded, two or more) starts a word or a camelCase hump of [name], in order, the first
         * at the name's start: `fs` → Family Shared, `gh` → GitHub (the search's initials).
         */
        fun initials(term: String, name: String): Boolean {
            val text = FuzzyText(name)
            if (term.length < 2 || text.characters.isEmpty() || text.characters[0] != term[0] || !text.boundaries[0]) return false
            var next = 1
            for (i in 1 until text.characters.size) {
                if (next < term.length && text.boundaries[i] && text.characters[i] == term[next]) next++
            }
            return next == term.length
        }
    }
}
