package dev.keepassbar.domain.time

import java.time.Instant
import kotlin.time.Duration

/** The time, behind a port so that tests set it. */
public interface Clock {
    /** The wall-clock time: one-time codes, expiry dates. */
    public fun now(): Instant

    /** Monotonic time since some fixed point, counting deep sleep: timeouts (the user may change the clock). */
    public fun elapsed(): Duration
}
