package dev.keething.android.session

import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How many times the app locked (plan AD7): the Lock button, a database's timeout, the lock alarm, a stay in the
 * background past the timeout with nothing open. A new database made across one stays locked (keething-spec
 * databases.new-database AC9), New database's form, with what was typed, goes ([onLock]), and the chooser's run ends
 * (plan AD13). [now] is a monotonic
 * clock in milliseconds. Call it on the main thread.
 */
internal class AppLocks(private val now: () -> Long) {
    private val mutableCounted = MutableStateFlow(0)

    /** [count], to follow: the chooser's run ends at a lock (plan AD13). */
    val counted: StateFlow<Int> = mutableCounted.asStateFlow()

    val count: Int get() = counted.value

    /** Each lock, as it is counted. */
    var onLock: () -> Unit = {}

    private var backgroundSince: Long? = null

    fun locked() {
        mutableCounted.value++
        onLock()
    }

    fun movedToBackground() {
        backgroundSince = now()
    }

    /** Back in the foreground: away past [timeout] was a lock, whether or not a database was open to lock. */
    fun movedToForeground(timeout: Duration) {
        val away = awayPast(timeout)
        backgroundSince = null
        if (away) locked()
    }

    /** In the background now, for [timeout] or longer: as good as locked, though nothing counted it yet. */
    fun awayPast(timeout: Duration): Boolean = backgroundSince?.let { now() - it >= timeout.inWholeMilliseconds } == true

    /** A lock since [count] was [before]: one counted, or the app away past [timeout] now. */
    fun lockedSince(before: Int, timeout: Duration): Boolean = count != before || awayPast(timeout)
}
