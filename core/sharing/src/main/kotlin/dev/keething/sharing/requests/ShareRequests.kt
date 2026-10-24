package dev.keething.sharing.requests

import dev.keething.domain.secret.Secret
import dev.keething.domain.sharing.SharingStore
import dev.keething.domain.time.Clock
import dev.keething.sharing.crypto.SealFailure
import dev.keething.sharing.crypto.X25519
import dev.keething.sharing.message.MalformedShareException
import dev.keething.sharing.message.ShareAnswer
import dev.keething.sharing.message.ShareEnvelope
import dev.keething.sharing.message.ShareRequest
import dev.keething.sharing.payload.AnswerPayload
import dev.keething.sharing.pictures.Pictures
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The requests of this phone (SPEC §10): those it made, waiting for an answer, and those it received, to answer.
 * One-time both ways: answering a request removes it, so nothing more can be sent to it; a request of mine goes
 * with its key when its answer was saved, when it is closed, or when it expires. Expired requests go whenever
 * the book is touched.
 *
 * The book is one file, sealed with the local key [open] is given (kept in the Android Keystore, SPEC §10.3),
 * 32 random bytes. It is read at the unlock, held in memory while the vault is unlocked and wiped by [close].
 *
 * The envelopes go through it too: those sealed here are kept with their pictures until they expire or are forgotten,
 * and the ids of those saved here until they expire.
 *
 * Thread-safe; [ask], [receive], [send] and [open] with pictures run Argon2 (about 0.3 s): call them off the main
 * thread.
 */
public class ShareRequests(
    private val store: SharingStore,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    private val mutableBook = MutableStateFlow<RequestBook?>(null)

    /** Null while closed (the vault locked). */
    public val book: StateFlow<RequestBook?> = mutableBook.asStateFlow()

    private var key: Secret? = null

    /**
     * Reads the book with [localKey] (taken over: wiped on [close]). One the key does not open starts empty and is
     * replaced by the next change; a file that cannot be read at all leaves the book closed, so nothing replaces it.
     */
    public fun open(localKey: Secret): Unit = synchronized(this) {
        close()
        val read = try {
            store.read()
        } catch (_: Exception) {
            localKey.wipe()
            return
        }
        key = localKey
        mutableBook.value = read?.let { RequestBook.open(it, localKey) } ?: RequestBook.EMPTY
        purgeExpired()
    }

    public fun close(): Unit = synchronized(this) {
        mutableBook.value?.wipe()
        mutableBook.value = null
        key?.wipe()
        key = null
    }

    /** A new request of mine, saved; null while closed. */
    public fun ask(name: String, reason: String, validFor: Duration): MyRequest? {
        require(ShareRequest.isLine(name.trim(), ShareRequest.MAX_NAME_LENGTH) && ShareRequest.isLine(reason.trim(), ShareRequest.MAX_REASON_LENGTH))
        require(!validFor.isNegative && !validFor.isZero && validFor <= ShareRequest.MAX_VALIDITY)
        val privateKey = X25519.newPrivateKey(random)
        val request = ShareRequest.create(name, reason, clock.now(), validFor, X25519.publicKey(privateKey))
        val mine = MyRequest(request, Secret.adopt(privateKey), Pictures.of(request))
        synchronized(this) {
            val book = mutableBook.value
            if (book == null) {
                mine.privateKey.wipe()
                return null
            }
            change(book.with(mine = listOf(mine) + book.mine))
            return mine
        }
    }

    public sealed interface Received {
        public class Added(public val request: TheirRequest) : Received

        /** Read before: the one kept, checked or not. */
        public class AlreadyHere(public val request: TheirRequest) : Received

        /** This phone made it: it is for the one who should answer. */
        public data object Own : Received

        public data object Expired : Received

        /** Made in the future, or valid longer than allowed from now: a clock is wrong, or the request was made up. */
        public data object BadDates : Received

        /** Answered here already: one answer per request. */
        public data object Answered : Received

        public data object Closed : Received
    }

    public fun receive(request: ShareRequest): Received {
        known(request)?.let { return it }
        val pictures = Pictures.of(request)
        synchronized(this) {
            known(request)?.let { return it }
            val book = mutableBook.value ?: return Received.Closed
            val their = TheirRequest(request, clock.now(), checked = false, pictures = pictures)
            change(book.with(theirs = listOf(their) + book.theirs))
            return Received.Added(their)
        }
    }

    private fun known(request: ShareRequest): Received? = synchronized(this) {
        purgeExpired()
        val book = mutableBook.value ?: return Received.Closed
        val now = clock.now()
        when {
            request.isExpired(now) -> Received.Expired
            badDates(request.created, request.expires, now) -> Received.BadDates
            request.id in book.answered -> Received.Answered
            book.mine.any { it.request.id == request.id } -> Received.Own
            else -> book.theirs.firstOrNull { it.request.id == request.id }?.let { Received.AlreadyHere(it) }
        }
    }

    public enum class Checked { Matched, Mismatched, Gone }

    /**
     * Their request [id] is checked when [heard], the pictures the asker read out and the user tapped (SPEC §10), are
     * its six in their order; anything else leaves it unchecked, so it cannot be answered.
     */
    public fun check(id: String, heard: List<Int>): Checked = synchronized(this) {
        val book = mutableBook.value ?: return Checked.Gone
        val their = book.theirs.firstOrNull { it.request.id == id } ?: return Checked.Gone
        if (heard != their.pictures) return Checked.Mismatched
        change(book.with(theirs = book.theirs.map { if (it === their) TheirRequest(it.request, it.received, true, it.pictures) else it }))
        Checked.Matched
    }

    /** Deletes a request either way, one of mine with its key; or forgets the pictures of an envelope sent from here. */
    public fun delete(id: String): Unit = synchronized(this) {
        val book = mutableBook.value ?: return
        book.mine.filter { it.request.id == id }.forEach { it.privateKey.wipe() }
        change(
            book.with(
                mine = book.mine.filterNot { it.request.id == id },
                theirs = book.theirs.filterNot { it.request.id == id },
                sent = book.sent.filterNot { it.envelope.id == id },
            ),
        )
    }

    /**
     * Seals [payload] for their request [id] and removes the request: one answer per request. Null when there is
     * no such request (expired, deleted), or its pictures were not checked.
     */
    public fun answer(id: String, payload: AnswerPayload): ShareAnswer? = synchronized(this) {
        purgeExpired()
        val book = mutableBook.value ?: return null
        val their = book.theirs.firstOrNull { it.request.id == id && it.checked } ?: return null
        val answer = Answers.seal(their.request, payload, random)
        change(book.with(theirs = book.theirs.filterNot { it.request.id == id }, answered = book.answered + (id to their.request.expires)))
        answer
    }

    public sealed interface Opened {
        /** The entries, the caller's to wipe; the request stays until [close]d or expired. */
        public class Entries(public val request: MyRequest, public val payload: AnswerPayload) : Opened

        /** For no request of this phone: closed, expired, or made on another phone. */
        public data object NotHere : Opened

        /** Changed on the way, or not really for that request. */
        public data object Broken : Opened
    }

    public fun open(answer: ShareAnswer): Opened = synchronized(this) {
        purgeExpired()
        val mine = mutableBook.value?.mine?.firstOrNull { it.request.id == answer.requestId } ?: return Opened.NotHere
        try {
            Opened.Entries(mine, Answers.open(answer, mine))
        } catch (_: MalformedShareException) {
            Opened.Broken
        }
    }

    /**
     * Seals [payload] in a new envelope under ten pictures drawn here, and keeps it with them, [recipient] (only on this
     * phone, may be empty) and the [titles] of its entries. Null while closed.
     */
    public fun send(payload: AnswerPayload, recipient: String, titles: List<String>, validFor: Duration): SentEnvelope? {
        require(recipient.isEmpty() || ShareRequest.isLine(recipient, ShareRequest.MAX_NAME_LENGTH))
        if (mutableBook.value == null) return null
        val pictures = Pictures.random(Pictures.ENVELOPE_COUNT, random)
        val sent = SentEnvelope(Envelopes.seal(payload, pictures, clock.now(), validFor, random), pictures, recipient, payload.from, titles)
        synchronized(this) {
            val book = mutableBook.value ?: return null
            change(book.with(sent = listOf(sent) + book.sent))
            return sent
        }
    }

    /** The share sheet sent envelope [id]'s code, or its pictures, through [component]: the other is offered elsewhere. */
    public fun sentVia(id: String, pictures: Boolean, component: String): Unit = synchronized(this) {
        val book = mutableBook.value ?: return
        change(
            book.with(
                sent = book.sent.map {
                    when {
                        it.envelope.id != id -> it
                        pictures -> SentEnvelope(it.envelope, it.pictures, it.recipient, it.from, it.titles, it.codeVia, component)
                        else -> SentEnvelope(it.envelope, it.pictures, it.recipient, it.from, it.titles, component, it.picturesVia)
                    }
                },
            ),
        )
    }

    public sealed interface Arrival {
        /** To open with its pictures. */
        public data object ToOpen : Arrival

        /** Sealed on this phone: it is for the one it was sent to. */
        public data object Own : Arrival

        public data object Expired : Arrival

        /** Made in the future, or valid longer than allowed from now. */
        public data object BadDates : Arrival

        /** Its entries were saved here already. */
        public data object Saved : Arrival

        public data object Closed : Arrival
    }

    /** What reading [envelope] here leads to, before its pictures are asked for. */
    public fun arrived(envelope: ShareEnvelope): Arrival = synchronized(this) {
        purgeExpired()
        val book = mutableBook.value ?: return Arrival.Closed
        val now = clock.now()
        when {
            book.sent.any { it.envelope.id == envelope.id } -> Arrival.Own
            envelope.isExpired(now) -> Arrival.Expired
            badDates(envelope.created, envelope.expires, now) -> Arrival.BadDates
            envelope.id in book.saved -> Arrival.Saved
            else -> Arrival.ToOpen
        }
    }

    public sealed interface Unsealed {
        /** The entries, the caller's to wipe. */
        public class Entries(public val payload: AnswerPayload) : Unsealed

        /** Not its pictures, or it was changed on the way: the two cannot be told apart. */
        public data object WrongPictures : Unsealed

        /** Its pictures, but what it holds cannot be read. */
        public data object Broken : Unsealed

        /** Expired, saved already, or the book closed: what [arrived] says. */
        public class NotNow(public val arrival: Arrival) : Unsealed
    }

    /** Opens [envelope] with the [pictures] the user tapped or pasted, in order. */
    public fun open(envelope: ShareEnvelope, pictures: List<Int>): Unsealed {
        if (pictures.size != Pictures.ENVELOPE_COUNT) return Unsealed.WrongPictures
        arrived(envelope).let { if (it != Arrival.ToOpen) return Unsealed.NotNow(it) }
        return try {
            Unsealed.Entries(Envelopes.open(envelope, pictures))
        } catch (_: SealFailure) {
            Unsealed.WrongPictures
        } catch (_: MalformedShareException) {
            Unsealed.Broken
        }
    }

    /** [envelope]'s entries were saved here: read again, it is not opened, until it expires. */
    public fun saved(envelope: ShareEnvelope): Unit = synchronized(this) {
        val book = mutableBook.value ?: return
        change(book.with(saved = book.saved + (envelope.id to envelope.expires)))
    }

    private fun badDates(created: Instant, expires: Instant, now: Instant): Boolean =
        created > now + CLOCK_SKEW || expires > now + ShareRequest.MAX_VALIDITY + CLOCK_SKEW

    private fun purgeExpired() {
        val book = mutableBook.value ?: return
        val now = clock.now()
        val (expired, mine) = book.mine.partition { it.request.isExpired(now) }
        val next = book.with(
            mine = mine,
            theirs = book.theirs.filterNot { it.request.isExpired(now) },
            answered = book.answered.filterValues { it > now },
            sent = book.sent.filterNot { it.envelope.isExpired(now) },
            saved = book.saved.filterValues { it > now },
        )
        if (expired.isEmpty() && next.theirs.size == book.theirs.size && next.answered.size == book.answered.size &&
            next.sent.size == book.sent.size && next.saved.size == book.saved.size
        ) {
            return
        }
        expired.forEach { it.privateKey.wipe() }
        change(next)
    }

    private fun RequestBook.with(
        mine: List<MyRequest> = this.mine,
        theirs: List<TheirRequest> = this.theirs,
        answered: Map<String, Instant> = this.answered,
        sent: List<SentEnvelope> = this.sent,
        saved: Map<String, Instant> = this.saved,
    ): RequestBook = RequestBook(mine, theirs, answered, sent, saved)

    /** The book becomes [next] and is written; a request that went is already wiped by the caller. */
    private fun change(next: RequestBook) {
        val key = key ?: return
        mutableBook.value = next
        if (next.isEmpty) {
            runCatching { store.delete() }
        } else {
            store.write(RequestBook.seal(next, key, random))
        }
    }

    private companion object {
        /** How far two phones' clocks may be apart. */
        val CLOCK_SKEW: Duration = Duration.ofDays(1)
    }
}
