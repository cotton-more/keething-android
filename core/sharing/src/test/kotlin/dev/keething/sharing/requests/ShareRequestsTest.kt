package dev.keething.sharing.requests

import dev.keething.domain.secret.Secret
import dev.keething.domain.sharing.LostBook
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
        /** A full disk: nothing can be written or deleted. */
        var failing = false
        override fun read(): ByteArray? = bytes
        override fun write(bytes: ByteArray) {
            if (failing) throw java.io.IOException("No space left on device")
            this.bytes = bytes.copyOf()
        }
        override fun delete() {
            if (failing) throw java.io.IOException("No space left on device")
            bytes = null
        }
        var lost: LostBook? = null
        override fun readLost(): LostBook? = lost
        override fun writeLost(lost: LostBook?) { this.lost = lost }
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

    private fun ShareRequests.Asked.made(): MyRequest = assertIs<ShareRequests.Asked.Made>(this).request

    private fun ShareRequests.Answering.sealed(): ShareAnswer = assertIs<ShareRequests.Answering.Sealed>(this).answer

    private fun ShareRequests.Sent.kept(): SentEnvelope = assertIs<ShareRequests.Sent.Kept>(this).envelope

    private fun payload() = AnswerPayload("Anna", listOf(SharedEntry("Sparkasse", "4711 0815", Secret.of("geheim"))))

    @Test
    fun anAnswerTravelsFromOnePhoneToTheOther() {
        val mine = alex.ask("Alex", "Sparkasse login", Duration.ofDays(3)).made()
        val asText = ShareCode.text(mine.request.encoded())

        val read = assertIs<ShareCode.Read.Request>(ShareCode.read(ShareCode.find(asText).messages.single())).request
        val theirs = assertIs<ShareRequests.Received.Added>(anna.receive(read)).request
        assertEquals(mine.pictures, theirs.pictures)
        assertEquals("Alex", theirs.request.name)
        assertEquals(ShareRequests.Answering.Gone, anna.answer(theirs.request.id, payload()), "not before the pictures were checked")
        assertEquals(ShareRequests.Checked.Matched, anna.check(theirs.request.id, mine.pictures))
        val answer = anna.answer(theirs.request.id, payload()).sealed()
        assertTrue(anna.book.value!!.theirs.isEmpty(), "one answer per request")
        assertEquals(ShareRequests.Answering.Gone, anna.answer(theirs.request.id, payload()))

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
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(1)).made()
        anna.receive(mine.request)
        val heard = mine.pictures

        assertEquals(ShareRequests.Checked.Mismatched, anna.check(mine.request.id, heard.reversed().takeIf { it != heard } ?: heard.map { (it + 1) % 64 }))
        assertEquals(ShareRequests.Checked.Mismatched, anna.check(mine.request.id, heard.take(4)))
        assertEquals(ShareRequests.Checked.Mismatched, anna.check(mine.request.id, heard.dropLast(1) + (heard.last() + 1) % 64))
        assertFalse(anna.book.value!!.theirs.single().checked)
        assertEquals(ShareRequests.Answering.Gone, anna.answer(mine.request.id, payload()))
        assertEquals(ShareRequests.Checked.Matched, anna.check(mine.request.id, heard))
        assertTrue(anna.book.value!!.theirs.single().checked)
        assertEquals(ShareRequests.Checked.Gone, anna.check("nope", heard))
    }

    @Test
    fun aChangedAnswerOrOneForAnotherRequestDoesNotOpen() {
        val first = alex.ask("Alex", "one", Duration.ofDays(1)).made()
        val second = alex.ask("Alex", "two", Duration.ofDays(1)).made()
        anna.receive(first.request); anna.check(first.request.id, first.pictures)
        val answer = anna.answer(first.request.id, payload()).sealed().encoded()

        val changed = answer.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(ShareRequests.Opened.Broken, alex.open(ShareAnswer.decode(changed)))
        // The first request's answer named as the second's.
        val renamed = answer.copyOf().also { second.request.id.chunked(2).forEachIndexed { i, h -> it[2 + i] = h.toInt(16).toByte() } }
        assertEquals(ShareRequests.Opened.Broken, alex.open(ShareAnswer.decode(renamed)))
        assertIs<ShareRequests.Opened.Entries>(alex.open(ShareAnswer.decode(answer)))
    }

    @Test
    fun aRequestAnsweredHereCannotBeAnsweredAgainUntilItExpires() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(1)).made()
        anna.receive(mine.request)
        anna.check(mine.request.id, mine.pictures)
        anna.answer(mine.request.id, payload()).sealed()

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
        val fromTheFuture = later.ask("Bob", "Wi-Fi", Duration.ofDays(1)).made().request

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
            override fun readLost(): LostBook? = null
            override fun writeLost(lost: LostBook?) { written = true }
        }
        val requests = ShareRequests(broken, clock)
        requests.open(Secret.copyOf(key))

        assertNull(requests.book.value)
        assertTrue(requests.unreadable.value, "said: the book is closed for this unlock")
        assertEquals(ShareRequests.Asked.Closed, requests.ask("Alex", "Netflix", Duration.ofDays(1)))
        assertFalse(broken.written)
        requests.close()
        assertFalse(requests.unreadable.value, "tried again at the next unlock")
        requests.cannotOpen()
        assertTrue(requests.unreadable.value, "its key could not be loaded")
        assertFalse(alex.unreadable.value)
    }

    @Test
    fun aBookThatCannotBeWrittenChangesNothingAndSaysSo() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(3)).made()
        val wifi = anna.ask("Anna", "Wi-Fi", Duration.ofDays(3)).made()
        val theirs = assertIs<ShareRequests.Received.Added>(alex.receive(wifi.request)).request
        val written = alexStore.bytes!!.copyOf()
        val book = alex.book.value
        alexStore.failing = true

        assertEquals(ShareRequests.Asked.NotKept, alex.ask("Alex", "Bank", Duration.ofDays(1)))
        assertEquals(ShareRequests.Received.NotKept, alex.receive(anna.ask("Anna", "Mail", Duration.ofDays(1)).made().request))
        assertEquals(ShareRequests.Checked.NotKept, alex.check(theirs.request.id, wifi.pictures))
        assertFalse(alex.delete(mine.request.id))
        assertFalse(mine.privateKey.isWiped, "a request that stays keeps its key")
        assertEquals(ShareRequests.Sent.NotKept, alex.send(payload(), "", listOf("Sparkasse"), Duration.ofDays(1)))
        val envelope = anna.send(payload(), "", listOf("Sparkasse"), Duration.ofDays(1)).kept().envelope
        assertFalse(alex.saved(envelope))

        assertSame(book, alex.book.value, "memory stays what the file holds")
        assertContentEquals(written, alexStore.bytes)

        alexStore.failing = false
        assertEquals(ShareRequests.Checked.Matched, alex.check(theirs.request.id, wifi.pictures))
        assertTrue(alex.delete(mine.request.id))
        assertTrue(mine.privateKey.isWiped)
    }

    @Test
    fun anAnswerIsNotGivenWhenItsRequestCannotBeRemoved() {
        val annaStore = MemoryStore()
        val anna = ShareRequests(annaStore, clock).also { it.open(Secret.copyOf(ByteArray(32) { 7 })) }
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(1)).made()
        anna.receive(mine.request)
        anna.check(mine.request.id, mine.pictures)
        annaStore.failing = true

        assertEquals(ShareRequests.Answering.NotKept, anna.answer(mine.request.id, payload()))
        assertTrue(anna.book.value!!.theirs.single().checked, "still there to answer once the file can be written")
        annaStore.failing = false
        anna.answer(mine.request.id, payload()).sealed()
    }

    @Test
    fun whatExpiredGoesFromMemoryAlthoughTheFileCannotBeWrittenAndFromTheFileAtTheNextChange() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(1)).made()
        val wifi = alex.ask("Alex", "Wi-Fi", Duration.ofDays(3)).made()
        alexStore.failing = true
        clock.time = clock.time.plus(Duration.ofDays(1))

        assertEquals(ShareRequests.Arrival.ToOpen, alex.arrived(anna.send(payload(), "", listOf("x"), Duration.ofDays(1)).kept().envelope))
        assertEquals(listOf(wifi.request), alex.book.value!!.mine.map { it.request })
        assertTrue(mine.privateKey.isWiped)

        alexStore.failing = false
        alex.ask("Alex", "Bank", Duration.ofDays(1)).made()
        alex.close()
        alex.open(Secret.copyOf(key))
        assertEquals(listOf("Bank", "Wi-Fi"), alex.book.value!!.mine.map { it.request.reason })
    }

    @Test
    fun ownExpiredAndRepeatedRequestsAreToldApart() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(1)).made()

        assertEquals(ShareRequests.Received.Own, alex.receive(mine.request))
        val added = assertIs<ShareRequests.Received.Added>(anna.receive(mine.request))
        assertSame(added.request, assertIs<ShareRequests.Received.AlreadyHere>(anna.receive(mine.request)).request)
        clock.time = mine.request.expires
        assertEquals(ShareRequests.Received.Expired, anna.receive(mine.request))
    }

    @Test
    fun expiredRequestsGoWithTheirKeys() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(1)).made()
        anna.receive(mine.request)
        anna.check(mine.request.id, mine.pictures)
        val answer = anna.answer(mine.request.id, payload()).sealed()
        val wifi = alex.ask("Alex", "Wi-Fi", Duration.ofDays(3)).made()
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
        assertEquals(ShareRequests.Answering.Gone, anna.answer(theirs.request.id, payload()))
        assertTrue(anna.book.value!!.theirs.isEmpty())
    }

    // spec: sharing.request/AC7
    @Test
    fun expiredRequestsGoWhenTheBookIsChangedOrShownToo() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(1)).made()
        val theirs = assertIs<ShareRequests.Received.Added>(anna.receive(mine.request)).request
        clock.time = mine.request.expires.plusSeconds(60)

        assertEquals(ShareRequests.Checked.Gone, anna.check(theirs.request.id, mine.pictures))
        alex.ask("Alex", "Wi-Fi", Duration.ofDays(1)).made()
        assertEquals(listOf("Wi-Fi"), alex.book.value!!.mine.map { it.request.reason })
        assertTrue(mine.privateKey.isWiped)

        val later = alex.ask("Alex", "Bank", Duration.ofDays(1)).made()
        clock.time = later.request.expires
        alex.purgeExpired()
        assertTrue(alex.book.value!!.mine.isEmpty(), "the screens purge as the seconds pass")
        assertNull(alexStore.bytes)
    }

    @Test
    fun theBookIsSealedWithTheLocalKeyAndOnlyItOpensIt() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(3)).made()
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

    // spec: sharing.book/AC3
    @Test
    fun aBookThatCanNeverBeReadIsGivenUpAndItsLossSaidOnceForAWeek() {
        val mine = anna.receive(alex.ask("Alex", "Netflix", Duration.ofDays(3)).made().request) as ShareRequests.Received.Added
        anna.check(mine.request.request.id, mine.request.pictures)
        val answer = anna.answer(mine.request.request.id, payload()).sealed()

        alex.close()
        alex.open(Secret.copyOf(ByteArray(32) { 1 }))   // the Keystore's key gone: SharingKey makes a new one
        assertTrue(alex.book.value!!.mine.isEmpty())
        assertNull(alexStore.bytes, "the file that can never be read goes")
        assertEquals(LostBook(clock.time, told = false), alex.lost.value)
        assertEquals(ShareRequests.Opened.Lost, alex.open(answer), "an answer to a request lost with the book")
        assertEquals(ShareRequests.Whose.Lost, alex.whose(answer), "told before it is opened")

        alex.toldLost()
        alex.close()
        clock.time = clock.time.plus(Duration.ofDays(2))
        alex.open(Secret.copyOf(ByteArray(32) { 1 }))
        assertEquals(LostBook(Instant.parse("2026-10-23T12:00:00Z"), told = true), alex.lost.value, "kept, and said once")
        assertEquals(ShareRequests.Opened.Lost, alex.open(answer))

        clock.time = Instant.parse("2026-10-23T12:00:00Z").plus(Duration.ofDays(8))
        alex.close()
        alex.open(Secret.copyOf(ByteArray(32) { 1 }))
        assertNull(alex.lost.value, "a week and a day on, no answer can be for a lost request")
        assertNull(alexStore.lost)
        assertEquals(ShareRequests.Opened.NotHere, alex.open(answer))
        assertEquals(ShareRequests.Whose.NotHere, alex.whose(answer))
        val wifi = alex.ask("Alex", "Wi-Fi", Duration.ofDays(1)).made()
        anna.receive(wifi.request); anna.check(wifi.request.id, wifi.pictures)
        assertEquals(ShareRequests.Whose.Here, alex.whose(anna.answer(wifi.request.id, payload()).sealed()))
    }

    @Test
    fun aDamagedBookOrOneOfAnUnknownVersionIsGivenUpTooANewBookIsNot() {
        alex.ask("Alex", "Netflix", Duration.ofDays(3)).made()
        val sealed = alexStore.bytes!!
        assertNull(alex.lost.value, "a book read as it is is not lost")

        for (broken in listOf(sealed.copyOf().also { it[0] = 9 }, sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })) {
            alexStore.bytes = broken
            alexStore.lost = null
            alex.open(Secret.copyOf(key))
            assertTrue(alex.book.value!!.mine.isEmpty())
            assertNotNull(alex.lost.value)
            assertNull(alexStore.bytes)
        }

        val fresh = MemoryStore()
        ShareRequests(fresh, clock).open(Secret.copyOf(key))
        assertNull(fresh.lost, "no file is no loss")
    }

    @Test
    fun anEmptyBookLeavesNoFile() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(3)).made()
        alex.delete(mine.request.id)

        assertNull(alexStore.bytes)
    }

    @Test
    fun aClosedBookDoesNothing() {
        alex.close()

        assertEquals(ShareRequests.Asked.Closed, alex.ask("Alex", "Netflix", Duration.ofDays(3)))
        assertEquals(ShareRequests.Received.Closed, alex.receive(anna.ask("Anna", "x", Duration.ofDays(1)).made().request))
    }

    @Test
    fun thePicturesAreTheRequestsOwn() {
        val one = alex.ask("Alex", "Netflix", Duration.ofDays(3)).made()
        val two = alex.ask("Alex", "Netflix", Duration.ofDays(3)).made()

        assertEquals(Pictures.of(ShareRequest.decode(one.request.encoded())), one.pictures)
        assertTrue(one.pictures != two.pictures, "another key, other pictures (a 2^-36 chance otherwise)")
    }

    private fun send(validFor: Duration = Duration.ofDays(3)) =
        anna.send(payload(), "Alex", listOf("Sparkasse"), validFor).kept()

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
        val fromTheFuture = later.send(payload(), "", listOf("Wi-Fi"), Duration.ofDays(1)).kept()

        assertEquals(ShareRequests.Arrival.BadDates, alex.arrived(fromTheFuture.envelope))
    }

    @Test
    fun aFileOfVersionOneIsStillRead() {
        val mine = alex.ask("Alex", "Netflix", Duration.ofDays(3)).made()
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
