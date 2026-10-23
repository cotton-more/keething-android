package dev.keepassbar.sharing.requests

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.sharing.SharingStore
import dev.keepassbar.domain.time.Clock
import dev.keepassbar.sharing.message.ShareAnswer
import dev.keepassbar.sharing.message.ShareCode
import dev.keepassbar.sharing.message.ShareRequest
import dev.keepassbar.sharing.payload.AnswerPayload
import dev.keepassbar.sharing.payload.SharedEntry
import dev.keepassbar.sharing.pictures.Pictures
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
}
