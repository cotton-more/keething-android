package dev.keepassbar.domain.vault

/** The host part of a written address (Mac `URLHost`). */
public object UrlHost {
    /**
     * `https://www.example.org/x` → `www.example.org`, `example.com/login` → `example.com`,
     * `ssh://user@server:22` → `server`; null for text that is not an address (`PIN`, empty).
     */
    public fun of(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val scheme = trimmed.indexOf("://")
        if (scheme >= 0) return parse(trimmed.substring(scheme + 3))
        // Without a scheme only something that looks like a domain counts, so notes like "PIN" are not hosts.
        return parse(trimmed)?.takeIf { '.' in it }
    }

    private fun parse(rest: String): String? {
        var authority = rest.takeWhile { it !in "/?#" }
        authority = authority.substringAfterLast('@')
        authority = if (authority.startsWith("[") && ']' in authority) {
            authority.substring(1, authority.indexOf(']'))
        } else {
            authority.substringBeforeLast(':')
        }
        return authority.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
    }
}
