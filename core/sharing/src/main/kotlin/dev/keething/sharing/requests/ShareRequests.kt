package dev.keething.sharing.requests

import dev.keething.domain.secret.Secret
import dev.keething.domain.sharing.LostBook
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
 * the book is read or changed ([purgeExpired]).
 *
 * The book is one file, sealed with the local key [open] is given (kept in the Android Keystore, SPEC §10.3),
 * 32 random bytes. It is read at the unlock, held in memory while the vault is unlocked and wiped by [close].
 *
 * The envelopes go through it too: those sealed here are kept with their pictures until they expire or are forgotten,
 * and the ids of those saved here until they expire.
 *
 * Every change is written before it is made: when the file cannot be written (a full disk), the change says
 * NotKept and nothing changed (keething-spec sharing.book AC3); only what expired goes from memory all the same, and from
 * the file at the next change that is written.
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

    private val mutableLost = MutableStateFlow<LostBook?>(null)

    /**
     * The book given up in the last week, if one was (keething-spec sharing.book AC3): the requests it held cannot
     * be answered here any more. Null while closed.
     */
    public val lost: StateFlow<LostBook?> = mutableLost.asStateFlow()

    private val mutableUnreadable = MutableStateFlow(false)

    /**
     * The book could not be read at this unlock (its file or its key could not be had, perhaps for now): it stays closed,
     * nothing replaces it, and the next unlock tries again (keething-spec sharing.book AC3). Said on the sharing screens.
     */
    public val unreadable: StateFlow<Boolean> = mutableUnreadable.asStateFlow()

    private var key: Secret? = null

    /**
     * Reads the book with [localKey] (taken over: wiped on [close]). One that can never be read (the key does not open
     * it, its content is damaged, a version this app does not know) is given up: the book starts empty, the file goes,
     * and the moment is kept a week as [lost]. A file the storage cannot give at all leaves the book closed, so
     * nothing replaces it.
     */
    public fun open(localKey: Secret): Unit = synchronized(this) {
        close()
        val read = try {
            store.read()
        } catch (_: Exception) {
            localKey.wipe()
            mutableUnreadable.value = true
            return
        }
        key = localKey
        val book = read?.let { RequestBook.open(it, localKey) }
        mutableBook.value = book ?: RequestBook.EMPTY
        mutableLost.value = if (read != null && book == null) giveUp() else runCatching { store.readLost() }.getOrNull()
        purgeExpired()
    }

    /** Notes the moment first, so a file that could not be deleted is given up again at the next unlock. */
    private fun giveUp(): LostBook {
        val lost = LostBook(clock.now(), told = false)
        runCatching {
            store.writeLost(lost)
            store.delete()
        }
        return lost
    }

    /** The user read that the book was lost: it is not said again. */
    public fun toldLost(): Unit = synchronized(this) {
        val lost = mutableLost.value?.takeIf { !it.told } ?: return
        mutableLost.value = lost.copy(told = true)
        runCatching { store.writeLost(mutableLost.value) }
    }

    /** The book's key could not be had at this unlock: the book stays closed, and that is said ([unreadable]). */
    public fun cannotOpen(): Unit = synchronized(this) {
        close()
        mutableUnreadable.value = true
    }

    public fun close(): Unit = synchronized(this) {
        mutableBook.value?.wipe()
        mutableBook.value = null
        mutableLost.value = null
        mutableUnreadable.value = false
        key?.wipe()
        key = null
    }

    public sealed interface Asked {
        public class Made(public val request: MyRequest) : Asked

        /** The file could not be written: no request was made. */
        public data object NotKept : Asked

        public data object Closed : Asked
    }

    /** A new request of mine, saved. */
    public fun ask(name: String, reason: String, validFor: Duration): Asked {
        require(ShareRequest.isLine(name.trim(), ShareRequest.MAX_NAME_LENGTH) && ShareRequest.isLine(reason.trim(), ShareRequest.MAX_REASON_LENGTH))
        require(!validFor.isNegative && !validFor.isZero && validFor <= ShareRequest.MAX_VALIDITY)
        val privateKey = X25519.newPrivateKey(random)
        val request = ShareRequest.create(name, reason, clock.now(), validFor, X25519.publicKey(privateKey))
        val mine = MyRequest(request, Secret.adopt(privateKey), Pictures.of(request))
        synchronized(this) {
            purgeExpired()
            val book = mutableBook.value
            if (book == null || !change(book.with(mine = listOf(mine) + book.mine))) {
                mine.privateKey.wipe()
                return if (book == null) Asked.Closed else Asked.NotKept
            }
            return Asked.Made(mine)
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

        /** The file could not be written: not kept. */
        public data object NotKept : Received
    }

    public fun receive(request: ShareRequest): Received {
        known(request)?.let { return it }
        val pictures = Pictures.of(request)
        synchronized(this) {
            known(request)?.let { return it }
            val book = mutableBook.value ?: return Received.Closed
            val their = TheirRequest(request, clock.now(), checked = false, pictures = pictures)
            if (!change(book.with(theirs = listOf(their) + book.theirs))) return Received.NotKept
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

    /** NotKept: they matched, but the file could not be written: still not checked. */
    public enum class Checked { Matched, Mismatched, Gone, NotKept }

    /**
     * Their request [id] is checked when [heard], the pictures the asker read out and the user tapped (SPEC §10), are
     * its six in their order; anything else leaves it unchecked, so it cannot be answered.
     */
    public fun check(id: String, heard: List<Int>): Checked = synchronized(this) {
        purgeExpired()
        val book = mutableBook.value ?: return Checked.Gone
        val their = book.theirs.firstOrNull { it.request.id == id } ?: return Checked.Gone
        if (heard != their.pictures) return Checked.Mismatched
        val checked = book.theirs.map { if (it === their) TheirRequest(it.request, it.received, true, it.pictures) else it }
        if (change(book.with(theirs = checked))) Checked.Matched else Checked.NotKept
    }

    /**
     * Deletes a request either way, one of mine with its key; or forgets the pictures of an envelope sent from here.
     * False when the file could not be written: it stays.
     */
    public fun delete(id: String): Boolean = synchronized(this) {
        purgeExpired()
        val book = mutableBook.value ?: return true
        val kept = change(
            book.with(
                mine = book.mine.filterNot { it.request.id == id },
                theirs = book.theirs.filterNot { it.request.id == id },
                sent = book.sent.filterNot { it.envelope.id == id },
            ),
        )
        if (kept) book.mine.filter { it.request.id == id }.forEach { it.privateKey.wipe() }
        kept
    }

    public sealed interface Answering {
        public class Sealed(public val answer: ShareAnswer) : Answering

        /** No such request (expired, deleted), or its pictures were not checked. */
        public data object Gone : Answering

        /** The file could not be written: the request stays, unanswered. */
        public data object NotKept : Answering
    }

    /** Seals [payload] for their request [id] and removes the request: one answer per request. */
    public fun answer(id: String, payload: AnswerPayload): Answering = synchronized(this) {
        purgeExpired()
        val book = mutableBook.value ?: return Answering.Gone
        val their = book.theirs.firstOrNull { it.request.id == id && it.checked } ?: return Answering.Gone
        val answer = Answers.seal(their.request, payload, random)
        val kept = change(book.with(theirs = book.theirs.filterNot { it.request.id == id }, answered = book.answered + (id to their.request.expires)))
        if (kept) Answering.Sealed(answer) else Answering.NotKept
    }

    public sealed interface Opened {
        /** The entries, the caller's to wipe; the request stays until [close]d or expired. */
        public class Entries(public val request: MyRequest, public val payload: AnswerPayload) : Opened

        /** For no request of this phone: closed, expired, or made on another phone. */
        public data object NotHere : Opened

        /** For no request of this phone, which gave up its book in the last week: most likely for one lost with it. */
        public data object Lost : Opened

        /** Changed on the way, or not really for that request. */
        public data object Broken : Opened
    }

    /** Whose request [answer] is for, read before it is opened. */
    public enum class Whose {
        Here,

        /** For no request of this phone: closed, expired, or made on another phone. */
        NotHere,

        /** For no request of this phone, which gave up its book in the last week: most likely for one lost with it. */
        Lost,
    }

    public fun whose(answer: ShareAnswer): Whose = synchronized(this) {
        purgeExpired()
        when {
            mutableBook.value?.mine?.any { it.request.id == answer.requestId } == true -> Whose.Here
            mutableLost.value != null -> Whose.Lost
            else -> Whose.NotHere
        }
    }

    public fun open(answer: ShareAnswer): Opened = synchronized(this) {
        purgeExpired()
        val mine = mutableBook.value?.mine?.firstOrNull { it.request.id == answer.requestId }
            ?: return if (mutableLost.value != null) Opened.Lost else Opened.NotHere
        try {
            Opened.Entries(mine, Answers.open(answer, mine))
        } catch (_: MalformedShareException) {
            Opened.Broken
        }
    }

    public sealed interface Sent {
        public class Kept(public val envelope: SentEnvelope) : Sent

        /** The file could not be written: nothing was sent. */
        public data object NotKept : Sent

        public data object Closed : Sent
    }

    /**
     * Seals [payload] in a new envelope under ten pictures drawn here, and keeps it with them, [recipient] (only on this
     * phone, may be empty) and the [titles] of its entries.
     */
    public fun send(payload: AnswerPayload, recipient: String, titles: List<String>, validFor: Duration): Sent {
        require(recipient.isEmpty() || ShareRequest.isLine(recipient, ShareRequest.MAX_NAME_LENGTH))
        if (mutableBook.value == null) return Sent.Closed
        val pictures = Pictures.random(Pictures.ENVELOPE_COUNT, random)
        val sent = SentEnvelope(Envelopes.seal(payload, pictures, clock.now(), validFor, random), pictures, recipient, payload.from, titles)
        synchronized(this) {
            purgeExpired()
            val book = mutableBook.value ?: return Sent.Closed
            return if (change(book.with(sent = listOf(sent) + book.sent))) Sent.Kept(sent) else Sent.NotKept
        }
    }

    /**
     * The share sheet sent envelope [id]'s code, or its pictures, through [component]: the other is offered elsewhere.
     * False when the file could not be written: not noted.
     */
    public fun sentVia(id: String, pictures: Boolean, component: String): Boolean = synchronized(this) {
        purgeExpired()
        val book = mutableBook.value ?: return true
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

    /**
     * [envelope]'s entries were saved here: read again, it is not opened, until it expires. False when the file could
     * not be written: not noted.
     */
    public fun saved(envelope: ShareEnvelope): Boolean = synchronized(this) {
        purgeExpired()
        val book = mutableBook.value ?: return true
        change(book.with(saved = book.saved + (envelope.id to envelope.expires)))
    }

    private fun badDates(created: Instant, expires: Instant, now: Instant): Boolean =
        created > now + CLOCK_SKEW || expires > now + ShareRequest.MAX_VALIDITY + CLOCK_SKEW

    /**
     * Removes what expired (keething-spec sharing.request AC7): whenever the book is read or changed, and by the screens
     * that show it as the seconds pass.
     */
    public fun purgeExpired(): Unit = synchronized(this) {
        val book = mutableBook.value ?: return
        val now = clock.now()
        // A request lives a week at most: after that no answer can be for one lost with the book.
        if (mutableLost.value?.let { it.at + ShareRequest.MAX_VALIDITY + CLOCK_SKEW <= now } == true) {
            mutableLost.value = null
            runCatching { store.writeLost(null) }
        }
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
        // What expired cannot be used anyway: it goes from memory even when the file cannot be written, and from the
        // file at the next change that is.
        expired.forEach { it.privateKey.wipe() }
        if (!change(next)) mutableBook.value = next
    }

    private fun RequestBook.with(
        mine: List<MyRequest> = this.mine,
        theirs: List<TheirRequest> = this.theirs,
        answered: Map<String, Instant> = this.answered,
        sent: List<SentEnvelope> = this.sent,
        saved: Map<String, Instant> = this.saved,
    ): RequestBook = RequestBook(mine, theirs, answered, sent, saved)

    /**
     * [next] is written, then becomes the book; false when it could not be written (the book stays as it was). The
     * caller wipes a request that went once this said true.
     */
    private fun change(next: RequestBook): Boolean {
        val key = key ?: return false
        val sealed = if (next.isEmpty) null else RequestBook.seal(next, key, random)
        try {
            if (sealed == null) store.delete() else store.write(sealed)
        } catch (_: Exception) {
            return false
        }
        mutableBook.value = next
        return true
    }

    private companion object {
        /** How far two phones' clocks may be apart. */
        val CLOCK_SKEW: Duration = Duration.ofDays(1)
    }
}
