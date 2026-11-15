package dev.keething.domain.sharing

import java.time.Instant

/**
 * Where the sharing's requests are kept between runs (SPEC §10): one small file, sealed before it gets here. A
 * port, so the rules around it are tested without Android.
 */
public interface SharingStore {
    /** Null when there is none. */
    public fun read(): ByteArray?

    /** Replaces what was there. */
    public fun write(bytes: ByteArray)

    public fun delete()

    /** The last book given up because it could not be read; null when there is none. */
    public fun readLost(): LostBook?

    /** Keeps [lost], or forgets it when null. */
    public fun writeLost(lost: LostBook?)
}

/**
 * A book that could not be read (its key gone, its content damaged) and was started again empty, [at] that moment
 * (keething-spec sharing.book AC3): no secret, no name. [told] once the user read that it was lost.
 */
public data class LostBook(val at: Instant, val told: Boolean)
