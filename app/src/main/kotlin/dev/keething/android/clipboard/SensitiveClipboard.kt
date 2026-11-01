package dev.keething.android.clipboard

import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Who put what is on the clipboard now, as far as the app may know. */
internal sealed interface ClipOwner {
    /** A copy of this app's, marked with the [token] of that copy. */
    data class Ours(val token: String) : ClipOwner

    /** Another app's copy, or nothing. */
    data object Other : ClipOwner

    /** Android hides the clipboard from an app in the background. */
    data object Unknown : ClipOwner
}

/** The system clipboard: a port, so that the clearing rules are tested without Android. */
internal interface SystemClipboard {
    /** Puts [text] on the clipboard, marked with [token]; [sensitive] hides its preview (Android 13+). */
    fun put(text: String, sensitive: Boolean, token: String)

    fun owner(): ClipOwner

    fun clear()

    /** The clipboard's text; null when it holds none, or the app is not in front (only it may read, Android 10+). */
    fun text(): String?

    /**
     * Whether the clipboard holds text, from its description alone (keething-spec app.quick-note AC7: before an
     * unlock nothing of the text is read, and the system says nothing of a look at the description).
     */
    fun hasText(): Boolean
}

/** Wakes the app to clear the clipboard: Android freezes an app in the background, so a timer may not run. */
internal interface ClearAlarm {
    /** Replaces an alarm set before: only the newest copy is cleared. */
    fun schedule(token: String, after: Duration)

    fun cancel()
}

/**
 * Copies a value and clears it again after [clearAfter] (SPEC §4), unless something else was copied
 * meanwhile (Mac `CopyService`). Call it on the main thread.
 *
 * Both a timer and an alarm clear: the timer is on time while the app runs, the alarm wakes a frozen app.
 * In the background Android does not show the app the clipboard, so then it is cleared whatever it holds.
 */
internal class SensitiveClipboard(
    private val system: SystemClipboard,
    private val alarm: ClearAlarm,
    private val scope: CoroutineScope,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    /** The copy this process made last, and whether it has been dealt with. */
    private var latestToken: String? = null
    private var latestDone = false
    private var timer: Job? = null

    fun copy(text: String, sensitive: Boolean) {
        val token = newToken()
        system.put(text, sensitive, token)
        latestToken = token
        latestDone = false
        timer?.cancel()
        timer = scope.launch {
            delay(clearAfter)
            clearIfStill(token)
        }
        alarm.schedule(token, clearAfter)
    }

    /** Clears the copy marked [token] if the clipboard may still hold it. */
    fun clearIfStill(token: String) {
        // Only the latest copy is cleared, and only once: clearing blind a second time could wipe what the
        // user copied elsewhere since. An alarm in a restarted process knows only its own copy.
        if (latestToken != null && (token != latestToken || latestDone)) return
        when (val owner = system.owner()) {
            is ClipOwner.Ours -> if (owner.token == token) system.clear()
            ClipOwner.Other -> {}
            ClipOwner.Unknown -> system.clear()
        }
        latestToken = token
        latestDone = true
        timer?.cancel()
        alarm.cancel()
    }

    companion object {
        /** Fixed, not a setting (SPEC §4). */
        val clearAfter: Duration = 30.seconds
    }
}
