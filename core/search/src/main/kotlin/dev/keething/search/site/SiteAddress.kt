package dev.keething.search.site

/**
 * The site part of a web address, for matching entries to a web page (Mac `SiteAddress`). Parsed by hand:
 * entry URLs come in every shape (`https://x.com/a`, `x.com/login`, `HTTPS://X.COM:8443`, values with
 * spaces), and only the host and port matter.
 */
public class SiteAddress private constructor(
    /** Lowercased, no trailing dot, no IPv6 brackets. */
    public val host: String,
    /** Null when absent or the scheme's default (80 for http, 443 for https). */
    public val port: Int?,
    /**
     * Written with `https://`, or without a scheme (`x.com/login`, which is opened as https). A plain
     * `http://` address is not: what is typed into such a page can be read on the way.
     */
    public val isSecure: Boolean,
) {
    /** The host compared for an exact match: a leading `www.` makes no difference. */
    public val hostKey: String get() = host.removePrefix("www.")

    /** `esia.gosuslugi.ru` → `gosuslugi.ru`; null for IP addresses, `localhost` and bare public suffixes. */
    public val registrableDomain: String? by lazy { PublicSuffixList.registrableDomain(host) }

    override fun equals(other: Any?): Boolean =
        other is SiteAddress && host == other.host && port == other.port && isSecure == other.isSecure

    override fun hashCode(): Int = (host.hashCode() * 31 + (port ?: 0)) * 31 + isSecure.hashCode()

    override fun toString(): String = "SiteAddress(${if (isSecure) "https" else "http"}://$host${port?.let { ":$it" }.orEmpty()})" // not user text

    public companion object {
        /**
         * `http`/`https` URLs and scheme-less `host.tld[/…]`; null for anything else (`ssh:`, `android://`,
         * `file:`, plain words).
         */
        public fun parse(text: String): SiteAddress? {
            var rest = text.trim()
            var scheme = "https"
            val schemeEnd = rest.indexOf("://")
            if (schemeEnd >= 0) {
                scheme = rest.substring(0, schemeEnd).lowercase()
                if (scheme != "http" && scheme != "https") return null
                rest = rest.substring(schemeEnd + 3)
            }
            var authority = rest.takeWhile { it !in "/?#" }
            authority = authority.substringAfterLast('@')

            var hostPart = authority
            var port: Int? = null
            if (authority.startsWith("[")) { // [::1]:8080
                val close = authority.indexOf(']')
                if (close < 0) return null
                hostPart = authority.substring(1, close)
                val after = authority.substring(close + 1)
                if (after.startsWith(":")) {
                    port = after.substring(1).toIntOrNull() ?: return null
                } else if (after.isNotEmpty()) {
                    return null
                }
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) {
                    hostPart = authority.substring(0, colon)
                    port = authority.substring(colon + 1).toIntOrNull() ?: return null
                }
            }

            val host = hostPart.lowercase().removeSuffix(".")
            if (host.isEmpty() || host.any(Char::isWhitespace)) return null
            // Without a scheme only something that looks like a domain counts, so notes like "PIN" are not hosts.
            if ("://" !in text && '.' !in host) return null
            if (port != null && port !in 1..65535) return null

            val defaultPort = (port == 80 && scheme == "http") || (port == 443 && scheme == "https")
            return SiteAddress(host, if (defaultPort) null else port, isSecure = scheme == "https")
        }
    }
}
