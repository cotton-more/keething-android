package dev.keething.android.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The app's locks, counted for a new database made across one (keething-spec databases.new-database AC9). */
class AppLocksTest {
    private var now = 0L
    private val locks = AppLocks { now }
    private val timeout = 1.minutes

    @Test
    fun eachLockCountsAndIsTold() {
        var told = 0
        locks.onLock = { told++ }
        locks.locked()
        locks.locked()
        assertEquals(2, locks.count)
        assertEquals(2, told)
    }

    @Test
    fun awayPastTheTimeoutIsALockOnReturnShorterIsNot() {
        locks.movedToBackground()
        now += 59_000
        locks.movedToForeground(timeout)
        assertEquals(0, locks.count, "back within the timeout")
        locks.movedToBackground()
        now += 60_000
        locks.movedToForeground(timeout)
        assertEquals(1, locks.count, "with nothing open to lock, too")
        now += 120_000
        assertFalse(locks.awayPast(timeout), "in the foreground: not away")
    }

    @Test
    fun lockedSinceCountsALockOrTheTimeAwayNotYetCounted() {
        val before = locks.count
        assertFalse(locks.lockedSince(before, timeout))
        locks.movedToBackground()
        now += 30_000
        assertFalse(locks.lockedSince(before, timeout), "away, within the timeout")
        now += 30_000
        assertTrue(locks.lockedSince(before, timeout), "still away, past the timeout: as good as locked")
        locks.movedToForeground(timeout)
        assertTrue(locks.lockedSince(before, timeout), "and counted on return")
        assertTrue(AppLocks { 0 }.apply { locked() }.lockedSince(0, timeout), "the button, the timeout, the alarm")
    }
}
