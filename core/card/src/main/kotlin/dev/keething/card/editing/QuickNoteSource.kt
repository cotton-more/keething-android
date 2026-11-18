package dev.keething.card.editing

import dev.keething.search.SearchNormalizer

/**
 * Where a quick note's text came from (SPEC §6.4; keething-spec app.quick-note-source; the Mac's `QuickNoteSource`):
 * the app, as a tag, and the page, as the Website. Both go into the editor, where either can be taken out before Save;
 * nothing of them is kept anywhere else. It says nothing of either when printed.
 */
public data class QuickNoteSource(
    /** The app's name as the launcher shows it; null when unknown or Keething itself. */
    val app: String? = null,
    /** The page's address, already through [address]. */
    val page: String? = null,
) {
    /**
     * [draft] with the app's tag (AC1: spelt as [knownTags] spell it, added once) and the page in the Website with its
     * row (AC2), unless one is there. Nothing for what is not known (AC4).
     */
    public fun applied(draft: EntryDraft, knownTags: List<String>): EntryDraft {
        var applied = draft
        val tag = app?.let { tag(it, knownTags) }
        if (tag != null && draft.tags.none { sameTag(it, tag) }) applied = applied.copy(tags = applied.tags + tag)
        if (page != null && draft.url.isBlank()) applied = applied.copy(url = page, websiteAdded = true)
        return applied
    }

    override fun toString(): String = "QuickNoteSource" // not user text

    public companion object {
        /**
         * The address as it goes into the Website (AC5): `http` or `https` with a host (a letter or digit in it), no
         * white space, control or invisible format character, outer spaces trimmed, the rest as written (a `user:password@`, a token, Chrome's link to the highlight too: the user's to move or protect); null
         * for anything else.
         */
        public fun address(raw: String?): String? {
            val text = raw?.trim() ?: return null
            if (text.any { it.isWhitespace() || it.isISOControl() || isInvisibleFormat(it) }) return null
            val schemeEnd = text.indexOf("://")
            if (schemeEnd < 0) return null
            val scheme = text.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") return null
            val rest = text.substring(schemeEnd + 3)
            val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#')).takeIf { it >= 0 } ?: rest.length)
            val hostAndPort = authority.substringAfterLast('@')
            val ipv6 = hostAndPort.startsWith('[')
            val hostEnd = if (ipv6) hostAndPort.indexOf(']') + 1 else hostAndPort.indexOf(':').takeIf { it >= 0 } ?: hostAndPort.length
            if (ipv6 && hostEnd == 0) return null
            val host = hostAndPort.substring(0, hostEnd)
            val port = hostAndPort.substring(hostEnd)
            val hostValid = if (ipv6) {
                host.length > 2 && ':' in host && host.substring(1, host.length - 1).all { it in HEX || it == ':' || it == '.' }
            } else {
                host.all(::isHostChar) && host.any(Char::isLetterOrDigit)
            }
            val portValid = port.isEmpty() || (port.startsWith(':') && port.drop(1).all { it in '0'..'9' })
            return text.takeIf { hostValid && portValid }
        }

        private const val HEX = "0123456789abcdefABCDEF"

        /**
         * A format character (a direction override that shows a text reversed, a zero-width space, a byte order mark),
         * but the zero-width non-joiner and joiner, which an internationalised name may need.
         */
        private fun isInvisibleFormat(c: Char) = Character.getType(c) == Character.FORMAT.toInt() && c != '\u200C' && c != '\u200D'

        /**
         * A host name's characters: ASCII letters, digits, dots, hyphens, `_` and `%`-escapes, and anything beyond ASCII
         * (an internationalised name as typed, its combining marks too; white space and control characters are refused
         * before).
         */
        private fun isHostChar(c: Char) = c.code >= 0x80 || c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' || c == '%'

        /**
         * The app's tag (AC1): the database's own spelling of a tag of that name (compared as the editor compares
         * tags), else the name; a tag separator in the name becomes a space. Null when nothing is left.
         */
        public fun tag(app: String, known: List<String>): String? {
            val name = app.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            if (name.isEmpty()) return null
            return known.firstOrNull { sameTag(it, name) } ?: name
        }

        private fun sameTag(a: String, b: String) = SearchNormalizer.normalize(a) == SearchNormalizer.normalize(b)
    }
}
