package dev.keething.android.unlock

import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.biometric.BiometricState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one button beside the lock screen's password field: the fingerprint or Unlock (SPEC §6.2). */
class BesideFieldTest {
    private val available = BiometricState(appEnabled = true, databaseEnabled = true, availability = BiometricAvailability.Available, stored = true)

    // spec: unlock.lock-screen-keyboard/AC3
    @Test
    fun theFingerprintWhileTheFieldIsEmptyAndItCanOpenTheDatabaseElseUnlock() {
        assertTrue(fingerprintBesideField(available, offered = true, typed = false))
        assertFalse(fingerprintBesideField(available, offered = true, typed = true), "typing: Unlock")
        assertFalse(fingerprintBesideField(available, offered = false, typed = false), "not offered (the run, a file not there): Unlock")
        assertFalse(fingerprintBesideField(available.copy(appEnabled = false), offered = true, typed = false), "the app's switch off")
        assertFalse(fingerprintBesideField(available.copy(passwordDue = true), offered = true, typed = false), "the password due")
        assertFalse(fingerprintBesideField(available.copy(stored = false), offered = true, typed = false), "on, nothing stored yet: typed to set it up")
        assertTrue(fingerprintBesideField(available.copy(stored = false, passwordless = true), offered = true, typed = false), "its key file alone")
    }

    // spec: unlock.biometric/AC4
    @Test
    fun aDatabaseWhoseFingerprintWasNeverChosenIsOfferedItUntilItIsChosen() {
        val neverChosen = available.copy(databaseEnabled = false, stored = false)
        assertTrue(fingerprintBesideField(neverChosen, offered = true, typed = false))
        assertFalse(fingerprintBesideField(neverChosen.copy(asked = true), offered = true, typed = false), "No: Unlock only")
        assertFalse(fingerprintBesideField(neverChosen.copy(availability = BiometricAvailability.Unavailable("none enrolled")), offered = true, typed = false))
        assertFalse(fingerprintBesideField(neverChosen.copy(appEnabled = false), offered = true, typed = false))
        assertFalse(
            fingerprintBesideField(neverChosen, offered = true, typed = false, emptyOpens = true),
            "a key file's database not known to open without a password: Unlock tries its key file alone",
        )
        assertTrue(fingerprintBesideField(available, offered = true, typed = false, emptyOpens = true), "set up: the fingerprint opens it")
    }
}
