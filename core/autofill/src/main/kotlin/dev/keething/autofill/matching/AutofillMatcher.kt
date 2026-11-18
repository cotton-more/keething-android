package dev.keething.autofill.matching

import dev.keething.autofill.target.FillTarget
import dev.keething.domain.vault.AndroidAppAddress
import dev.keething.domain.vault.Entry
import dev.keething.search.site.SiteAddress
import dev.keething.search.site.SiteMatch
import dev.keething.search.site.SiteMatcher
import java.text.Collator

/** Why an entry belongs to the target. Ordered: the closest first. */
public enum class AutofillMatch {
    /** The entry names the app: `androidapp://<package>`. */
    APP,

    /** The page's host, or one of the hosts the app is verified for. */
    EXACT_HOST,

    /** Another host of the same registrable domain. */
    SAME_DOMAIN,
}

public data class AutofillCandidate(val entry: Entry, val match: AutofillMatch)

/**
 * The entries offered for a target (SPEC §7). Built once per loaded vault. A web page gets the Mac's site
 * matching; an app gets the entries that name its package (`androidapp://<package>`, as Keepass2Android
 * writes it) and those of the websites it is verified for (Android app links), so one entry for
 * `https://bank.de` also serves the bank's app.
 */
public class AutofillMatcher(entries: List<Entry>, collator: Collator = Collator.getInstance()) {
    private val sites = SiteMatcher(entries, collator)

    private val byPackage: Map<String, List<Entry>> = entries
        .flatMap { entry -> entry.addresses.mapNotNull(AndroidAppAddress::packageOf).distinct().map { it to entry } }
        .groupBy({ it.first }, { it.second })

    private val titleOrder: Comparator<Entry> = run {
        val copy = (collator.clone() as Collator).apply { strength = Collator.SECONDARY }
        Comparator { a, b -> copy.compare(a.sortTitle, b.sortTitle) }
    }

    /**
     * Best match first, then by title; an entry once, with its best match. [appWebsites]: the hosts an app is
     * verified for, as the system lists them (`www.bank.de`, `*.bank.de`); not used for a web page.
     */
    public fun candidates(target: FillTarget, appWebsites: List<String> = emptyList()): List<AutofillCandidate> {
        val found = when (target) {
            is FillTarget.WebPage -> sites.suggestions(target.site, anyPort = !target.portKnown).map { AutofillCandidate(it.entry, it.match.autofill()) }
            is FillTarget.App -> {
                val named = byPackage[target.packageName].orEmpty().map { AutofillCandidate(it, AutofillMatch.APP) }
                val verified = appWebsites.flatMap { host ->
                    val site = SiteAddress.parse("https://" + host.removePrefix("*.")) ?: return@flatMap emptyList()
                    // A wildcard stands for the hosts under the domain, not for the domain's own host.
                    sites.suggestions(site).map { s ->
                        val match = if (host.startsWith("*.")) AutofillMatch.SAME_DOMAIN else s.match.autofill()
                        AutofillCandidate(s.entry, match)
                    }
                }
                named + verified
            }
        }
        return found
            .groupBy { it.entry.id }
            .map { (_, same) -> same.minBy { it.match } }
            .sortedWith(compareBy<AutofillCandidate> { it.match }.thenBy(titleOrder) { it.entry })
    }

    private fun SiteMatch.autofill(): AutofillMatch = when (this) {
        SiteMatch.EXACT_HOST -> AutofillMatch.EXACT_HOST
        SiteMatch.SAME_DOMAIN -> AutofillMatch.SAME_DOMAIN
    }
}
