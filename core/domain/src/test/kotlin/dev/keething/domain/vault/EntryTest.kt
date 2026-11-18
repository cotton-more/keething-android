package dev.keething.domain.vault

import dev.keething.domain.secret.Secret
import dev.keething.domain.text.english
import dev.keething.domain.totp.TotpConfig
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryTest {
    @Test
    fun displayTitleIsTheTitleElseTheHostElseUntitled() {
        assertEquals("GitHub", Entry("e", title = "GitHub", url = "https://github.com").displayTitle.english())
        assertEquals("www.example.org", Entry("e", url = "https://www.example.org/path").displayTitle.english())
        assertEquals("(Untitled)", Entry("e").displayTitle.english())
    }

    @Test
    fun sortTitleIsTheTitleElseTheHostElseNothing() {
        assertEquals("GitHub", Entry("e", title = "GitHub", url = "https://github.com").sortTitle)
        assertEquals("www.example.org", Entry("e", url = "https://www.example.org/path").sortTitle)
        assertEquals("", Entry("e").sortTitle)
    }

    @Test
    fun isExpiredOnlyWithTheFlagAndAPastDate() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        val entry = Entry("e")

        assertFalse(entry.isExpired(now), "expires=false never expires")
        assertTrue(entry.copy(expires = true, expiryTime = Instant.parse("2025-01-01T00:00:00Z")).isExpired(now))
        assertFalse(entry.copy(expires = true, expiryTime = Instant.parse("2027-01-01T00:00:00Z")).isExpired(now))
        assertFalse(entry.copy(expires = true, expiryTime = null).isExpired(now), "no date means not expired")
    }

    @Test
    fun addressesAreTheUrlThenTheAdditionalUrlsButNeverProtectedOnes() {
        val entry = Entry(
            "e",
            url = "https://a.example",
            customFields = listOf(
                CustomField.plain("KP2A_URL", "https://b.example"),
                CustomField.protected("KP2A_URL_1", "https://hidden.example"),
                CustomField.plain("kp2a_url_2", "https://c.example"),
                CustomField.plain("KP2A_URL_3", ""),
                CustomField.plain("Website", "https://not-additional.example"),
            ),
        )

        assertEquals(listOf("https://a.example", "https://b.example", "https://c.example"), entry.addresses)
    }

    @Test
    fun wipeOverwritesEverySecretOfTheEntry() {
        val entry = Entry(
            "e",
            password = Secret.of("pw"),
            customFields = listOf(CustomField.protected("PIN", "1234"), CustomField.plain("Org", "Acme")),
            totp = TotpConfig(Secret.of("key")),
        )

        entry.wipe()

        assertTrue(entry.password.isWiped)
        assertTrue((entry.customFields[0].value as FieldValue.Protected).secret.isWiped)
        assertTrue(entry.totp!!.secret.isWiped)
    }

    @Test
    fun aProtectedFieldHasNoPlainText() {
        assertNull(CustomField.protected("PIN", "1234").plainText)
        assertEquals("Acme", CustomField.plain("Org", "Acme").plainText)
    }

    @Test
    fun aNewIdIsSixteenRandomBytesInBase64() {
        val id = Entry.newId()

        assertEquals(16, Base64.getDecoder().decode(id).size)
        assertNotEquals(id, Entry.newId())
    }
}
