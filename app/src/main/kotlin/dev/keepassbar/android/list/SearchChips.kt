package dev.keepassbar.android.list

import dev.keepassbar.search.EntrySearch
import dev.keepassbar.search.FilterCompletion
import dev.keepassbar.search.FilterKind
import dev.keepassbar.search.SearchFacets

/**
 * A chip over the keyboard while the search field has the focus (SPEC §6.2): a filter to start one with
 * ([sigil]: `@`, `#`, `+`, `-` for the bin), or a name for the filter being typed, with its entry count.
 * A tap makes the query [query].
 */
internal data class SearchChip(
    val label: String,
    val query: String,
    val sigil: Char? = null,
    val count: Int? = null,
    val selected: Boolean = false,
)

/**
 * The chips for [query] (Mac `FilterSuggestions`, shown as chips instead of in the list). Typing a filter at the
 * end of the query: the groups (their paths, in tree order), tags or file names it can be, a bare `+` first "Any
 * file"; nothing when none matches. Otherwise the four filters; the bin's toggles the recycle bin search (not in
 * the Codes list, which has no bin).
 */
internal fun searchChips(query: String, facets: SearchFacets, binFacets: SearchFacets, codesOnly: Boolean): List<SearchChip> {
    val inBin = !codesOnly && EntrySearch.recycleBinQuery(query) != null
    val active = FilterCompletion.activeFilter(query)
    if (active != null) {
        val from = if (inBin) binFacets else facets
        val partial = active.partial
        return when (active.kind) {
            FilterKind.GROUP -> from.groups(partial).map { SearchChip(it.path.joinToString(" › "), FilterCompletion.complete(query, it.name), count = it.count) }
            FilterKind.TAG -> from.tags(partial).map { SearchChip(it.name, FilterCompletion.complete(query, it.name), count = it.count) }
            FilterKind.ATTACHMENT -> {
                // A bare `+` lists every entry with files: choosing it leaves `+ `.
                val any = if (partial.isBlank() && from.entriesWithAttachments > 0) {
                    listOf(SearchChip("Any file", FilterCompletion.complete(query, ""), count = from.entriesWithAttachments))
                } else {
                    emptyList()
                }
                any + from.attachments(partial).map { SearchChip(it.name, FilterCompletion.complete(query, it.name), count = it.count) }
            }
        }
    }
    val base = if (query.isEmpty() || query.last().isWhitespace()) query else "$query "
    val filters = listOf(
        SearchChip("group", base + FilterKind.GROUP.sigil, sigil = FilterKind.GROUP.sigil),
        SearchChip("tag", base + FilterKind.TAG.sigil, sigil = FilterKind.TAG.sigil),
        SearchChip("file", base + FilterKind.ATTACHMENT.sigil, sigil = FilterKind.ATTACHMENT.sigil),
    )
    if (codesOnly) return filters
    val rest = query.trim()
    val bin = when {
        inBin -> SearchChip("bin", rest.removePrefix("-").trimStart(), sigil = '-', selected = true)
        rest.isEmpty() -> SearchChip("bin", "-", sigil = '-')
        else -> SearchChip("bin", "- $rest", sigil = '-')
    }
    return filters + bin
}
