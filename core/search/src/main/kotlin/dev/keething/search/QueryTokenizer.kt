package dev.keething.search

/** What a filter in the query narrows by: `@group`, `#tag`, `+attachment` or `!database`. */
public enum class FilterKind(public val sigil: Char) {
    GROUP('@'),
    TAG('#'),
    ATTACHMENT('+'),
    DATABASE('!'),
    ;

    internal companion object {
        fun of(sigil: Char): FilterKind? = entries.firstOrNull { it.sigil == sigil }
    }
}

internal data class Token(val content: Content, val start: Int, val end: Int) {
    sealed interface Content {
        data class Term(val text: String) : Content

        data class Filter(val kind: FilterKind, val value: String) : Content
    }
}

/**
 * Splits a query into whitespace-separated tokens (Mac `QueryTokenizer`). A token starting with `@`, `#` or `!`
 * is a filter (only at a token's start: `hi!` is a term); its value may be quoted (`@"Family Shared"`) so that a name with spaces stays one token. So is a
 * `+` token, except before a digit: `+49 30…` stays a term (a phone number).
 */
internal object QueryTokenizer {
    fun tokens(raw: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < raw.length) {
            if (raw[i].isWhitespace()) {
                i++
                continue
            }
            val start = i
            val kind = FilterKind.of(raw[i])
            if (kind != null && (kind != FilterKind.ATTACHMENT || !digitFollows(raw, i))) {
                val afterSigil = i + 1
                if (afterSigil < raw.length && raw[afterSigil] == '"') {
                    val valueStart = afterSigil + 1
                    val close = raw.indexOf('"', valueStart).takeIf { it >= 0 }
                    val end = close?.plus(1) ?: raw.length
                    tokens += Token(Token.Content.Filter(kind, raw.substring(valueStart, close ?: raw.length)), start, end)
                    i = end
                    continue
                }
                val end = endOfWord(raw, afterSigil)
                tokens += Token(Token.Content.Filter(kind, raw.substring(afterSigil, end)), start, end)
                i = end
                continue
            }
            val end = endOfWord(raw, i)
            tokens += Token(Token.Content.Term(raw.substring(start, end)), start, end)
            i = end
        }
        return tokens
    }

    private fun endOfWord(raw: String, from: Int): Int {
        var i = from
        while (i < raw.length && !raw[i].isWhitespace()) i++
        return i
    }

    private fun digitFollows(raw: String, i: Int) = i + 1 < raw.length && raw[i + 1].isDigit()
}

/** The filter being typed at the end of the query, for suggestions (`@Wo` while typing `@Work`). */
public data class ActiveFilter(
    val kind: FilterKind,
    /** What has been typed after the sigil (without quotes), not normalised. */
    val partial: String,
    internal val start: Int,
)

public object FilterCompletion {
    /**
     * The filter token at the end of the query: the last token, if it is a filter and nothing but an open
     * quote follows it. `@Work ` (with a trailing space) is finished, not active.
     */
    public fun activeFilter(query: String): ActiveFilter? {
        val last = QueryTokenizer.tokens(query).lastOrNull() ?: return null
        val filter = last.content as? Token.Content.Filter ?: return null
        if (last.end != query.length) return null
        val text = query.substring(last.start, last.end)
        val closedQuote = text.length >= 3 && text[1] == '"' && text.last() == '"'
        return if (closedQuote) null else ActiveFilter(filter.kind, filter.value, last.start)
    }

    /** Replaces the active filter with the chosen name and a trailing space, ready for the next term. */
    public fun complete(query: String, name: String): String {
        val active = activeFilter(query) ?: return query
        return query.substring(0, active.start) + token(active.kind, name) + " "
    }

    /**
     * The query without the filter being typed at its end: what is offered after a sign is counted over the entries
     * that pass the other filters (keething-spec search.suggestions AC6). Nothing active: the query as it is.
     */
    public fun withoutActiveFilter(query: String): String {
        val active = activeFilter(query) ?: return query
        return query.substring(0, active.start)
    }

    /**
     * Applies a narrowing (keething-spec search.narrowing AC5): the query's last word (and the blanks after it) becomes
     * the filter, quoted when needed, and a space, ready for the search to go on inside it (`github fin` →
     * `github @Finanzen `). No word last: unchanged.
     */
    public fun narrow(query: String, kind: FilterKind, name: String): String {
        val last = QueryTokenizer.tokens(query).lastOrNull() ?: return query
        if (last.content !is Token.Content.Term) return query
        return query.substring(0, last.start) + token(kind, name) + " "
    }

    /**
     * What a card's place chip searches, a trailing space ready for a term: its database while two or more are open
     * and its group, `!Work @Mail `; for an entry in the recycle bin the bin, by the group inside it, `- @Mail `, or
     * `- ` at the bin's top (search.recycle-bin).
     */
    public fun place(database: String?, group: String?, inBin: Boolean): String =
        listOfNotNull("-".takeIf { inBin }, database?.let { token(FilterKind.DATABASE, it) }, group?.let { token(FilterKind.GROUP, it) })
            .joinToString(" ") + " "

    /** The text of a filter for a name: quoted when it contains whitespace. */
    public fun token(kind: FilterKind, name: String): String =
        "${kind.sigil}${if (name.any(Char::isWhitespace)) "\"$name\"" else name}"
}
