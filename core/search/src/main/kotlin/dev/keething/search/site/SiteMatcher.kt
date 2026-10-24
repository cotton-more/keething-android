package dev.keething.search.site

import dev.keething.domain.vault.Entry
import java.text.Collator

/** How closely an entry belongs to a web page. Ordered: an exact match ranks first. */
public enum class SiteMatch {
    /** Same host (a leading `www.` aside) and port. */
    EXACT_HOST,

    /** Another host of the same registrable domain, e.g. `login.example.com` for `www.example.com`. */
    SAME_DOMAIN,
}

public data class SiteSuggestion(val entry: Entry, val match: SiteMatch)

/**
 * The entries that belong to a web page (Mac `SiteMatcher`). Built once per loaded vault: every entry's
 * addresses (its URL and its additional URLs, [Entry.addresses]) are parsed once.
 *
 * One rule more than on the Mac, where the page is only shown with a warning: on a plain `http://` page
 * only addresses written with `http://` match, so a password kept for an https site is never offered to be
 * sent unencrypted (a network can show `http://bank.de` to someone who typed `bank.de`).
 */
public class SiteMatcher(entries: List<Entry>, collator: Collator = Collator.getInstance()) {
    private class Indexed(val entry: Entry, val sites: List<SiteAddress>)

    private val indexed: List<Indexed> = entries.mapNotNull { entry ->
        val sites = entry.addresses.mapNotNull(SiteAddress::parse)
        if (sites.isEmpty()) null else Indexed(entry, sites)
    }

    private val titleOrder: Comparator<Entry> = run {
        val copy = (collator.clone() as Collator).apply { strength = Collator.SECONDARY }
        Comparator { a, b -> copy.compare(a.displayTitle, b.displayTitle) }
    }

    /**
     * Entries for the page: exact host matches first, then the rest of the same registrable domain; each
     * group by title. An entry counts once, with its best match. Empty for a page that is not an http(s) site.
     */
    public fun suggestions(pageUrl: String): List<SiteSuggestion> = SiteAddress.parse(pageUrl)?.let(::suggestions).orEmpty()

    /**
     * [anyPort]: the page's port is not known, so a host matches with any port. Android's browsers name a
     * page by its host only (`localhost` for `http://localhost:8000`).
     */
    public fun suggestions(page: SiteAddress, anyPort: Boolean = false): List<SiteSuggestion> {
        val pageDomain = page.registrableDomain
        val result = indexed.mapNotNull { item ->
            val best = item.sites
                .filter { page.isSecure || !it.isSecure }
                .mapNotNull { site ->
                    when {
                        site.hostKey == page.hostKey && (anyPort || site.port == page.port) -> SiteMatch.EXACT_HOST
                        pageDomain != null && site.registrableDomain == pageDomain -> SiteMatch.SAME_DOMAIN
                        else -> null
                    }
                }
                .minOrNull()
            best?.let { SiteSuggestion(item.entry, it) }
        }
        return result.sortedWith(compareBy<SiteSuggestion> { it.match }.thenBy(titleOrder) { it.entry })
    }
}
