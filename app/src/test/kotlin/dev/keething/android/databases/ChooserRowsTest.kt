package dev.keething.android.databases

import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.domain.vault.Vault
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.FingerprintOutcome
import dev.keething.vault.biometric.SetUpAgain
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chooser's rows (plan AD13; keething-spec databases.chooser AC3, AC4; the Mac's `WindowDatabasesModel.chooserRow`):
 * how each database of the window opens, what typing cannot open cannot be checked, the rest checked as last time.
 */
class ChooserRowsTest {
    private fun line(name: String, missing: Boolean = false, keyFile: String? = null, keyMissing: Boolean = false) = DatabaseLine(
        id = DatabaseId(name),
        label = DatabaseLabel(name),
        fileName = "$name.kdbx",
        path = "/storage/emulated/0/Sync/$name.kdbx",
        folder = "Sync",
        status = null,
        reach = if (missing) Reach.NOT_FOUND else Reach.THERE,
        keyFile = keyFile?.let { KeyFileLine(it, "Keys", "/storage/emulated/0/Keys/$it", reach = if (keyMissing) Reach.NOT_FOUND else Reach.THERE) },
        isCurrent = false,
        lastOpened = null,
        modified = null,
    )

    private val locked = SessionState.Locked(LockReason.Initial)
    private fun failed(failure: OpenFailure) = SessionState.Locked(LockReason.UnlockFailed(failure))

    private val off = BiometricState(appEnabled = true, databaseEnabled = false, availability = BiometricAvailability.Available)
    private val on = BiometricState(appEnabled = true, databaseEnabled = true, availability = BiometricAvailability.Available, stored = true)

    private fun row(session: SessionState = locked, line: DatabaseLine = line("Work"), fingerprint: BiometricState = off, checked: Boolean = true) =
        ChooserRows.row(session, line, fingerprint, checked)

    // spec: databases.chooser/AC3
    // spec: databases.chooser/AC4
    @Test
    fun theChooserSaysHowEachOpensWhatTypingCannotOpenCannotBeCheckedTheOthersAsLastTime() {
        val window = listOf(
            WindowDatabase(DatabaseId("Personal"), locked, on),
            WindowDatabase(DatabaseId("Work"), locked, off),
            WindowDatabase(DatabaseId("Solo"), locked, off.copy(passwordless = true)),
            WindowDatabase(DatabaseId("Family"), locked, on.copy(passwordDue = true, reminderDays = 14)),
            WindowDatabase(DatabaseId("Crypto"), locked, on),
            WindowDatabase(DatabaseId("Taxes"), locked, off),
            WindowDatabase(DatabaseId("Club"), locked, off),
        )
        val lines = listOf(
            line("Personal"), line("Work", keyFile = "work.keyx"), line("Solo", keyFile = "solo.keyx"), line("Family"),
            line("Crypto", missing = true), line("Taxes", keyFile = "taxes.keyx", keyMissing = true), line("Club"), line("Elsewhere"),
        )

        val rows = ChooserRows.of(window, lines) { it != DatabaseId("Club") }

        assertEquals(listOf("Personal", "Work", "Solo", "Family", "Crypto", "Taxes", "Club"), rows.map { it.line.name }, "the window's, in its order")
        assertEquals(
            listOf("Fingerprint", "Password + work.keyx", "Key file solo.keyx", "Fingerprint paused · 14 days", "Not found", "taxes.keyx not there", "Password"),
            rows.map { it.opensWith },
        )
        assertEquals(
            listOf(ChooserRow.Tone.READY, ChooserRow.Tone.PLAIN, ChooserRow.Tone.PLAIN, ChooserRow.Tone.WARNING, ChooserRow.Tone.PROBLEM, ChooserRow.Tone.PROBLEM, ChooserRow.Tone.PLAIN),
            rows.map { it.tone },
        )
        assertEquals(listOf(true, true, true, true, false, false, true), rows.map { it.canCheck })
        assertEquals(listOf(true, true, true, true, false, false, false), rows.map { it.isChecked }, "checked unless unchecked; not what cannot be")
        assertEquals(listOf(false, false, false, false, true, false, false), rows.map { it.offersLocate })
        assertTrue(ChooserRows.offersFingerprint(rows))
        assertEquals("Unlock 4 databases", ChooserRows.unlockTitle(rows))
        assertEquals(listOf("Personal", "Work", "Solo", "Family"), ChooserRows.checked(rows).map { it.value })
        assertEquals("7 databases · scroll for the rest", ChooserRows.countText(rows))
    }

    @Test
    fun theUnlockButtonSaysHowManyAndTheListScrollsPastFive() {
        val one = listOf(row(checked = true), row(line = line("Club"), checked = false))
        assertEquals("Unlock 1 database", ChooserRows.unlockTitle(one))
        assertEquals("Unlock", ChooserRows.unlockTitle(listOf(row(checked = false))))
        assertNull(ChooserRows.countText(List(5) { row(line = line("D$it")) }), "five show whole")
        assertFalse(ChooserRows.offersFingerprint(one), "no fingerprint opens either")
    }

    // spec: databases.chooser/AC4
    @Test
    fun aDatabaseThatFailedSoTypingCannotOpenItCannotBeChecked() {
        assertEquals("Cannot be read", row(failed(OpenFailure.Damaged("truncated"))).opensWith)
        assertFalse(row(failed(OpenFailure.Unsupported("KDBX 2.0", OpenFailure.Unsupported.Kind.OLD_FORMAT))).canCheck)
        val unusable = row(failed(OpenFailure.KeyFileUnusable("it is empty")), line("Work", keyFile = "work.keyx"))
        assertEquals("work.keyx cannot be used", unusable.opensWith)
        assertFalse(unusable.canCheck)
        assertEquals("Password", row(failed(OpenFailure.WrongPassword)).opensWith, "a typo is typed again")
    }

    // spec: databases.chooser/AC3
    @Test
    fun theFingerprintsStatesOpenAndOpeningOnes() {
        assertEquals("Fingerprint to set up again", row(fingerprint = on.copy(stored = false, setUpAgain = SetUpAgain.PASSWORD_REJECTED)).opensWith)
        assertEquals("Fingerprint to set up again", row(fingerprint = on.copy(stored = false, setUpAgain = SetUpAgain.FINGERPRINTS_CHANGED)).opensWith)
        assertEquals("Fingerprint to set up", row(fingerprint = on.copy(stored = false)).opensWith)
        assertEquals(ChooserRow.Tone.WARNING, row(fingerprint = on.copy(stored = false)).tone)
        assertEquals("Fingerprint", row(fingerprint = on.copy(stored = false, passwordless = true), line = line("Solo", keyFile = "solo.keyx")).opensWith)
        assertEquals("Password", row(fingerprint = on.copy(availability = BiometricAvailability.Unavailable("no sensor"))).opensWith, "the phone cannot")
        assertEquals("Password", row(fingerprint = on.copy(appEnabled = false)).opensWith, "the app's switch off")

        val opening = row(SessionState.Unlocking, checked = true)
        assertEquals("Opening…", opening.opensWith)
        assertFalse(opening.canCheck || opening.isChecked, "opening already: not offered")
        val open = row(SessionState.Unlocked(Vault(emptyList())))
        assertEquals("Open", open.opensWith)
        assertFalse(open.canCheck)
    }

    // spec: databases.chooser/AC2
    @Test
    fun whatTheChooserSaysWhenTheFingerprintOpenedNone() {
        assertEquals("The fingerprint was cancelled.", ChooserRows.note(FingerprintOutcome.Cancelled))
        assertEquals("The fingerprint failed: Too many attempts. Try again later.", ChooserRows.note(FingerprintOutcome.AuthenticationFailed("Too many attempts. Try again later.")))
        assertEquals("The fingerprint failed: sensor error.", ChooserRows.note(FingerprintOutcome.AuthenticationFailed("sensor error")))
        assertEquals(
            "The fingerprints on this phone changed: type the passwords to set up the fingerprint again.",
            ChooserRows.note(FingerprintOutcome.KeyInvalidated),
        )
        assertEquals("Could not read the stored passwords: the Keystore did not open the keyring.", ChooserRows.note(FingerprintOutcome.StorageError("the Keystore did not open the keyring")))
        // Each database's row says its own: its fingerprint off, its password due or rejected, nothing stored.
        for (outcome in listOf(
            FingerprintOutcome.Unlocked, FingerprintOutcome.InProgress, FingerprintOutcome.NothingStored, FingerprintOutcome.PasswordDue,
            FingerprintOutcome.StoredPasswordRejected, FingerprintOutcome.NotAvailable("it is turned off for this database"),
            FingerprintOutcome.UnlockFailed(OpenFailure.Missing),
        )) assertNull(ChooserRows.note(outcome), outcome.toString())
    }
}
