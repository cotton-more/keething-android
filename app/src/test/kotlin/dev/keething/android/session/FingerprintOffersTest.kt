package dev.keething.android.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The lock screen asks for the fingerprint by itself once per visit that began locked (SPEC §5; unlock.biometric AC9). */
class FingerprintOffersTest {
    @Test
    fun onceInAVisitThatBeganLockedWhicheverDatabaseIsShown() {
        val offers = FingerprintOffers()
        val start = Visit(1, beganLocked = true)

        assertTrue(offers.offer(start), "the start: the database shown first")
        // The lock screen asks again with the same visit when another database is chosen and after the user's own Lock
        // (LockScreen's LaunchedEffect; the wiring is not tested here).
        assertFalse(offers.offer(start))

        assertTrue(offers.offer(Visit(2, beganLocked = true)), "a return after the timeout")
        assertFalse(offers.offer(Visit(3, beganLocked = false)), "a visit that began open: the user's own Lock later")
        assertFalse(offers.offer(Visit(3, beganLocked = false)))
    }
}
