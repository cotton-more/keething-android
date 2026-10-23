package dev.keepassbar.sharing.requests

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.sharing.SharingStore
import dev.keepassbar.domain.time.Clock
import dev.keepassbar.sharing.crypto.X25519
import dev.keepassbar.sharing.message.MalformedShareException
import dev.keepassbar.sharing.message.ShareAnswer
import dev.keepassbar.sharing.message.ShareRequest
import dev.keepassbar.sharing.payload.AnswerPayload
import dev.keepassbar.sharing.pictures.Pictures
import java.security.SecureRandom
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The requests of this phone (SPEC §10): those it made, waiting for an answer, and those it received, to answer.
 * One-time both ways: answering a request removes it, so nothing more can be sent to it; a request of mine goes
 * with its key when its answer was saved, when it is closed, or when it expires. Expired requests go whenever
 * the book is touched.
 *
 * The book is one file, sealed with a key from the database's ([open]): readable only with the database unlocked,
 * as hard to guess as the database itself. It is held in memory while the vault is unlocked and wiped by [close].
 *
 * Thread-safe; [ask] and [receive] work out the pictures (Argon2, about 0.3 s): call them off the main thread.
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
            change(RequestBook(listOf(mine) + book.mine, book.theirs, book.answered))
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
            change(RequestBook(book.mine, listOf(their) + book.theirs, book.answered))
            return Received.Added(their)
        }
    }

    private fun known(request: ShareRequest): Received? = synchronized(this) {
        purgeExpired()
        val book = mutableBook.value ?: return Received.Closed
        val now = clock.now()
        when {
            request.isExpired(now) -> Received.Expired
            request.created > now + CLOCK_SKEW || request.expires > now + ShareRequest.MAX_VALIDITY + CLOCK_SKEW -> Received.BadDates
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
        change(RequestBook(book.mine, book.theirs.map { if (it === their) TheirRequest(it.request, it.received, true, it.pictures) else it }, book.answered))
        Checked.Matched
    }

    /** Deletes a request either way; one of mine goes with its key. */
    public fun delete(id: String): Unit = synchronized(this) {
        val book = mutableBook.value ?: return
        book.mine.filter { it.request.id == id }.forEach { it.privateKey.wipe() }
        change(RequestBook(book.mine.filterNot { it.request.id == id }, book.theirs.filterNot { it.request.id == id }, book.answered))
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
        change(RequestBook(book.mine, book.theirs.filterNot { it.request.id == id }, book.answered + (id to their.request.expires)))
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

    private fun purgeExpired() {
        val book = mutableBook.value ?: return
        val now = clock.now()
        val (expired, mine) = book.mine.partition { it.request.isExpired(now) }
        val theirs = book.theirs.filterNot { it.request.isExpired(now) }
        val answered = book.answered.filterValues { it > now }
        if (expired.isEmpty() && theirs.size == book.theirs.size && answered.size == book.answered.size) return
        expired.forEach { it.privateKey.wipe() }
        change(RequestBook(mine, theirs, answered))
    }

    /** The book becomes [next] and is written; a request that went is already wiped by the caller. */
    private fun change(next: RequestBook) {
        val key = key ?: return
        mutableBook.value = next
        if (next.mine.isEmpty() && next.theirs.isEmpty() && next.answered.isEmpty()) {
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
