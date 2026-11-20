package dev.keething.sharing.payload

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.sharing.message.MalformedShareException
import dev.keething.sharing.crypto.X25519
import dev.keething.sharing.message.ShareEnvelope
import dev.keething.sharing.message.ShareRequest
import dev.keething.sharing.requests.Answers
import dev.keething.sharing.requests.Envelopes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertFalse(e.notesProtected)
        assertNull(e.entryType)
    }

    private fun note() = SharedEntry(title = "Films to watch", notes = "Dune\nArrival", notesProtected = true, entryType = "note")

    // spec: sharing.content/AC9
    @Test
    fun anEntryWithoutProtectedNotesOrAKindIsStillVersionOne() {
        assertEquals(1, AnswerPayload("Anna", listOf(full(), SharedEntry(title = "Wi-Fi"))).encode().useBytes { it[0].toInt() })
        // Protected Notes that are empty send nothing protected.
        assertEquals(1, AnswerPayload("Anna", listOf(SharedEntry(title = "x", notesProtected = true))).encode().useBytes { it[0].toInt() })
    }

    // spec: sharing.content/AC9
    @Test
    fun aNoteOrProtectedNotesGoAsVersionTwoAndComeBackWhole() {
        val routerAdmin = SharedEntry(title = "Router admin", password = Secret.of("pw"), notes = "admin / 192.168.178.1", notesProtected = true)
        for (entries in listOf(listOf(note()), listOf(routerAdmin), listOf(full(), SharedEntry(title = "Shopping", notes = "milk", entryType = "note")))) {
            val bytes = AnswerPayload("Anna", entries).encode()
            assertEquals(2, bytes.useBytes { it[0].toInt() })

            val read = bytes.useBytes { AnswerPayload.decode(it) }.entries
            assertEquals(entries.map { it.title }, read.map { it.title })
            assertEquals(entries.map { it.notes }, read.map { it.notes })
            assertEquals(entries.map { it.notesProtected }, read.map { it.notesProtected })
            assertEquals(entries.map { it.entryType }, read.map { it.entryType })
            assertEquals(entries.map { it.password }, read.map { it.password })
            assertEquals(entries.map { it.totp }, read.map { it.totp })
            assertEquals(entries.map { it.customFields }, read.map { it.customFields })
        }
    }

    // spec: sharing.content/AC9
    @Test
    fun aKindThisVersionDoesNotKnowGoesAsItWasStored() {
        val read = AnswerPayload("Anna", listOf(SharedEntry(title = "x", entryType = " Checklist "))).encode().useBytes { AnswerPayload.decode(it) }

        assertEquals(" Checklist ", read.entries.single().entryType)
        assertFailsWith<IllegalArgumentException> { AnswerPayload("Anna", listOf(SharedEntry(title = "x", entryType = "no\u0000te"))).encode() }
    }

    // spec: sharing.content/AC9
    @Test
    fun aVersionTwoCutAnywhereOrWithABadFlagOrKindIsRefusedAndOtherVersionsToo() {
        val bytes = AnswerPayload("Anna", listOf(note(), full())).encode().useBytes { it.copyOf() }
        for (length in 0 until bytes.size) {
            assertFailsWith<MalformedShareException>("cut at $length") { AnswerPayload.decode(bytes.copyOf(length)) }
        }
        assertFailsWith<MalformedShareException> { AnswerPayload.decode(bytes + 0) }

        // 02 | "Anna" | 1 entry | title "x" | four empty fields | the flag, then the kind.
        fun content(flag: Int, kind: Int) = byteArrayOf(2, 4, 'A'.code.toByte(), 'n'.code.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 1, 1, 'x'.code.toByte(), 0, 0, 0, 0, flag.toByte(), kind.toByte())
        // The rest of an entry with nothing: no tags, no expiry, no fields, no types, no code; then the padding to 128.
        fun padded(head: ByteArray): ByteArray {
            val body = head + byteArrayOf(0, 0, 0, 0, 0)
            return body + byteArrayOf((128 - body.size - 1).toByte()) + ByteArray(128 - body.size - 1)
        }
        assertEquals(1, AnswerPayload.decode(padded(content(0, 0))).entries.size)
        assertTrue(AnswerPayload.decode(padded(content(1, 0))).entries.single().notesProtected)
        assertFailsWith<MalformedShareException> { AnswerPayload.decode(padded(content(2, 0))) }
        assertFailsWith<MalformedShareException> { AnswerPayload.decode(padded(content(0, 2))) }
        for (version in listOf(0, 3, 0xFF)) {
            val other = padded(content(0, 0)).also { it[0] = version.toByte() }
            assertFailsWith<MalformedShareException>("version $version") { AnswerPayload.decode(other) }
        }
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
        ) + listOf("", "Title", "UserName", "URL", "Notes", "TOTP Seed", "TOTP Settings", "TimeOtp-Secret-Base32", "TimeOtp-Period", "TimeOtp-Secret-Hex").map {
            AnswerPayload("Anna", listOf(SharedEntry("x", customFields = listOf(CustomField.plain(it, "a")))))
        }) {
            // The sender reads it back by the receiver's rules before anything is sealed.
            assertFailsWith<IllegalArgumentException> { payload.encode() }
        }
    }

    // spec: sharing.content/AC3
    @Test
    fun aOneTimeCodeOutOfRangeOrMoreThan500OfAnythingIsRefused() {
        fun code(key: Secret = Secret.of("12345678901234567890"), digits: Int = 6, period: Int = 30) =
            AnswerPayload("Anna", listOf(SharedEntry("x", totp = TotpConfig(key, digits, period))))
        for (payload in listOf(
            code(digits = 0), code(digits = 11), code(period = 0), code(period = 86401), code(key = Secret.empty()), code(key = Secret.adopt(ByteArray(1025) { 1 })),
            AnswerPayload("Anna", listOf(SharedEntry("x", tags = List(501) { "t$it" }))),
            AnswerPayload("Anna", listOf(SharedEntry("x", customFields = List(501) { CustomField.plain("f$it", "") }))),
        )) {
            assertFailsWith<IllegalArgumentException> { payload.encode() }
        }
        for (digits in listOf(1, 10)) code(digits = digits).encode()
        code(period = 86400, key = Secret.adopt(ByteArray(1024) { 1 })).encode()
        AnswerPayload("Anna", listOf(SharedEntry("x", tags = List(500) { "t$it" }))).encode()

        // An algorithm not known: 0 SHA-1, 1 SHA-256, 2 SHA-512 only.
        val bytes = code(digits = 7, period = 29).encode().useBytes { it.copyOf() }
        val at = (0 until bytes.size - 2).first { bytes[it] == 7.toByte() && bytes[it + 1] == 29.toByte() && bytes[it + 2] == 0.toByte() } + 2
        bytes[at] = 2
        assertEquals(TotpConfig.Algorithm.entries[2], AnswerPayload.decode(bytes).entries.single().totp?.algorithm)
        bytes[at] = 3
        assertFailsWith<MalformedShareException> { AnswerPayload.decode(bytes) }
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

    /** 01 | [from] | one entry: title "x", no username, [password], no address nor notes, nothing more | padded to 128. */
    private fun withPassword(password: ByteArray, padding: Int? = null, from: String = "Anna"): ByteArray {
        val name = from.encodeToByteArray()
        val body = byteArrayOf(1, name.size.toByte()) + name + byteArrayOf(1, 1, 'x'.code.toByte(), 0) +
            byteArrayOf(password.size.toByte()) + password + byteArrayOf(0, 0, 0, 0, 0, 0, 0)
        val pad = padding ?: (128 - body.size - 1)
        return body + byteArrayOf(pad.toByte()) + ByteArray(pad)
    }

    @Test
    fun aSenderNameThatIsNotOneLineIsNeitherSentNorRead() {
        for (from in listOf("Al\u200Bex", " Anna", "", "Anna\nB", "A".repeat(65))) {
            assertFailsWith<IllegalArgumentException>(from) { AnswerPayload(from, listOf(SharedEntry("x"))).encode() }
        }
        // Crafted, as only another app would: the content is whole, only its sender's name breaks the rule.
        assertEquals("Anna", AnswerPayload.decode(withPassword("pw".encodeToByteArray(), from = "Anna")).from)
        for (from in listOf("An\u200Bna", "Anna\uDB40\uDC41", " Anna")) {
            assertFailsWith<MalformedShareException>(from) { AnswerPayload.decode(withPassword("pw".encodeToByteArray(), from = from)) }
        }
    }

    // spec: sharing.content/AC2
    @Test
    fun aContentNotPaddedToAMultipleOf128OrWithPaddingNotZeroIsRefused() {
        val good = withPassword("pw".encodeToByteArray())
        assertEquals(1, AnswerPayload.decode(good).entries.size)
        for (padding in listOf(0, 1, 127 - (good.size - 128) - 2)) {
            val short = withPassword("pw".encodeToByteArray(), padding)
            assertTrue(short.size % 128 != 0)
            assertFailsWith<MalformedShareException>("${short.size} bytes") { AnswerPayload.decode(short) }
        }
        val notZero = good.copyOf().also { it[it.size - 1] = 1 }
        assertFailsWith<MalformedShareException> { AnswerPayload.decode(notZero) }
    }

    // spec: sharing.content/AC3
    @Test
    fun aSecretIsUtf8WithoutWhatXmlCannotHold() {
        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        val good = "pä€😀\tx".encodeToByteArray()
        assertEquals(Secret.adopt(good.copyOf()), AnswerPayload.decode(withPassword(good)).entries.single().password)

        for (bad in listOf(
            bytes(0xEF, 0xBF, 0xBF), bytes(0xEF, 0xBF, 0xBE), // U+FFFF, U+FFFE
            bytes(0xC3, 0x28), bytes(0xC3), bytes(0x80), bytes(0xFF), // not UTF-8
            bytes(0xC0, 0x80), bytes(0xE0, 0x80, 0x80), // overlong
            bytes(0xED, 0xA0, 0x80), // a surrogate
            bytes(0xF4, 0x90, 0x80, 0x80), // beyond U+10FFFF
            bytes(0x07), bytes(0x00),
        )) {
            assertFailsWith<MalformedShareException>(bad.joinToString(" ") { "%02X".format(it) }) { AnswerPayload.decode(withPassword(bad)) }
            assertFailsWith<IllegalArgumentException> { AnswerPayload("Anna", listOf(SharedEntry("x", password = Secret.adopt(bad.copyOf())))).encode() }
        }
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

        // Sealing wipes the encoded content, in an answer and in an envelope.
        val random = java.security.SecureRandom()
        val request = ShareRequest.create("Alex", "Wi-Fi", Instant.now(), java.time.Duration.ofDays(1), X25519.publicKey(X25519.newPrivateKey(random)))
        val forAnswer = AnswerPayload("Anna", listOf(full())).encode()
        Answers.sealEncoded(request, forAnswer, X25519.newPrivateKey(random))
        assertTrue(forAnswer.isWiped)
        val forEnvelope = AnswerPayload("Anna", listOf(full())).encode()
        Envelopes.sealEncoded(forEnvelope, List(10) { it }, Instant.now(), java.time.Duration.ofDays(1), ByteArray(ShareEnvelope.SALT_SIZE))
        assertTrue(forEnvelope.isWiped)
    }
}
