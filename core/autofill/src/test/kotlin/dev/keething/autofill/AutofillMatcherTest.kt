package dev.keething.autofill

import dev.keething.autofill.matching.AutofillMatch
import dev.keething.autofill.matching.AutofillMatcher
import dev.keething.autofill.target.FillTarget
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.search.site.SiteAddress
import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AutofillMatcherTest {
    private val entries = listOf(
        Entry(id = "bank", title = "Bank", url = "https://www.bank.de/login"),
        Entry(id = "bank-online", title = "Bank online", url = "https://online.bank.de"),
        Entry(id = "app", title = "Bank app", url = "androidapp://de.bank.app"),
        Entry(id = "extra", title = "Extra", url = "https://example.org", customFields = listOf(CustomField.plain("KP2A_URL", "androidapp://de.bank.app/"))),
        Entry(id = "hidden", title = "Hidden", customFields = listOf(CustomField.protected("KP2A_URL", "androidapp://de.bank.app"))),
        Entry(id = "both", title = "Both", url = "https://bank.de", customFields = listOf(CustomField.plain("KP2A_URL_1", "androidapp://de.bank.app"))),
        Entry(id = "mts", title = "MTS", url = "https://mts.ru"),
        Entry(id = "lk", title = "MTS account", url = "https://lk.mts.ru"),
        Entry(id = "other", title = "Other", url = "androidapp://de.bank.app.evil"),
    )
    private val matcher = AutofillMatcher(entries, Collator.getInstance(Locale.ENGLISH))

    private fun app(pkg: String, websites: List<String> = emptyList()) =
        matcher.candidates(FillTarget.App(pkg), websites).map { it.entry.id to it.match }

    @Test
    fun aWebPageGetsTheSiteMatching() {
        val page = FillTarget.WebPage(SiteAddress.parse("https://www.bank.de/")!!, "com.android.chrome")
        assertEquals(
            listOf("bank" to AutofillMatch.EXACT_HOST, "both" to AutofillMatch.EXACT_HOST, "bank-online" to AutofillMatch.SAME_DOMAIN),
            matcher.candidates(page, appWebsites = listOf("mts.ru")).map { it.entry.id to it.match },
        )
    }

    @Test
    fun aPageWhosePortIsNotKnownMatchesEntriesWithAPort() {
        val dev = AutofillMatcher(listOf(Entry(id = "dev", title = "Dev", url = "http://localhost:8000")))
        val unknown = FillTarget.WebPage(SiteAddress.parse("http://localhost")!!, "org.mozilla.firefox", portKnown = false)
        val known = FillTarget.WebPage(SiteAddress.parse("http://localhost:3000")!!, "org.mozilla.firefox")
        assertEquals(listOf("dev"), dev.candidates(unknown).map { it.entry.id })
        assertEquals(emptyList(), dev.candidates(known).map { it.entry.id })
    }

    @Test
    fun anAppGetsTheEntriesThatNameItsPackage() {
        assertEquals(listOf("app" to AutofillMatch.APP, "both" to AutofillMatch.APP, "extra" to AutofillMatch.APP), app("de.bank.app"))
        assertEquals(listOf("other" to AutofillMatch.APP), app("de.bank.app.evil"))
        assertEquals(emptyList(), app("de.bank"))
    }

    @Test
    fun andTheEntriesOfTheWebsitesItIsVerifiedFor() {
        assertEquals(
            listOf(
                "app" to AutofillMatch.APP,
                "both" to AutofillMatch.APP,
                "extra" to AutofillMatch.APP,
                "bank" to AutofillMatch.EXACT_HOST,
                "bank-online" to AutofillMatch.SAME_DOMAIN,
            ),
            app("de.bank.app", listOf("www.bank.de")),
        )
        assertEquals(listOf("bank" to AutofillMatch.EXACT_HOST, "both" to AutofillMatch.EXACT_HOST, "bank-online" to AutofillMatch.SAME_DOMAIN), app("de.bank.other", listOf("bank.de")))
    }

    @Test
    fun aWildcardWebsiteIsADomainMatch() {
        assertEquals(listOf("mts" to AutofillMatch.SAME_DOMAIN, "lk" to AutofillMatch.SAME_DOMAIN), app("ru.mts.mymts", listOf("*.mts.ru")))
        assertEquals(listOf("mts" to AutofillMatch.EXACT_HOST, "lk" to AutofillMatch.SAME_DOMAIN), app("ru.mts.mymts", listOf("*.mts.ru", "mts.ru")))
    }

    @Test
    fun anAppWithoutWebsitesOrNamesGetsNothing() {
        assertEquals(emptyList(), app("com.unknown"))
        assertEquals(emptyList(), app("com.unknown", listOf("not a host", "")))
    }

    @Test
    fun aProtectedAddressIsNeverRead() {
        assertEquals(false, app("de.bank.app").any { it.first == "hidden" })
    }
}
