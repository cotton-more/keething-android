package dev.keething.sharing.message

import dev.keething.sharing.crypto.hex
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessagesTest {
    private val key = hex("430f4b9859665145a6b1ba274024487bd66f03a2dd577d7753c68d7d7d00c00c")
    private val created = Instant.parse("2026-10-23T16:00:00Z")

    /** Version, kind, created, expires (3 days), the key, "Alex", "Sparkasse login for the tax return". */
    private val requestBytes = hex(
        "0101000000006adb8480000000006adf7900430f4b9859665145a6b1ba274024487bd66f03a2dd577d7753c68d7d7d00c00c" +
            "04416c657822537061726b61737365206c6f67696e20666f7220746865207461782072657475726e",
    )

    @Test
    fun aRequestIsWrittenAsTheFormatSays() {
        val request = ShareRequest.create("  Alex ", "Sparkasse login for the tax return", created.plusMillis(700), Duration.ofDays(3), key)

        assertContentEquals(requestBytes, request.encoded())
        assertEquals("Alex", request.name)
        assertEquals(created, request.created)
        assertEquals(created.plus(Duration.ofDays(3)), request.expires)
        assertEquals("085a3dac8cbd1c1d177b737aa4fa7588", request.id)
        assertEquals(request, ShareRequest.decode(requestBytes))
    }

    @Test
    fun aRequestExpiresAtItsTime() {
        val request = ShareRequest.decode(requestBytes)

        assertFalse(request.isExpired(request.expires.minusSeconds(1)))
        assertTrue(request.isExpired(request.expires))
    }

    @Test
    fun aRequestOutOfRangeIsRefused() {
        fun bytes(validDays: Long = 3, name: String = "Alex", reason: String = "Netflix", extra: ByteArray = ByteArray(0)): ByteArray {
            val w = WireWriter()
            w.byte(1); w.byte(1); w.long(created.epochSecond); w.long(created.epochSecond + validDays * 86400)
            w.bytes(key); w.text(name); w.text(reason); w.bytes(extra)
            return w.toByteArray()
        }
        ShareRequest.decode(bytes(validDays = 7))
        for (bad in listOf(
            bytes(validDays = 8), bytes(validDays = 0), bytes(validDays = -1), bytes(name = ""), bytes(name = " Alex"),
            bytes(reason = "two\nlines"), bytes(name = "x".repeat(65)), bytes(reason = "x".repeat(201)), bytes(extra = byteArrayOf(0)),
            byteArrayOf(2) + bytes().copyOfRange(1, bytes().size), requestBytes.copyOf(requestBytes.size - 1),
        )) {
            assertFailsWith<MalformedShareException> { ShareRequest.decode(bad) }
        }
    }

    @Test
    fun craftedDatesFormatCharactersAndSmallOrderKeysAreMalformedNotACrash() {
        fun bytes(from: Long = created.epochSecond, until: Long = from + 86400, name: String = "Alex", key: ByteArray = this.key): ByteArray {
            val w = WireWriter()
            w.byte(1); w.byte(1); w.long(from); w.long(until); w.bytes(key); w.text(name); w.text("Netflix")
            return w.toByteArray()
        }
        for (bad in listOf(
            bytes(from = Long.MAX_VALUE), bytes(from = Long.MIN_VALUE, until = Long.MIN_VALUE + 1),
            bytes(name = "Al\u202Eex"), bytes(name = "Al\u200Bex"), bytes(key = ByteArray(32)),
            bytes(key = hex("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800")),
        )) {
            assertFailsWith<MalformedShareException> { ShareCode.read(bad) }
        }
        assertFalse(ShareRequest.isLine("Al\u202Eex", 64))
    }

    @Test
    fun aNumberHasOneEncodingOnly() {
        val w = WireWriter()
        w.count(300)
        assertContentEquals(byteArrayOf(0xAC.toByte(), 0x02), w.toByteArray())
        assertEquals(300, WireReader(w.toByteArray()).count(1000))
        assertFailsWith<MalformedShareException> { WireReader(byteArrayOf(0x81.toByte(), 0x00)).count(1000) }
        assertFailsWith<MalformedShareException> { WireReader(w.toByteArray()).count(299) }
    }

    @Test
    fun theTextFormHoldsAnyMessage() {
        val text = ShareCode.text(requestBytes)

        assertTrue(text.startsWith("keething:AQEAAAAAatuEgAAAAABq33kAQw9Lm"))
        assertTrue(text.endsWith("."))
        val found = ShareCode.find(text)
        assertEquals(0, found.broken)
        assertContentEquals(requestBytes, found.messages.single())
        assertIs<ShareCode.Read.Request>(ShareCode.read(found.messages.single()))
    }

    @Test
    fun codesAreFoundAmongWordsAndAcrossWrappedLines() {
        val text = ShareCode.text(requestBytes)
        val wrapped = text.chunked(40).joinToString("\r\n")
        val message = "Hi! Open it in Keething (Sharing › Read a code › Paste):\n$wrapped\nThanks.\n" +
            "And again: ${text.uppercase().take(9)}${text.drop(9)} — bye"

        val found = ShareCode.find(message)

        assertEquals(0, found.broken)
        assertEquals(2, found.messages.size)
        found.messages.forEach { assertContentEquals(requestBytes, it) }
    }

    @Test
    fun aCodeCutShortIsCountedAsBroken() {
        val text = ShareCode.text(requestBytes)

        assertEquals(1, ShareCode.find(text.dropLast(1)).broken)
        assertEquals(1, ShareCode.find(text.take(30) + "?" + text.drop(30)).broken)
        assertEquals(0, ShareCode.find("nothing here").messages.size)
        assertFailsWith<MalformedShareException> { ShareCode.read(byteArrayOf(1, 9, 0)) }
    }

    @Test
    fun aLargeMessageGoesInPartsReadInAnyOrder() {
        val message = ByteArray(1500) { it.toByte() }
        val texts = ShareCode.qrTexts(message, maxBytes = 600)

        assertEquals(3, texts.size)
        texts.forEach { assertTrue(ShareCode.find(it).messages.single().size <= 600) }
        val collector = PartCollector()
        val parts = texts.map { assertIs<ShareCode.Read.Part>(ShareCode.read(ShareCode.find(it).messages.single())).part }

        assertEquals(PartCollector.Progress.Waiting(1, 3), collector.add(parts[2]))
        assertEquals(PartCollector.Progress.Waiting(1, 3), collector.add(parts[2]))
        assertEquals(PartCollector.Progress.Waiting(2, 3), collector.add(parts[0]))
        val complete = assertIs<PartCollector.Progress.Complete>(collector.add(parts[1]))
        assertContentEquals(message, complete.message)
    }

    @Test
    fun aSmallMessageIsOneCodeWithoutParts() {
        assertEquals(listOf(ShareCode.text(requestBytes)), ShareCode.qrTexts(requestBytes))
    }

    @Test
    fun partsOfAnotherMessageStartOverAndForeignPiecesAreCaught() {
        val one = ShareCode.qrTexts(ByteArray(1000) { 1 }, maxBytes = 600).map(::part)
        val two = ShareCode.qrTexts(ByteArray(1000) { 2 }, maxBytes = 600).map(::part)
        val collector = PartCollector()

        collector.add(one[0])
        assertEquals(PartCollector.Progress.Waiting(1, 2), collector.add(two[0]))
        assertIs<PartCollector.Progress.Complete>(collector.add(two[1]))

        // A piece of another message under this message's id.
        val forged = ShareCode.read(one[1].let { bytesOf(it).also { b -> b[b.size - 1] = 9 } }) as ShareCode.Read.Part
        collector.add(one[0])
        assertFailsWith<MalformedShareException> { collector.add(forged.part) }
    }

    @Test
    fun tooManyPartsAreRefused() {
        assertFailsWith<IllegalArgumentException> { ShareCode.qrTexts(ByteArray(600 * 17), maxBytes = 600) }
    }

    private fun part(text: String): CodePart = (ShareCode.read(ShareCode.find(text).messages.single()) as ShareCode.Read.Part).part

    private fun bytesOf(part: CodePart): ByteArray {
        val w = WireWriter()
        w.byte(1); w.byte(3); w.bytes(part.messageId); w.byte(part.index); w.byte(part.count); w.bytes(part.piece)
        return w.toByteArray()
    }

    @Test
    fun anEnvelopeOutOfRangeIsRefused() {
        fun bytes(validDays: Long = 3, salt: Int = 16, ciphertext: Int = 16): ByteArray {
            val w = WireWriter()
            w.byte(1); w.byte(4); w.long(created.epochSecond); w.long(created.epochSecond + validDays * 86400)
            w.bytes(ByteArray(salt)); w.bytes(ByteArray(ciphertext))
            return w.toByteArray()
        }
        val envelope = assertIs<ShareCode.Read.Envelope>(ShareCode.read(bytes())).envelope
        assertEquals(created, envelope.created)
        assertEquals(created.plus(Duration.ofDays(3)), envelope.expires)
        ShareEnvelope.decode(bytes(validDays = 7))
        for (bad in listOf(bytes(validDays = 8), bytes(validDays = 0), bytes(ciphertext = 15), bytes(salt = 8, ciphertext = 0))) {
            assertFailsWith<MalformedShareException> { ShareCode.read(bad) }
        }
    }
}
