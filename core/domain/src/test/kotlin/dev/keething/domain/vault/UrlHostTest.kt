package dev.keething.domain.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlHostTest {
    @Test
    fun theHostOfAnAddressWithAScheme() {
        assertEquals("www.example.org", UrlHost.of("https://www.example.org/x"))
        assertEquals("server", UrlHost.of("ssh://user@server:22"))
        assertEquals("host", UrlHost.of("http://host?query#frag"))
        assertEquals("::1", UrlHost.of("http://[::1]:8080/"))
        assertEquals("localhost", UrlHost.of("  http://localhost  "))
    }

    @Test
    fun withoutASchemeOnlySomethingLikeADomain() {
        assertEquals("example.com", UrlHost.of("example.com/login"))
        assertNull(UrlHost.of("PIN"))
        assertNull(UrlHost.of(""))
        assertNull(UrlHost.of("two words.com"))
    }

    @Test
    fun noHostIsNull() {
        assertNull(UrlHost.of("http:///path"))
        assertNull(UrlHost.of("https://exa mple.com"))
    }
}
