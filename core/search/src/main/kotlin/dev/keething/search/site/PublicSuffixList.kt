package dev.keething.search.site

/**
 * The registrable domain ("eTLD+1") of a host by the Public Suffix List rules (Mac `PublicSuffixList`):
 * `esia.gosuslugi.ru` → `gosuslugi.ru`, `a.b.example.co.uk` → `example.co.uk`, `me.github.io` →
 * `me.github.io` (github.io is a suffix, so every user page is its own site). The rules are a resource of
 * this module (`public_suffix_list.txt`, from `tools/update-public-suffix-list.sh`), read on first use.
 */
internal object PublicSuffixList {
    private const val RESOURCE = "/dev/keething/search/site/public_suffix_list.txt"

    private class Rules(
        val exact: Set<String>,
        /** `*.ck` is stored as `ck`. */
        val wildcard: Set<String>,
        /** `!www.ck` is stored as `www.ck`. */
        val exception: Set<String>,
    )

    private val rules: Rules by lazy {
        val exact = HashSet<String>(16_384)
        val wildcard = HashSet<String>()
        val exception = HashSet<String>()
        // An absolute path: R8 may move this class to another package in a release build.
        val stream = checkNotNull(PublicSuffixList::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE is missing" }
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("//")) continue
                val rule = line.lowercase()
                when {
                    rule.startsWith("!") -> exception += rule.substring(1)
                    rule.startsWith("*.") -> wildcard += rule.substring(2)
                    else -> exact += rule
                }
            }
        }
        Rules(exact, wildcard, exception)
    }

    /**
     * The host must already be lowercased without a trailing dot. Null for a host that is itself a public
     * suffix (`co.uk`), a single label (`localhost`) or an IP address.
     */
    fun registrableDomain(host: String): String? {
        if (isIpAddress(host)) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        val suffixCount = publicSuffixLabelCount(labels)
        if (labels.size <= suffixCount) return null
        return labels.takeLast(suffixCount + 1).joinToString(".")
    }

    /**
     * Number of trailing labels that form the public suffix. The PSL algorithm: an exception rule wins (its
     * suffix is the rule minus its first label), otherwise the longest matching rule, otherwise `*`.
     */
    private fun publicSuffixLabelCount(labels: List<String>): Int {
        var longest = 1 // the implicit "*" rule
        for (count in 1..labels.size) {
            val candidate = labels.takeLast(count).joinToString(".")
            if (candidate in rules.exception) return count - 1
            if (candidate in rules.exact) longest = maxOf(longest, count)
            if (count < labels.size && candidate in rules.wildcard) longest = maxOf(longest, count + 1)
        }
        return longest
    }

    private fun isIpAddress(host: String): Boolean {
        if (':' in host) return true // IPv6 (brackets already removed)
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) }
    }
}
