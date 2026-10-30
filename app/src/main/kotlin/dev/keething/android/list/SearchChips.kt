package dev.keething.android.list

import dev.keething.android.databases.DatabaseMark
import dev.keething.search.DatabaseCount
import dev.keething.search.EntrySearch
import dev.keething.search.FilterCompletion
import dev.keething.search.FilterKind
import dev.keething.search.Narrowing

/**
 * A chip over the keyboard while the search field has the focus (SPEC §6.2): a filter to start one with
 * ([sigil]: `@`, `#`, `!`, `+`, `-` for the bin), a name for the filter being typed, or a name to narrow the search to,
 * with its entry count. A tap makes the query [query].
 */
internal data class SearchChip(
    val label: String,
    val query: String,
    val sigil: Char? = null,
    val count: Int? = null,
    val selected: Boolean = false,
    /** A database's chip (after `!`, or narrowing to it): its dot, and its label in its colour. */
    val database: DatabaseMark? = null,
    /** Its entries in two or more databases: the count in each, by its dot, in place of [count]. */
    val shares: List<DatabaseShare> = emptyList(),
)

/** How many of a chip's entries are in one database. */
internal data class DatabaseShare(val database: DatabaseMark, val count: Int)

/** The chips over the keyboard; [narrowing]: they narrow the search to what the last word names ("Narrow to"). */
internal data class ChipLine(val chips: List<SearchChip>, val narrowing: Boolean = false)

/**
 * The chips for [query] (Mac `FilterSuggestions` and the narrowing line, shown as chips over the keyboard; keething-spec
 * search.suggestions, search.database-filter AC5, search.narrowing). Typing a filter at the end of the query: the
 * groups (their paths, in tree order), tags, file names (a bare `+` first "Any file") or open databases it can be,
 * counted over the query's other filters; nothing when none matches. Otherwise, when the last word names a database,
 * group or tag well: those to narrow to (not in the bin's search nor the Codes list). Otherwise the filters, `!` only
 * while two or more databases are open; the bin's toggles the recycle bin search (not in the Codes list, which has no
 * bin).
 */
internal fun searchChips(query: String, listing: VaultListing, codesOnly: Boolean): ChipLine {
    val inBin = !codesOnly && EntrySearch.recycleBinQuery(query) != null
    val marks = listing.databaseMarks
    val active = FilterCompletion.activeFilter(query)
    if (active != null) {
        val filters = FilterCompletion.withoutActiveFilter(if (inBin) EntrySearch.recycleBinQuery(query).orEmpty() else query)
        val from = listing.facets(filters, inBin)
        val partial = active.partial
        fun complete(name: String) = FilterCompletion.complete(query, name)
        fun chip(label: String, name: String, count: Int, databases: List<DatabaseCount>) =
            SearchChip(label, complete(name), count = count, shares = shares(databases, marks))
        val chips = when (active.kind) {
            FilterKind.GROUP -> from.groups(partial).map { chip(it.path.joinToString(" › "), it.name, it.count, it.databases) }
            FilterKind.TAG -> from.tags(partial).map { chip(it.name, it.name, it.count, it.databases) }
            FilterKind.ATTACHMENT -> {
                // A bare `+` lists every entry with files: choosing it leaves `+ `.
                val any = if (partial.isBlank() && from.entriesWithAttachments > 0) {
                    listOf(SearchChip("Any file", complete(""), count = from.entriesWithAttachments))
                } else {
                    emptyList()
                }
                any + from.attachments(partial).map { chip(it.name, it.name, it.count, it.databases) }
            }
            // Only while two or more are open: with one, `!` offers nothing.
            FilterKind.DATABASE -> from.databases(partial).mapNotNull { facet ->
                marks[facet.name]?.let { SearchChip(facet.name, complete(facet.name), count = facet.count, database = it) }
            }
        }
        return ChipLine(chips)
    }
    if (!inBin && !codesOnly) {
        val narrowing = listing.narrowing(query)
        if (narrowing.isNotEmpty()) return ChipLine(narrowing.map { narrowChip(query, it, marks) }, narrowing = true)
    }
    val base = if (query.isEmpty() || query.last().isWhitespace()) query else "$query "
    fun filter(label: String, kind: FilterKind) = SearchChip(label, base + kind.sigil, sigil = kind.sigil)
    val filters = listOfNotNull(
        filter("group", FilterKind.GROUP),
        filter("tag", FilterKind.TAG),
        filter("database", FilterKind.DATABASE).takeIf { marks.isNotEmpty() },
        filter("file", FilterKind.ATTACHMENT),
    )
    if (codesOnly) return ChipLine(filters)
    val rest = query.trim()
    val bin = when {
        inBin -> SearchChip("bin", rest.removePrefix("-").trimStart(), sigil = '-', selected = true)
        rest.isEmpty() -> SearchChip("bin", "-", sigil = '-')
        else -> SearchChip("bin", "- $rest", sigil = '-')
    }
    return ChipLine(filters + bin)
}

/** A database's chip shows its dot and label; a group's and a tag's their sign. */
private fun narrowChip(query: String, narrowing: Narrowing, marks: Map<String, DatabaseMark>): SearchChip {
    val database = if (narrowing.kind == FilterKind.DATABASE) marks[narrowing.name] else null
    return SearchChip(
        label = narrowing.name,
        query = FilterCompletion.narrow(query, narrowing.kind, narrowing.name),
        sigil = if (database == null) narrowing.kind.sigil else null,
        count = narrowing.count,
        database = database,
        shares = shares(narrowing.databases, marks),
    )
}

/** The counts per database while the entries are in two or more of the open databases; none otherwise. */
private fun shares(counts: List<DatabaseCount>, marks: Map<String, DatabaseMark>): List<DatabaseShare> =
    if (counts.size < 2) emptyList() else counts.mapNotNull { count -> marks[count.database]?.let { DatabaseShare(it, count.count) } }
