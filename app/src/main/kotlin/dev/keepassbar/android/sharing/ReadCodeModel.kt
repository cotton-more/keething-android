package dev.keepassbar.android.sharing

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keepassbar.sharing.message.MalformedShareException
import dev.keepassbar.sharing.message.PartCollector
import dev.keepassbar.sharing.message.ShareCode
import dev.keepassbar.sharing.message.ShareRequest
import dev.keepassbar.sharing.requests.ShareRequests
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
 * answer for a request of this phone is opened, parts of a large answer are collected in any order. What cannot be
 * used is said in a banner, and the camera goes on looking.
 */
internal class ReadCodeModel(private val requests: ShareRequests, private val parts: PartCollector) : ViewModel() {
    data class Banner(val title: String, val text: String, val isProblem: Boolean)

    sealed interface Outcome {
        /** A request to answer: its screen, to check the pictures. */
        data class Theirs(val id: String) : Outcome

        /** An answer for a request of this phone, as text: its screen opens it. */
        data class Answer(val text: String) : Outcome
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
            banner = Banner("No code in what was shared", "Share the picture or the text of a Keething code, or read it here.", isProblem = true)
            return
        }
        val fresh = if (fromCamera) texts.filter { text -> seen[text]?.let { it.elapsedNow() < REPEAT } != true } else texts
        if (fresh.isEmpty()) return
        fresh.forEach { seen[it] = TimeSource.Monotonic.markNow() }
        val found = fresh.map(ShareCode::find)
        val messages = found.flatMap { it.messages }
        if (messages.isEmpty()) {
            banner = if (found.any { it.broken > 0 }) {
                Banner("The code is cut short", "Part of it is missing or was changed. Read it again, or ask for it once more.", isProblem = true)
            } else {
                Banner("Not a Keething code", "This one is something else. Read the code of a request or an answer.", isProblem = true)
            }
            return
        }
        viewModelScope.launch {
            try {
                for (message in messages) if (handle(message)) break
            } catch (failure: RuntimeException) {
                // A code nothing here foresaw: said, not a crash.
                banner = Banner("Not a code this app can read", failure.javaClass.simpleName, isProblem = true)
            }
        }
    }

    /** True when it led somewhere: the rest are not read. */
    private suspend fun handle(message: ByteArray): Boolean {
        val read = try {
            ShareCode.read(message)
        } catch (_: MalformedShareException) {
            banner = Banner("Not a code this app can read", "It may come from a newer Keething, or it was changed on the way.", isProblem = true)
            return false
        }
        when (read) {
            is ShareCode.Read.Part -> {
                val progress = try {
                    parts.add(read.part)
                } catch (_: MalformedShareException) {
                    banner = Banner("These codes do not belong together", "Read all the codes of one answer.", isProblem = true)
                    return false
                }
                when (progress) {
                    is PartCollector.Progress.Waiting -> {
                        val rest = progress.count - progress.read
                        banner = Banner(
                            "Code ${progress.read} of ${progress.count} read",
                            if (rest == 1) "Now the other one." else "Now the other $rest, in any order.",
                            isProblem = false,
                        )
                        return false
                    }
                    is PartCollector.Progress.Complete -> return handle(progress.message)
                }
            }
            is ShareCode.Read.Request -> return receive(read.request)
            is ShareCode.Read.Answer -> {
                val forThisPhone = requests.book.value?.mine?.any { it.request.id == read.answer.requestId } == true
                if (!forThisPhone) {
                    banner = Banner(
                        "Not for a request on this phone",
                        "It answers a request that was closed, expired, or made on another phone. Ask again.",
                        isProblem = true,
                    )
                    return false
                }
                outcomes.send(Outcome.Answer(ShareCode.text(message)))
                return true
            }
        }
    }

    private suspend fun receive(request: ShareRequest): Boolean {
        busy = true
        val started = TimeSource.Monotonic.markNow()
        val received = try {
            withContext(Dispatchers.Default) { requests.receive(request) }.also { Log.i("Sharing", "request read in ${started.elapsedNow()}") }
        } catch (failure: Exception) {
            banner = Banner("The request could not be kept", failure.message ?: failure.javaClass.simpleName, isProblem = true)
            return false
        } finally {
            busy = false
        }
        when (received) {
            is ShareRequests.Received.Added -> outcomes.send(Outcome.Theirs(received.request.request.id))
            is ShareRequests.Received.AlreadyHere -> outcomes.send(Outcome.Theirs(received.request.request.id))
            ShareRequests.Received.Own -> {
                banner = Banner("This is your own request", "Send it to the one who should answer it.", isProblem = false)
                return false
            }
            ShareRequests.Received.Expired -> {
                banner = Banner(
                    "This request has expired",
                    "It ran out on ${SharingTexts.dateTime(request.expires)}. Ask ${request.name} to send a new one.",
                    isProblem = true,
                )
                return false
            }
            ShareRequests.Received.BadDates -> {
                banner = Banner(
                    "This request's dates are wrong",
                    "It says it was made later than now, or lasts longer than a week. Check both phones' clocks, or ask for a new one.",
                    isProblem = true,
                )
                return false
            }
            ShareRequests.Received.Answered -> {
                banner = Banner("Answered already", "This phone answered this request. For more, ask ${request.name} for a new one.", isProblem = false)
                return false
            }
            ShareRequests.Received.Closed -> return false
        }
        return true
    }

    fun problem(title: String, text: String) {
        banner = Banner(title, text, isProblem = true)
    }

    private companion object {
        val REPEAT = 3.seconds
    }
}
