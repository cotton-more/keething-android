package dev.keething.search

/** How an entry matched a query. */
internal sealed interface SearchMatch {
    /** Every term occurs as plain text. */
    data object Exact : SearchMatch

    /** At least one term matched only fuzzily; a higher score is a better alignment. */
    data class Fuzzy(val score: Int) : SearchMatch
}

/** The ranking classes: title starts with the query, title contains it, anything else exact; fuzzy last. */
internal enum class SearchRank {
    TITLE_PREFIX,
    TITLE_CONTAINS,
    OTHER,
    FUZZY,
    ;

    companion object {
        fun of(normalizedTitle: String, query: SearchQuery): SearchRank = when {
            normalizedTitle.startsWith(query.phrase) -> TITLE_PREFIX
            query.phrase in normalizedTitle -> TITLE_CONTAINS
            else -> OTHER
        }
    }
}

/**
 * A parsed, normalised query (Mac `SearchQuery`): whitespace-separated terms that must all match an entry,
 * and `@group` / `#tag` / `+attachment` / `!database` filters that narrow the entries first.
 */
internal class SearchQuery(raw: String) {
    val terms: List<String>

    /** The whole trimmed query without its filters, normalised (for ranking). */
    val phrase: String

    /** Group and tag prefixes; empty ones (a bare `@` or `#`) are dropped. */
    val groupFilters: List<String>
    val tagFilters: List<String>

    /** Attachment-name prefixes; an empty one (a bare `+`) asks for any attachment. */
    val attachmentFilters: List<String>

    /** Prefixes of the database's label; a bare `!` is dropped. */
    val databaseFilters: List<String>

    /**
     * The database filters that are a whole label of the index ([resolving]): those keep that database only, so a
     * label chosen from the suggestions or the card's chip does not also keep one it starts ("Work", "Workshop").
     */
    private var exactDatabases: Set<String> = emptySet()

    init {
        val terms = mutableListOf<String>()
        val groups = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val attachments = mutableListOf<String>()
        val databases = mutableListOf<String>()
        val tokens = QueryTokenizer.tokens(raw)
        for (token in tokens) {
            when (val content = token.content) {
                is Token.Content.Term -> terms += SearchNormalizer.normalize(content.text)
                is Token.Content.Filter -> {
                    val value = SearchNormalizer.normalize(content.value.trim())
                    when {
                        content.kind == FilterKind.ATTACHMENT -> attachments += value
                        value.isEmpty() -> {}
                        content.kind == FilterKind.GROUP -> groups += value
                        content.kind == FilterKind.TAG -> tags += value
                        else -> databases += value
                    }
                }
            }
        }
        this.terms = terms
        groupFilters = groups
        tagFilters = tags
        attachmentFilters = attachments
        databaseFilters = databases
        val hasFilters = tokens.any { it.content is Token.Content.Filter }
        phrase = if (hasFilters) terms.joinToString(" ") else SearchNormalizer.normalize(raw.trim())
    }

    /** Nothing to search for: only blanks and bare signs (`@`, `#`, `!`), which filter nothing. */
    val isEmpty: Boolean
        get() = terms.isEmpty() && !hasFilters

    val hasFilters: Boolean
        get() = groupFilters.isNotEmpty() || tagFilters.isNotEmpty() || attachmentFilters.isNotEmpty() || databaseFilters.isNotEmpty()

    /** The query against the index's database labels (folded). */
    fun resolving(labels: Set<String>): SearchQuery = apply { exactDatabases = databaseFilters.filterTo(HashSet()) { it in labels } }

    /** Whether a database of this [label] (folded) passes every database filter: its start, or it when it is a whole label. */
    fun passesDatabase(label: String): Boolean =
        databaseFilters.all { f -> if (f in exactDatabases) label == f else label.startsWith(f) }

    /**
     * Every group filter must start one of the entry's group names (any level, so a group includes its
     * subgroups), every tag filter one of its tags, every attachment filter one of its file names, and every
     * database filter its database's label — or be that label, when it is a whole one (an entry indexed without
     * one passes none).
     */
    fun passesFilters(item: SearchIndex.Item): Boolean =
        groupFilters.all { f -> item.groups.any { it.startsWith(f) } } &&
            tagFilters.all { f -> item.tags.any { it.startsWith(f) } } &&
            attachmentFilters.all { f -> item.attachments.any { it.startsWith(f) } } &&
            (databaseFilters.isEmpty() || item.normalizedDatabase?.let(::passesDatabase) == true)

    /** Each term must occur exactly in the entry's searchable text, or fuzzily in one of its short fields. */
    fun match(item: SearchIndex.Item): SearchMatch? {
        if (!passesFilters(item)) return null
        var allExact = true
        var fuzzyTotal = 0
        for (term in terms) {
            if (term in item.haystack) {
                fuzzyTotal += 60 + 10 * term.length
                continue
            }
            allExact = false
            val best = item.fuzzyFields.mapNotNull { FuzzyMatcher.score(term, it) }.maxOrNull() ?: return null
            fuzzyTotal += best
        }
        return if (allExact) SearchMatch.Exact else SearchMatch.Fuzzy(fuzzyTotal)
    }
}
