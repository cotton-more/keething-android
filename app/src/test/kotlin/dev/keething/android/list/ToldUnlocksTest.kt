package dev.keething.android.list

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Unlocked · key derivation …" once per unlock in the app, not once per list (plan AD9, found on the phone). */
class ToldUnlocksTest {
    @Test
    fun anUnlockIsToldOnceAListBuiltAgainForTheSameOpenDatabaseTellsItNoMore() {
        val told = ToldUnlocks()

        assertTrue(told.firstTime(ToldUnlocks.key(session = 1, locks = 0)), "Personal unlocked")
        // Another database's lock screen, then Back: the list is built again over Personal, still open.
        assertFalse(told.firstTime(ToldUnlocks.key(session = 1, locks = 0)))
        assertTrue(told.firstTime(ToldUnlocks.key(session = 2, locks = 0)), "Work unlocked beside it")
        assertTrue(told.firstTime(ToldUnlocks.key(session = 1, locks = 1)), "Personal locked and unlocked again: a new unlock")
    }
}
