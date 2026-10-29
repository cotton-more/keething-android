package dev.keething.android.session

/**
 * When the lock screen asks for the fingerprint by itself (SPEC §5; keething-spec unlock.biometric AC9): once in a
 * [Visit] that began locked, for the database shown first. Not after the lock button, nor for another database chosen
 * in the same visit: each database's lock screen is a screen of its own, so the app remembers it, not the screen.
 */
internal class FingerprintOffers {
    private var offered = -1

    /** True once per visit that began locked. */
    fun offer(visit: Visit): Boolean {
        if (!visit.beganLocked || visit.number == offered) return false
        offered = visit.number
        return true
    }
}
