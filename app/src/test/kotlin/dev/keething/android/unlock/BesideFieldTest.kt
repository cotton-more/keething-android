package dev.keething.android.unlock

import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.biometric.BiometricState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one button beside the lock screen's password field: the fingerprint or Unlock (SPEC §6.2). */
class BesideFieldTest {
    private val available = BiometricState(appEnabled = true, databaseEnabled = true, availability = BiometricAvailability.Available, stored = true)

    // spec: unlock.lock-screen-keyboard/AC3
    @Test
    fun theFingerprintWhileTheFieldIsEmptyAndItCanOpenTheDatabaseElseUnlock() {
        assertEquals(Beside.FINGERPRINT, besideField(available, offered = true, typed = false))
        assertEquals(Beside.UNLOCK, besideField(available, offered = true, typed = true), "typing: Unlock")
        assertEquals(Beside.UNLOCK, besideField(available, offered = false, typed = false), "not offered (the run, a file not there): Unlock")
        assertEquals(Beside.UNLOCK, besideField(available.copy(appEnabled = false), offered = true, typed = false), "the app's switch off")
        assertEquals(Beside.FINGERPRINT, besideField(available.copy(reminder = true), offered = true, typed = false), "the reminder never withholds it")
        assertEquals(Beside.UNLOCK, besideField(available.copy(stored = false), offered = true, typed = false), "on, nothing stored yet: typed to set it up")
        assertEquals(Beside.FINGERPRINT, besideField(available.copy(stored = false, passwordless = true), offered = true, typed = false), "its key file alone")
    }

    // spec: unlock.biometric/AC4
    @Test
    fun aDatabaseWhoseFingerprintWasNeverChosenKeepsTheFingerprintWhileTypingAndItUnlocks() {
        val neverChosen = available.copy(databaseEnabled = false, stored = false)
        assertEquals(Beside.UNLOCK_THEN_ASK, besideField(neverChosen, offered = true, typed = false))
        assertEquals(Beside.UNLOCK_THEN_ASK, besideField(neverChosen, offered = true, typed = true), "typing does not change it")
        assertEquals(Beside.UNLOCK, besideField(neverChosen.copy(asked = true), offered = true, typed = false), "No: Unlock only")
        assertEquals(Beside.UNLOCK, besideField(neverChosen.copy(availability = BiometricAvailability.Unavailable("none enrolled")), offered = true, typed = false))
        assertEquals(Beside.UNLOCK, besideField(neverChosen.copy(appEnabled = false), offered = true, typed = false))
        assertEquals(Beside.UNLOCK, besideField(neverChosen, offered = false, typed = true), "the chooser's run: Unlock, asked over the list after")
    }
}
