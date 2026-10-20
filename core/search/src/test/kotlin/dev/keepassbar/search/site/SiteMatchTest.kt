package dev.keepassbar.search.site

import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.domain.vault.Entry
import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The Mac `SiteMatchTests` expectations, and the plain-http rule of the Android matcher. */
class SiteMatchTest {
    private fun domain(host: String) = PublicSuffixList.registrableDomain(host)

    // PublicSuffixList

    @Test
    fun registrableDomainOfOrdinaryAndMultiLevelSuffixes() {
        assertEquals("gosuslugi.ru", domain("esia.gosuslugi.ru"))
        assertEquals("gosuslugi.ru", domain("gosuslugi.ru"))
        assertEquals("example.co.uk", domain("a.b.example.co.uk"))
        assertEquals("example.com.ru", domain("shop.example.com.ru"))
        assertEquals("c24.de", domain("login.c24.de"))
    }

    @Test
    fun privateSuffixesMakeEveryUserPageItsOwnSite() {
        assertEquals("me.github.io", domain("me.github.io"))
        assertEquals("me.github.io", domain("a.me.github.io"))
        assertNull(domain("github.io"))
    }

    @Test
    fun wildcardAndExceptionRules() {
        // *.ck and !www.ck
        assertNull(domain("foo.ck"), "foo.ck is itself a suffix")
        assertEquals("bar.foo.ck", domain("bar.foo.ck"))
        assertEquals("www.ck", domain("www.ck"))
    }

    @Test
    fun noRegistrableDomainForSuffixesSingleLabelsAndIpAddresses() {
        assertNull(domain("co.uk"))
        assertNull(domain("ru"))
        assertNull(domain("localhost"))
        assertNull(domain("192.168.1.10"))
        assertNull(domain("::1"))
        assertNull(domain("a..b.com"))
    }

    @Test
    fun anUnknownTldFallsBackToTheImplicitStarRule() {
        assertEquals("example.internal-tld", domain("x.example.internal-tld"))
    }

    // SiteAddress

    @Test
    fun hostAndPortFromFullUrls() {
        val a = SiteAddress.parse("https://ESIA.Gosuslugi.ru/login?x=1#y")
        assertEquals("esia.gosuslugi.ru", a?.host)
        assertNull(a?.port)
        assertEquals(8080, SiteAddress.parse("http://example.com:8080/a")?.port)
        assertEquals("example.com", SiteAddress.parse("https://user:pw@example.com/")?.host)
        assertEquals("example.com", SiteAddress.parse("https://example.com./")?.host)
        assertEquals("example.com", SiteAddress.parse("  https://example.com  ")?.host)
        assertEquals("::1", SiteAddress.parse("https://[::1]:8443/")?.host)
        assertEquals(8443, SiteAddress.parse("https://[::1]:8443/")?.port)
    }

    @Test
    fun defaultPortsAreTheSameAsNoPort() {
        assertNull(SiteAddress.parse("https://example.com:443/")?.port)
        assertNull(SiteAddress.parse("http://example.com:80/")?.port)
        assertEquals(443, SiteAddress.parse("http://example.com:443/")?.port)
    }

    @Test
    fun schemeLessHostsCountPlainWordsAndOtherSchemesDoNot() {
        assertEquals("github.com", SiteAddress.parse("github.com/login")?.host)
        assertEquals("github.com", SiteAddress.parse("github.com")?.host)
        for (text in listOf(
            "PIN", "", "my bank.com", "ssh://host.example.com", "android://com.example.app", "chrome://settings",
            "file:///etc/hosts", "https://example.com:99999/", "https://example.com:abc/",
        )) {
            assertNull(SiteAddress.parse(text), text)
        }
    }

    @Test
    fun wwwIsIgnoredForTheExactHost() {
        assertEquals("example.com", SiteAddress.parse("https://www.example.com")?.hostKey)
        assertEquals("wwwexample.com", SiteAddress.parse("https://wwwexample.com")?.hostKey)
    }

    @Test
    fun onlyAnHttpAddressIsInsecure() {
        assertEquals(false, SiteAddress.parse("http://example.com")?.isSecure)
        assertEquals(true, SiteAddress.parse("https://example.com")?.isSecure)
        assertEquals(true, SiteAddress.parse("example.com")?.isSecure)
    }

    // SiteMatcher

    private val entries = listOf(
        Entry(id = "esia", title = "Госуслуги", username = "124-383-099 49", url = "https://esia.gosuslugi.ru/"),
        Entry(id = "gos", title = "gosuslugi.ru", url = "https://www.gosuslugi.ru"),
        Entry(id = "lk", title = "ЛК", url = "lk.gosuslugi.ru/profile"),
        Entry(id = "other", title = "Other", url = "https://notgosuslugi.ru"),
        Entry(id = "nourl", title = "No URL"),
        Entry(
            id = "extra",
            title = "Extra URLs",
            url = "https://example.org",
            customFields = listOf(
                CustomField.plain("KP2A_URL", "https://esia.gosuslugi.ru/x"),
                CustomField.plain("KP2A_URL_1", "https://ya.ru"),
            ),
        ),
        Entry(id = "notes", title = "Notes field", customFields = listOf(CustomField.plain("Website", "https://esia.gosuslugi.ru"))),
        Entry(id = "port", title = "Dev", url = "https://esia.gosuslugi.ru:8443"),
    )
    private val matcher = SiteMatcher(entries, Collator.getInstance(Locale.ENGLISH))

    private fun ids(page: String) = matcher.suggestions(page).map { it.entry.id }

    @Test
    fun exactHostFirstThenTheSameRegistrableDomainEachByTitle() {
        val s = matcher.suggestions("https://esia.gosuslugi.ru/login/")
        assertEquals(listOf("extra", "esia", "port", "gos", "lk"), s.map { it.entry.id })
        assertEquals(
            listOf(SiteMatch.EXACT_HOST, SiteMatch.EXACT_HOST, SiteMatch.SAME_DOMAIN, SiteMatch.SAME_DOMAIN, SiteMatch.SAME_DOMAIN),
            s.map { it.match },
        )
    }

    @Test
    fun wwwCountsAsTheSameHost() {
        val first = matcher.suggestions("https://gosuslugi.ru/").first()
        assertEquals("gos", first.entry.id)
        assertEquals(SiteMatch.EXACT_HOST, first.match)
    }

    @Test
    fun aDifferentPortIsOnlyADomainMatch() {
        val s = matcher.suggestions("https://esia.gosuslugi.ru:8443/")
        assertEquals("port", s.first().entry.id)
        assertEquals(SiteMatch.EXACT_HOST, s.first().match)
        assertEquals(SiteMatch.SAME_DOMAIN, s.first { it.entry.id == "esia" }.match)
    }

    @Test
    fun aLookAlikeDomainNeverMatches() {
        assertFalse("other" in ids("https://esia.gosuslugi.ru"))
        assertEquals(listOf("other"), ids("https://notgosuslugi.ru"))
        assertEquals(emptyList(), ids("https://gosuslugi.ru.evil.com"))
    }

    @Test
    fun additionalUrlsCountOtherCustomFieldsDoNot() {
        assertEquals(listOf("extra"), ids("https://ya.ru"))
        assertFalse("notes" in ids("https://esia.gosuslugi.ru"))
    }

    @Test
    fun anEntryCountsOnceWithItsBestMatch() {
        assertEquals(1, matcher.suggestions("https://esia.gosuslugi.ru").count { it.entry.id == "extra" })
    }

    @Test
    fun pagesThatAreNotWebSitesHaveNoSuggestions() {
        assertEquals(emptyList(), ids("chrome://newtab"))
        assertEquals(emptyList(), ids("about:blank"))
        assertEquals(emptyList(), ids(""))
    }

    @Test
    fun ipAddressesAndLocalhostMatchExactlyOnly() {
        val m = SiteMatcher(
            listOf(
                Entry(id = "r", title = "Router", url = "http://192.168.1.1"),
                Entry(id = "r2", title = "Other router", url = "http://192.168.1.2"),
                Entry(id = "l", title = "Local", url = "http://localhost:3000"),
            ),
        )
        assertEquals(listOf("r"), m.suggestions("http://192.168.1.1/admin").map { it.entry.id })
        assertEquals(listOf("l"), m.suggestions("http://localhost:3000/").map { it.entry.id })
        assertEquals(emptyList(), m.suggestions("http://localhost:4000/").map { it.entry.id })
    }

    @Test
    fun aPageWithoutAKnownPortMatchesTheHostOnAnyPort() {
        val page = SiteAddress.parse("https://esia.gosuslugi.ru")!!
        val s = matcher.suggestions(page, anyPort = true)
        assertEquals(SiteMatch.EXACT_HOST, s.first { it.entry.id == "port" }.match)
        assertEquals(SiteMatch.EXACT_HOST, s.first { it.entry.id == "esia" }.match)
        assertEquals(SiteMatch.SAME_DOMAIN, matcher.suggestions(page).first { it.entry.id == "port" }.match)
    }

    @Test
    fun aPlainHttpPageGetsOnlyAddressesWrittenWithHttp() {
        val m = SiteMatcher(
            listOf(
                Entry(id = "secure", title = "Bank", url = "https://bank.de"),
                Entry(id = "bare", title = "Bank, no scheme", url = "bank.de/login"),
                Entry(id = "plain", title = "Bank over http", url = "http://bank.de"),
                Entry(
                    id = "both",
                    title = "Both",
                    url = "https://bank.de",
                    customFields = listOf(CustomField.plain("KP2A_URL", "http://www.bank.de")),
                ),
            ),
            Collator.getInstance(Locale.ENGLISH),
        )
        assertEquals(listOf("plain", "both"), m.suggestions("http://bank.de/").map { it.entry.id })
        assertEquals(listOf("secure", "bare", "plain", "both"), m.suggestions("https://bank.de/").map { it.entry.id })
    }
}
