package dev.keepassbar.android.session

import android.os.SystemClock
import dev.keepassbar.domain.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** The phone's clocks: wall time, and the monotonic time that keeps counting in deep sleep. */
internal object AndroidClock : Clock {
    override fun now(): Instant = Instant.now()

    override fun elapsed(): Duration = SystemClock.elapsedRealtimeNanos().nanoseconds
}
