package dev.keething.sharing.requests

import dev.keething.domain.secret.Secret
import dev.keething.domain.sharing.SharingStore
import dev.keething.domain.time.Clock
import dev.keething.sharing.message.ShareAnswer
import dev.keething.sharing.message.ShareCode
import dev.keething.sharing.message.ShareEnvelope
import dev.keething.sharing.message.ShareRequest
import dev.keething.sharing.payload.AnswerPayload
import dev.keething.sharing.payload.SharedEntry
import dev.keething.sharing.pictures.Pictures
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

class ShareRequestsTest {
    private class MemoryStore : SharingStore {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override fun delete() { bytes = null }
    }

    private class FixedClock(var time: Instant) : Clock {
        override fun now(): Instant = time
        override fun elapsed(): kotlin.time.Duration = ZERO
    }

    private val clock = FixedClock(Instant.parse("2026-10-23T12:00:00Z"))
    private val key = ByteArray(32) { it.toByte() }

    /** Two phones: Alex asks, Anna answers. */
    private val alexStore = MemoryStore()
    private val alex = ShareRequests(alexStore, clock).also { it.open(Secret.copyOf(key)) }
    private val anna = ShareRequests(MemoryStore(), clock).also { it.open(Secret.copyOf(ByteArray(32) { 7 })) }

    private fun payload() = AnswerPayload("Anna", listOf(SharedEntry("Sparkasse", "4711 0815", Secret.of("geheim"))))

    @Test
    fun anAnswerTravelsFromOnePhoneToTheOther() {
        val mine = assertNotNull(alex.ask("Alex", "Sparkasse login", Duration.ofDays(3)))
        val asText = ShareCode.text(mine.request.encoded())

        val read = assertIs<ShareCode.Read.Request>(ShareCode.read(ShareCode.find(asText).messages.single())).request
        val theirs = assertIs<ShareRequests.Received.Added>(anna.receive(read)).request
        assertEquals(mine.pictures, theirs.pictures)
        assertEquals("Alex", theirs.request.name)
        assertNull(anna.answer(theirs.request.id, payload()), "not before the pictures were checked")
        assertEquals(ShareRequests.Checked.Matched, anna.check(theirs.request.id, mine.pictures))
        val answer = assertNotNull(anna.answer(theirs.request.id, payload()))
        assertTrue(anna.book.value!!.theirs.isEmpty(), "one answer per request")
        assertNull(anna.answer(theirs.request.id, payload()))

        val travelled = ShareAnswer.decode(ShareCode.find(ShareCode.text(answer.encoded())).messages.single())
        val opened = assertIs<ShareRequests.Opened.Entries>(alex.open(travelled))
        assertEquals("Anna", opened.payload.from)
        assertEquals(Secret.of("geheim"), opened.payload.entries.single().password)
        assertIs<ShareRequests.Opened.Entries>(alex.open(travelled), "it opens again until the request is closed")

        alex.delete(mine.request.id)
        assertTrue(mine.privateKey.isWiped)
        assertEquals(ShareRequests.Opened.NotHere, alex.open(travelled))
    }

    @Test
    fun aRequestIsCheckedOnlyWithItsSixPicturesInOrder() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(1))!!
        anna.receive(mine.request)
        val heard = mine.pictures

        assertEquals(ShareRequests.Checked.Mismatched, anna.check(mine.request.id, heard.reversed().takeIf { it != heard } ?: heard.map { (it + 1) % 64 }))
        assertEquals(ShareRequests.Checked.Mismatched, anna.check(mine.request.id, heard.take(4)))
        assertEquals(ShareRequests.Checked.Mismatched, anna.check(mine.request.id, heard.dropLast(1) + (heard.last() + 1) % 64))
        assertFalse(anna.book.value!!.theirs.single().checked)
        assertNull(anna.answer(mine.request.id, payload()))
        assertEquals(ShareRequests.Checked.Matched, anna.check(mine.request.id, heard))
        assertTrue(anna.book.value!!.theirs.single().checked)
        assertEquals(ShareRequests.Checked.Gone, anna.check("nope", heard))
    }

    @Test
    fun aChangedAnswerOrOneForAnotherRequestDoesNotOpen() {
        val first = alex.ask("Alex", "one", Duration.ofDays(1))!!
        val second = alex.ask("Alex", "two", Duration.ofDays(1))!!
        anna.receive(first.request); anna.check(first.request.id, first.pictures)
        val answer = anna.answer(first.request.id, payload())!!.encoded()

        val changed = answer.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(ShareRequests.Opened.Broken, alex.open(ShareAnswer.decode(changed)))
        // The first request's answer named as the second's.
        val renamed = answer.copyOf().also { second.request.id.chunked(2).forEachIndexed { i, h -> it[2 + i] = h.toInt(16).toByte() } }
        assertEquals(ShareRequests.Opened.Broken, alex.open(ShareAnswer.decode(renamed)))
        assertIs<ShareRequests.Opened.Entries>(alex.open(ShareAnswer.decode(answer)))
    }

    @Test
    fun aRequestAnsweredHereCannotBeAnsweredAgainUntilItExpires() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(1))!!
        anna.receive(mine.request)
        anna.check(mine.request.id, mine.pictures)
        anna.answer(mine.request.id, payload())!!

        assertEquals(ShareRequests.Received.Answered, anna.receive(mine.request))
        anna.close()
        anna.open(Secret.copyOf(ByteArray(32) { 7 }))
        assertEquals(ShareRequests.Received.Answered, anna.receive(mine.request), "kept in the file")
        clock.time = mine.request.expires
        assertEquals(ShareRequests.Received.Expired, anna.receive(mine.request))
    }

    @Test
    fun aRequestFromTheFutureOrValidTooLongFromNowIsRefused() {
        val later = ShareRequests(MemoryStore(), FixedClock(clock.time.plus(Duration.ofDays(3)))).also { it.open(Secret.copyOf(ByteArray(32) { 3 })) }
        val fromTheFuture = later.ask("Bob", "Wi-Fi", Duration.ofDays(1))!!.request

        assertEquals(ShareRequests.Received.BadDates, anna.receive(fromTheFuture))
        clock.time = clock.time.plus(Duration.ofDays(2).plusHours(1))
        assertIs<ShareRequests.Received.Added>(anna.receive(fromTheFuture), "within a day of the clocks")
    }

    @Test
    fun aFileThatCannotBeReadIsNeitherOpenedNorReplaced() {
        val broken = object : SharingStore {
            var written = false
            override fun read(): ByteArray? = throw java.io.IOException("disk")
            override fun write(bytes: ByteArray) { written = true }
            override fun delete() { written = true }
        }
        val requests = ShareRequests(broken, clock)
        requests.open(Secret.copyOf(key))

        assertNull(requests.book.value)
        assertNull(requests.ask("Alex", "Netflix", Duration.ofDays(1)))
        assertFalse(broken.written)
    }

    @Test
    fun ownExpiredAndRepeatedRequestsAreToldApart() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(1))!!

        assertEquals(ShareRequests.Received.Own, alex.receive(mine.request))
        val added = assertIs<ShareRequests.Received.Added>(anna.receive(mine.request))
        assertSame(added.request, assertIs<ShareRequests.Received.AlreadyHere>(anna.receive(mine.request)).request)
        clock.time = mine.request.expires
        assertEquals(ShareRequests.Received.Expired, anna.receive(mine.request))
    }

    @Test
    fun expiredRequestsGoWithTheirKeys() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(1))!!
        anna.receive(mine.request)
        anna.check(mine.request.id, mine.pictures)
        val answer = anna.answer(mine.request.id, payload())!!
        val wifi = alex.ask("Alex", "Wi-Fi", Duration.ofDays(3))!!
        val theirs = (anna.receive(wifi.request) as ShareRequests.Received.Added).request
        anna.check(theirs.request.id, wifi.pictures)

        clock.time = clock.time.plus(Duration.ofDays(1))

        assertEquals(ShareRequests.Opened.NotHere, alex.open(answer))
        assertTrue(mine.privateKey.isWiped)
        assertEquals(listOf("Wi-Fi"), alex.book.value!!.mine.map { it.request.reason })
        alex.close()
        alex.open(Secret.copyOf(key))
        assertEquals(listOf("Wi-Fi"), alex.book.value!!.mine.map { it.request.reason }, "the file was written without it")
        clock.time = clock.time.plus(Duration.ofDays(2))
        assertNull(anna.answer(theirs.request.id, payload()))
        assertTrue(anna.book.value!!.theirs.isEmpty())
    }

    @Test
    fun theBookIsSealedWithTheLocalKeyAndOnlyItOpensIt() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(3))!!
        val privateKey = mine.privateKey.useBytes { it.copyOf() }
        val sealed = assertNotNull(alexStore.bytes)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("Netflix"))

        alex.close()
        assertNull(alex.book.value)
        assertTrue(mine.privateKey.isWiped, "closing wipes the keys")
        alex.open(Secret.copyOf(key))
        val reopened = alex.book.value!!.mine.single()
        assertEquals(mine.request, reopened.request)
        assertEquals(mine.pictures, reopened.pictures)
        reopened.privateKey.useBytes { assertContentEquals(privateKey, it) }

        alex.open(Secret.copyOf(ByteArray(32) { 1 }))
        assertTrue(alex.book.value!!.mine.isEmpty(), "another key reads an empty book")
    }

    @Test
    fun anEmptyBookLeavesNoFile() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(3))!!
        alex.delete(mine.request.id)

        assertNull(alexStore.bytes)
    }

    @Test
    fun aClosedBookDoesNothing() {
        alex.close()

        assertNull(alex.ask("Alex", "Netflix", Duration.ofDays(3)))
        assertEquals(ShareRequests.Received.Closed, alex.receive(anna.ask("Anna", "x", Duration.ofDays(1))!!.request))
    }

    @Test
    fun thePicturesAreTheRequestsOwn() {
        val one = alex.ask("Alex", "Netflix", Duration.ofDays(3))!!
        val two = alex.ask("Alex", "Netflix", Duration.ofDays(3))!!

        assertEquals(Pictures.of(ShareRequest.decode(one.request.encoded())), one.pictures)
        assertTrue(one.pictures != two.pictures, "another key, other pictures (a 2^-36 chance otherwise)")
    }

    private fun send(validFor: Duration = Duration.ofDays(3)) =
        assertNotNull(anna.send(payload(), "Alex", listOf("Sparkasse"), validFor))

    @Test
    fun anEnvelopeTravelsAndOpensWithItsTenPicturesOnly() {
        val sent = send()
        assertEquals(Pictures.ENVELOPE_COUNT, sent.pictures.size)
        val read = assertIs<ShareCode.Read.Envelope>(ShareCode.read(ShareCode.find(ShareCode.text(sent.envelope.encoded())).messages.single())).envelope

        assertEquals(ShareRequests.Arrival.ToOpen, alex.arrived(read))
        val wrong = sent.pictures.dropLast(1) + (sent.pictures.last() + 1) % 64
        assertEquals(ShareRequests.Unsealed.WrongPictures, alex.open(read, wrong))
        assertEquals(ShareRequests.Unsealed.WrongPictures, alex.open(read, sent.pictures.take(9)))
        assertEquals(ShareRequests.Unsealed.WrongPictures, alex.open(read, sent.pictures.reversed()))
        val opened = assertIs<ShareRequests.Unsealed.Entries>(alex.open(read, sent.pictures))
        assertEquals("Anna", opened.payload.from)
        assertEquals(Secret.of("geheim"), opened.payload.entries.single().password)

        alex.saved(read)
        assertEquals(ShareRequests.Arrival.Saved, alex.arrived(read))
        assertIs<ShareRequests.Unsealed.NotNow>(alex.open(read, sent.pictures))
        alex.close()
        alex.open(Secret.copyOf(key))
        assertEquals(ShareRequests.Arrival.Saved, alex.arrived(read), "kept in the file")
    }

    @Test
    fun aChangedEnvelopeDoesNotOpen() {
        val sent = send()
        val bytes = sent.envelope.encoded()
        // A later expiry, and a changed ciphertext: the header is the associated data.
        val later = bytes.copyOf().also { it[17] = (it[17] + 1).toByte() }
        val changed = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        for (bad in listOf(later, changed)) assertEquals(ShareRequests.Unsealed.WrongPictures, alex.open(ShareEnvelope.decode(bad), sent.pictures))
    }

    @Test
    fun theSenderKeepsItsEnvelopesUntilTheyExpireOrAreForgotten() {
        val first = send(Duration.ofDays(1))
        val second = send()
        assertTrue(first.pictures != second.pictures, "drawn anew (a 2^-60 chance otherwise)")

        assertEquals(ShareRequests.Arrival.Own, anna.arrived(first.envelope))
        anna.close()
        anna.open(Secret.copyOf(ByteArray(32) { 7 }))
        val kept = anna.book.value!!.sent
        assertEquals(listOf(second.envelope, first.envelope), kept.map { it.envelope })
        assertEquals(second.pictures, kept.first().pictures)
        assertEquals("Alex", kept.first().recipient)
        assertEquals(listOf("Sparkasse"), kept.first().titles)
        assertEquals("Anna", kept.first().from)
        anna.sentVia(second.envelope.id, pictures = false, component = "org.telegram/.Share")
        anna.sentVia(second.envelope.id, pictures = true, component = "com.slack/.Share")
        anna.close()
        anna.open(Secret.copyOf(ByteArray(32) { 7 }))
        assertEquals("org.telegram/.Share" to "com.slack/.Share", anna.book.value!!.sent.first().let { it.codeVia to it.picturesVia })

        clock.time = clock.time.plus(Duration.ofDays(1))
        anna.arrived(second.envelope)
        assertEquals(listOf(second.envelope), anna.book.value!!.sent.map { it.envelope }, "the expired one went")
        assertEquals(ShareRequests.Arrival.Expired, alex.arrived(first.envelope))
        assertIs<ShareRequests.Unsealed.NotNow>(alex.open(first.envelope, first.pictures))

        anna.delete(second.envelope.id)
        assertTrue(anna.book.value!!.sent.isEmpty())
        assertEquals(ShareRequests.Arrival.ToOpen, anna.arrived(second.envelope), "forgotten: no longer known as its own")
    }

    @Test
    fun anEnvelopeFromTheFutureIsRefused() {
        val later = ShareRequests(MemoryStore(), FixedClock(clock.time.plus(Duration.ofDays(3)))).also { it.open(Secret.copyOf(ByteArray(32) { 3 })) }
        val fromTheFuture = later.send(payload(), "", listOf("Wi-Fi"), Duration.ofDays(1))!!

        assertEquals(ShareRequests.Arrival.BadDates, alex.arrived(fromTheFuture.envelope))
    }

    @Test
    fun aFileOfVersionOneIsStillRead() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(3))!!
        val book = RequestBook(listOf(mine), emptyList(), mapOf("0f" to clock.time.plusSeconds(60)))
        // Version 1: the same book without the envelopes' two counts at its end, under the same key and AAD.
        val v2 = RequestBook.seal(book, Secret.copyOf(key), java.security.SecureRandom())
        val plain = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, v2.copyOfRange(1, 13)))
            updateAAD("Keething requests".encodeToByteArray())
            doFinal(v2, 13, v2.size - 13)
        }
        val v1Plain = plain.copyOf(plain.size - 2)
        val v1 = byteArrayOf(1) + v2.copyOfRange(1, 13) + javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, v2.copyOfRange(1, 13)))
            updateAAD("Keething requests".encodeToByteArray())
            doFinal(v1Plain)
        }

        val read = assertNotNull(RequestBook.open(v1, Secret.copyOf(key)))
        assertEquals(mine.request, read.mine.single().request)
        assertTrue(read.sent.isEmpty())
        assertEquals(2, v2[0].toInt(), "written as version 2")
    }
}
