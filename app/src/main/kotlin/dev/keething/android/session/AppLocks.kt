package dev.keething.android.session

import kotlin.time.Duration

/**
 * How many times the app locked (plan AD7): the Lock button, a database's timeout, the lock alarm, a stay in the
 * background past the timeout with nothing open. A new database made across one stays locked (keething-spec
 * databases.new-database AC9), and New database's form, with what was typed, goes ([onLock]). [now] is a monotonic
 * clock in milliseconds. Call it on the main thread.
 */
internal class AppLocks(private val now: () -> Long) {
    var count = 0
        private set

    /** Each lock, as it is counted. */
    var onLock: () -> Unit = {}

    private var backgroundSince: Long? = null

    fun locked() {
        count++
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
