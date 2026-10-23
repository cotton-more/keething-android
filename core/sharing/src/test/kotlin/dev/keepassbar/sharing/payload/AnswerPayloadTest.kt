package dev.keepassbar.sharing.payload

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.totp.TotpConfig
import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.sharing.message.MalformedShareException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnswerPayloadTest {
    private fun full() = SharedEntry(
        title = "Amazon",
        username = "alex.morgan@posteo.de",
        password = Secret.of("k7#Rv9!mQ2\$xLp-Tw"),
        url = "https://amazon.de",
        notes = "Prime is on the family plan\nuntil March",
        customFields = listOf(
            CustomField.plain("KP2A_URL", "https://amazon.com"),
            CustomField.protected("PIN", "4711"),
            CustomField.plain("Valid until", "2027-03-31"),
        ),
        fieldTypes = mapOf("Valid until" to "until", "PIN" to "card.pin"),
        tags = listOf("shopping", "family"),
        expires = Instant.parse("2027-01-01T00:00:00Z"),
        totp = TotpConfig(Secret.adopt(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)), digits = 8, period = 60, algorithm = TotpConfig.Algorithm.SHA256),
    )

    @Test
    fun everythingComesBackAsSent() {
        val sent = AnswerPayload("Anna", listOf(full(), SharedEntry(title = "Wi-Fi")))
        val bytes = sent.encode()

        val read = bytes.useBytes { AnswerPayload.decode(it) }

        assertEquals("Anna", read.from)
        assertEquals(2, read.entries.size)
        val e = read.entries[0]
        val f = full()
        assertEquals(f.title, e.title)
        assertEquals(f.username, e.username)
        assertEquals(f.password, e.password)
        assertEquals(f.url, e.url)
        assertEquals(f.notes, e.notes)
        assertEquals(f.customFields, e.customFields)
        assertEquals(f.fieldTypes, e.fieldTypes)
        assertEquals(f.tags, e.tags)
        assertEquals(f.expires, e.expires)
        assertEquals(f.totp, e.totp)
        val plain = read.entries[1]
        assertEquals("Wi-Fi", plain.title)
        assertTrue(plain.password.isEmpty())
        assertNull(plain.expires)
        assertNull(plain.totp)
    }

    @Test
    fun anAnswerCutAnywhereIsRefused() {
        val bytes = AnswerPayload("Anna", listOf(full())).encode().useBytes { it.copyOf() }

        for (length in 0 until bytes.size) {
            assertFailsWith<MalformedShareException>("cut at $length") { AnswerPayload.decode(bytes.copyOf(length)) }
        }
        assertFailsWith<MalformedShareException> { AnswerPayload.decode(bytes + 0) }
    }

    @Test
    fun noEntriesOrAFieldNamedAsKeePassKeepsItIsRefused() {
        for (payload in listOf(
            AnswerPayload("Anna", emptyList()),
            AnswerPayload("Anna", listOf(SharedEntry("x", customFields = listOf(CustomField.plain("Password", "a"))))),
            AnswerPayload("Anna", listOf(SharedEntry("x", customFields = listOf(CustomField.plain("otp", "a"))))),
            AnswerPayload("Anna", listOf(SharedEntry("x", customFields = listOf(CustomField.plain("A", "1"), CustomField.plain("A", "2"))))),
            AnswerPayload("Anna", listOf(SharedEntry("x", fieldTypes = mapOf("missing" to "url")))),
        )) {
            // The sender reads it back by the receiver's rules before anything is sealed.
            assertFailsWith<IllegalArgumentException> { payload.encode() }
        }
    }

    @Test
    fun whatTheReceiverWouldRefuseIsNotSentAndTheLengthIsPadded() {
        assertFailsWith<IllegalArgumentException> { AnswerPayload("Anna", List(51) { SharedEntry("e$it") }).encode() }
        assertFailsWith<IllegalArgumentException> { AnswerPayload("Anna", listOf(SharedEntry("a\u0000b"))).encode() }
        assertFailsWith<IllegalArgumentException> { AnswerPayload("Anna", listOf(SharedEntry("x", password = Secret.of("p\u0007w")))).encode() }
        for (title in listOf("x", "x".repeat(200), "x".repeat(1000))) {
            assertEquals(0, AnswerPayload("Anna", listOf(SharedEntry(title))).encode().size % 128)
        }
        AnswerPayload("Anna", listOf(SharedEntry("tab\tand\nlines\r"))).encode()
    }

    @Test
    fun aDateOutOfKeePassRangeIsMalformedNotACrash() {
        val expiry = Instant.parse("2027-01-01T00:00:00Z").epochSecond
        val bytes = AnswerPayload("Anna", listOf(SharedEntry("x", expires = Instant.ofEpochSecond(expiry)))).encode().useBytes { it.copyOf() }
        fun longAt(i: Int) = (0 until 8).fold(0L) { value, k -> (value shl 8) or (bytes[i + k].toLong() and 0xFF) }
        val at = (0..bytes.size - 8).first { longAt(it) == expiry }
        // Long.MAX_VALUE seconds: beyond what Instant holds.
        for (k in 0 until 8) bytes[at + k] = if (k == 0) 0x7F else 0xFF.toByte()

        assertFailsWith<MalformedShareException> { AnswerPayload.decode(bytes) }
    }

    @Test
    fun theEncodedFormIsASecretTheWriterLeavesNothingBehind() {
        val secret = AnswerPayload("Anna", listOf(full())).encode()

        assertTrue(secret.useBytes { bytes -> String(bytes, Charsets.ISO_8859_1).contains("k7#Rv9") })
        secret.wipe()
        assertTrue(secret.isWiped)
    }
}
