package dev.keething.android.sharing

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiPlural
import dev.keething.android.ui.uiText
import dev.keething.sharing.message.MalformedShareException
import dev.keething.sharing.message.PartCollector
import dev.keething.sharing.message.ShareCode
import dev.keething.sharing.message.ShareRequest
import dev.keething.sharing.requests.ShareRequests
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reading codes (SPEC §10), from the camera, a picture, a paste or another app: a request is kept to be answered, an
 * answer for a request of this phone is opened, an envelope goes to its pictures, parts of a large answer or envelope
 * are collected in any order. What cannot be used is said in a banner, and the camera goes on looking.
 */
internal class ReadCodeModel(private val requests: ShareRequests, private val parts: PartCollector) : ViewModel() {
    data class Banner(val title: UiText, val text: UiText, val isProblem: Boolean)

    sealed interface Outcome {
        /** A request to answer: its screen, to check the pictures. */
        data class Theirs(val id: String) : Outcome

        /** An answer for a request of this phone, or an envelope, as text: its screen opens it. */
        data class Sealed(val text: String) : Outcome
    }

    var banner by mutableStateOf<Banner?>(null)
        private set

    /** Working out a request's pictures, about a third of a second. */
    var busy by mutableStateOf(false)
        private set

    private val outcomes = Channel<Outcome>(Channel.BUFFERED)
    val outcomesFlow: Flow<Outcome> = outcomes.receiveAsFlow()

    private val seen = mutableMapOf<String, TimeSource.Monotonic.ValueTimeMark>()

    /** Each text's codes; the camera sees a code in every frame, so one read in the last seconds is passed over. */
    fun read(texts: List<String>, fromCamera: Boolean = false) {
        if (busy) return
        // What another app shared held nothing to read (a picture without a code, or one the app may not open).
        if (texts.all(String::isBlank)) {
            banner = Banner(uiText(R.string.sharing_read_nothing_shared_title), uiText(R.string.sharing_read_nothing_shared_text), isProblem = true)
            return
        }
        val fresh = if (fromCamera) texts.filter { text -> seen[text]?.let { it.elapsedNow() < REPEAT } != true } else texts
        if (fresh.isEmpty()) return
        fresh.forEach { seen[it] = TimeSource.Monotonic.markNow() }
        val found = fresh.map(ShareCode::find)
        val messages = found.flatMap { it.messages }
        if (messages.isEmpty()) {
            banner = if (found.any { it.broken > 0 }) {
                Banner(uiText(R.string.sharing_read_cut_title), uiText(R.string.sharing_read_cut_text), isProblem = true)
            } else {
                Banner(uiText(R.string.sharing_read_not_keething_title), uiText(R.string.sharing_read_not_keething_text), isProblem = true)
            }
            return
        }
        viewModelScope.launch {
            try {
                for (message in messages) if (handle(message)) break
            } catch (failure: RuntimeException) {
                // A code nothing here foresaw: said, not a crash.
                banner = Banner(uiText(R.string.sharing_read_unknown_title), uiPlain(failure.javaClass.simpleName), isProblem = true)
            }
        }
    }

    /** True when it led somewhere: the rest are not read. */
    private suspend fun handle(message: ByteArray): Boolean {
        // The requests could not be read at this unlock: nothing here can be kept or opened.
        if (requests.unreadable.value) {
            banner = Banner(uiText(R.string.sharing_unreadable_title), uiText(R.string.sharing_unreadable), isProblem = true)
            return true
        }
        val read = try {
            ShareCode.read(message)
        } catch (_: MalformedShareException) {
            banner = Banner(uiText(R.string.sharing_read_unknown_title), uiText(R.string.sharing_read_unknown_text), isProblem = true)
            return false
        }
        when (read) {
            is ShareCode.Read.Part -> {
                val progress = try {
                    parts.add(read.part)
                } catch (_: MalformedShareException) {
                    banner = Banner(uiText(R.string.sharing_read_mixed_title), uiText(R.string.sharing_read_mixed_text), isProblem = true)
                    return false
                }
                when (progress) {
                    is PartCollector.Progress.Waiting -> {
                        val rest = progress.count - progress.read
                        banner = Banner(
                            uiText(R.string.sharing_read_part_title, progress.read, progress.count),
                            uiPlural(R.plurals.sharing_read_part_rest, rest),
                            isProblem = false,
                        )
                        return false
                    }
                    is PartCollector.Progress.Complete -> return handle(progress.message)
                }
            }
            is ShareCode.Read.Request -> return receive(read.request)
            is ShareCode.Read.Answer -> {
                when (requests.whose(read.answer)) {
                    ShareRequests.Whose.Here -> {}
                    ShareRequests.Whose.NotHere -> {
                        banner = Banner(uiText(R.string.sharing_not_here_title), uiText(R.string.sharing_not_here_text), isProblem = true)
                        return false
                    }
                    // keething-spec sharing.book AC3: the request most likely went with the book given up.
                    ShareRequests.Whose.Lost -> {
                        banner = Banner(uiText(R.string.sharing_received_lost_title), uiText(R.string.sharing_received_lost_text), isProblem = true)
                        return false
                    }
                }
                outcomes.send(Outcome.Sealed(ShareCode.text(message)))
                return true
            }
            is ShareCode.Read.Envelope -> {
                val envelope = read.envelope
                when (requests.arrived(envelope)) {
                    ShareRequests.Arrival.ToOpen -> {
                        outcomes.send(Outcome.Sealed(ShareCode.text(message)))
                        return true
                    }
                    ShareRequests.Arrival.Own -> banner = Banner(
                        uiText(R.string.sharing_own_entries_title),
                        uiText(R.string.sharing_own_entries_text),
                        isProblem = false,
                    )
                    ShareRequests.Arrival.Expired -> banner = Banner(
                        uiText(R.string.sharing_expired_entries_title),
                        uiText(R.string.sharing_read_expired_entries_text, SharingTexts.dateTime(envelope.expires)),
                        isProblem = true,
                    )
                    ShareRequests.Arrival.BadDates -> banner = Banner(
                        uiText(R.string.sharing_bad_dates_title),
                        uiText(R.string.sharing_bad_dates_text),
                        isProblem = true,
                    )
                    ShareRequests.Arrival.Saved -> banner = Banner(
                        uiText(R.string.sharing_saved_already_title),
                        uiText(R.string.sharing_read_saved_already_text),
                        isProblem = false,
                    )
                    ShareRequests.Arrival.Closed -> {}
                }
                return false
            }
        }
    }

    private suspend fun receive(request: ShareRequest): Boolean {
        busy = true
        val started = TimeSource.Monotonic.markNow()
        val received = try {
            withContext(Dispatchers.Default) { requests.receive(request) }.also { Log.i("Sharing", "request read in ${started.elapsedNow()}") }
        } finally {
            busy = false
        }
        when (received) {
            is ShareRequests.Received.Added -> outcomes.send(Outcome.Theirs(received.request.request.id))
            is ShareRequests.Received.AlreadyHere -> outcomes.send(Outcome.Theirs(received.request.request.id))
            ShareRequests.Received.Own -> {
                banner = Banner(uiText(R.string.sharing_read_own_request_title), uiText(R.string.sharing_read_own_request_text), isProblem = false)
                return false
            }
            ShareRequests.Received.Expired -> {
                banner = Banner(
                    uiText(R.string.sharing_read_request_expired_title),
                    uiText(R.string.sharing_read_request_expired_text, SharingTexts.dateTime(request.expires), request.name),
                    isProblem = true,
                )
                return false
            }
            ShareRequests.Received.BadDates -> {
                banner = Banner(
                    uiText(R.string.sharing_read_request_bad_dates_title),
                    uiText(R.string.sharing_read_request_bad_dates_text),
                    isProblem = true,
                )
                return false
            }
            ShareRequests.Received.Answered -> {
                banner = Banner(
                    uiText(R.string.sharing_read_answered_title),
                    uiText(R.string.sharing_read_answered_text, request.name),
                    isProblem = false,
                )
                return false
            }
            ShareRequests.Received.NotKept -> {
                banner = Banner(uiText(R.string.sharing_read_not_kept_title), uiText(R.string.sharing_not_kept), isProblem = true)
                return false
            }
            ShareRequests.Received.Closed -> return false
        }
        return true
    }

    fun problem(title: UiText, text: UiText) {
        banner = Banner(title, text, isProblem = true)
    }

    private companion object {
        val REPEAT = 3.seconds
    }
}
