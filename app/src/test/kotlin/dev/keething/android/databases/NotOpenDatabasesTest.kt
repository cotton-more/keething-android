package dev.keething.android.databases

import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.SetUpAgain
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The databases of the window not open and their states (keething-spec databases.not-open; the Mac's
 * WindowDatabasesTests, `WindowDatabasesModel.state(of:)`), and ⋮ › Databases' sections.
 */
class NotOpenDatabasesTest {
    private fun line(
        name: String,
        missing: Boolean = false,
        keyFile: String? = null,
        keyMissing: Boolean = false,
        colorIndex: Int = 0,
        lastOpened: Instant? = null,
    ) = DatabaseLine(
        id = DatabaseId(name),
        label = DatabaseLabel(name),
        fileName = "$name.kdbx",
        path = "/storage/emulated/0/Sync/$name.kdbx",
        folder = "Sync",
        status = null,
        reach = if (missing) Reach.NOT_FOUND else Reach.THERE,
        keyFile = keyFile?.let { KeyFileLine(it, "Keys", "/storage/emulated/0/Keys/$it", reach = if (keyMissing) Reach.NOT_FOUND else Reach.THERE) },
        isCurrent = false,
        lastOpened = lastOpened,
        modified = null,
        colorIndex = colorIndex,
    )

    private val locked = SessionState.Locked(LockReason.Initial)
    private fun failed(failure: OpenFailure) = SessionState.Locked(LockReason.UnlockFailed(failure))

    private val off = BiometricState(appEnabled = false, databaseEnabled = false, availability = BiometricAvailability.Available)
    private val on = BiometricState(appEnabled = true, databaseEnabled = true, availability = BiometricAvailability.Available, stored = true)

    private fun state(session: SessionState, line: DatabaseLine = line("Work"), fingerprint: BiometricState = off, skipped: Boolean = false) =
        NotOpenDatabases.state(session, line, fingerprint, skipped)

    // spec: databases.not-open/AC1
    @Test
    fun eachDatabaseOfTheWindowNotOpenWithItsStateInTheWindowsOrder() {
        val window = listOf(
            WindowDatabase(DatabaseId("Personal"), SessionState.Unlocked(dev.keething.domain.vault.Vault(emptyList())), off),
            WindowDatabase(DatabaseId("Work"), locked, off),
            WindowDatabase(DatabaseId("Family"), locked, on.copy(passwordDue = true, reminderDays = 14)),
            WindowDatabase(DatabaseId("Crypto"), locked, off),
            WindowDatabase(DatabaseId("Taxes"), locked, off),
        )
        val lines = listOf(
            line("Personal"), line("Work", keyFile = "work.keyx", colorIndex = 1), line("Family", colorIndex = 2),
            line("Crypto", missing = true, colorIndex = 3), line("Taxes", keyFile = "taxes.keyx", keyMissing = true, colorIndex = 4),
            line("Elsewhere"),
        )
        val notOpen = NotOpenDatabases.of(window, lines, skipped = emptySet())
        assertEquals(listOf("Work", "Family", "Crypto", "Taxes"), notOpen.map { it.line.name }, "open ones and those out of the window are not there")
        assertEquals(
            listOf(NotOpenState.Locked, NotOpenState.FingerprintPaused(14), NotOpenState.NotFound(), NotOpenState.NeedsKeyFile("taxes.keyx", null)),
            notOpen.map { it.state },
        )
        assertEquals(listOf("Locked", "Fingerprint paused", "Not found", "Needs its key file"), notOpen.map { it.state.word })
        assertEquals(
            listOf(
                "Locked · tap to type its password",
                "Fingerprint paused: type the password (every 14 days)",
                "Not found at Sync/Crypto.kdbx",
                "Needs its key file: taxes.keyx is not there",
            ),
            notOpen.map { it.say },
        )
        assertEquals(listOf(1, 2, 3, 4), notOpen.map { it.line.colorIndex }, "its colour in the list's order")
    }

    // spec: databases.not-open/AC8
    @Test
    fun eachStateHasAnIconNoOtherStateHasAndItsWordToBeReadAloud() {
        val states = listOf(
            NotOpenState.Locked, NotOpenState.Opening, NotOpenState.FingerprintPaused(14), NotOpenState.FingerprintToSetUp(null),
            NotOpenState.NotFound(Reach.NOT_FOUND), NotOpenState.NotFound(Reach.NO_ACCESS), NotOpenState.NotFound(Reach.NEEDS_FOLDER),
            NotOpenState.NeedsKeyFile("work.keyx", null), NotOpenState.CannotBeRead(OpenFailure.Damaged("a block's hash")),
            NotOpenState.WrongPassword, NotOpenState.PasswordChanged, NotOpenState.Skipped,
        )
        assertEquals(states.size, states.map { it.glyph }.toSet().size, "one icon per state")
        assertEquals(NotOpenState.Glyph.entries.toSet(), states.map { it.glyph }.toSet(), "no icon left over")
        assertEquals(states.size, states.map { it.word }.toSet().size, "one word per state, for what is read aloud")
        assertEquals(NotOpenState.FingerprintToSetUp(null).glyph, NotOpenState.FingerprintToSetUp(SetUpAgain.FINGERPRINTS_CHANGED).glyph, "set up again is the same state")
    }

    // spec: databases.not-open/AC6
    @Test
    fun theStateIsTheFirstThatApplies() {
        assertEquals(NotOpenState.Opening, state(SessionState.Unlocking, line("Work", missing = true)), "opening first")
        assertEquals(NotOpenState.NotFound(), state(failed(OpenFailure.WrongPassword), line("Work", missing = true)), "the file not there wins over an old failure")
        assertEquals(NotOpenState.Locked, state(failed(OpenFailure.Missing)), "the file is back: its old not found is not said")
        assertEquals(NotOpenState.Locked, state(failed(OpenFailure.KeyFileMissing("work.keyx")), line("Work", keyFile = "work.keyx")), "its key file is back")
        assertEquals(
            NotOpenState.NeedsKeyFile("work.keyx", null),
            state(failed(OpenFailure.WrongPassword), line("Work", keyFile = "work.keyx", keyMissing = true)),
        )
        val unusable = state(failed(OpenFailure.KeyFileUnusable("it is empty")), line("Work", keyFile = "work.keyx"))
        assertEquals(NotOpenState.NeedsKeyFile("work.keyx", "it is empty"), unusable)
        val damaged = state(failed(OpenFailure.Damaged("a block's hash")), fingerprint = on.copy(stored = false, setUpAgain = SetUpAgain.FINGERPRINTS_CHANGED))
        assertEquals(NotOpenState.CannotBeRead(OpenFailure.Damaged("a block's hash")), damaged, "what typing cannot fix before the fingerprint's verdict")
        assertEquals(NotOpenState.CannotBeRead(OpenFailure.Unsupported("KDBX 2.0", OpenFailure.Unsupported.Kind.OLD_FORMAT)), state(failed(OpenFailure.Unsupported("KDBX 2.0", OpenFailure.Unsupported.Kind.OLD_FORMAT))))

        val changed = on.copy(stored = false, setUpAgain = SetUpAgain.FINGERPRINTS_CHANGED)
        assertEquals(NotOpenState.FingerprintToSetUp(SetUpAgain.FINGERPRINTS_CHANGED), state(failed(OpenFailure.WrongPassword), fingerprint = changed, skipped = true), "the fingerprint's verdict before skipped")
        assertEquals(NotOpenState.Skipped, state(failed(OpenFailure.WrongPassword), skipped = true), "skipped before a wrong password")
        assertEquals(NotOpenState.WrongPassword, state(failed(OpenFailure.WrongPasswordOrKeyFile)))
        assertEquals(NotOpenState.WrongPassword, state(failed(OpenFailure.WrongPassword), fingerprint = on.copy(passwordDue = true)), "before the fingerprint waiting")
        assertEquals(NotOpenState.PasswordChanged, state(SessionState.Locked(LockReason.PasswordChanged)))
        assertEquals(NotOpenState.FingerprintPaused(14), state(locked, fingerprint = on.copy(passwordDue = true, reminderDays = 14)))
        assertEquals(NotOpenState.FingerprintToSetUp(null), state(locked, fingerprint = on.copy(stored = false)), "nothing stored yet")
        assertEquals(NotOpenState.Locked, state(locked, fingerprint = on.copy(stored = false, passwordless = true)), "its key file alone: nothing to store")
        assertEquals(NotOpenState.Locked, state(locked, fingerprint = on.copy(stored = false, appEnabled = false)), "the app's switch off")
        assertEquals(NotOpenState.Locked, state(locked, fingerprint = on.copy(stored = false, databaseEnabled = false)), "its own switch off")
        assertEquals(
            NotOpenState.Locked,
            state(locked, fingerprint = on.copy(stored = false, availability = BiometricAvailability.Unavailable("none enrolled"))),
            "no fingerprint on this phone",
        )
        assertEquals(NotOpenState.Locked, state(locked, fingerprint = on))
    }

    @Test
    fun eachStateSaysItAll() {
        fun say(state: NotOpenState, line: DatabaseLine = line("Work", keyFile = "work.keyx")) = NotOpenDatabase(line, state).say
        assertEquals("Opening…", say(NotOpenState.Opening))
        assertEquals("Fingerprint to set up again: the fingerprints changed", say(NotOpenState.FingerprintToSetUp(SetUpAgain.FINGERPRINTS_CHANGED)))
        assertEquals("Fingerprint to set up again: the stored password was not accepted", say(NotOpenState.FingerprintToSetUp(SetUpAgain.PASSWORD_REJECTED)))
        assertEquals("Fingerprint to set up: type its password once", say(NotOpenState.FingerprintToSetUp(null)))
        assertEquals("Its key file work.keyx cannot be used: it is empty", say(NotOpenState.NeedsKeyFile("work.keyx", "it is empty")))
        assertEquals("Cannot be read: damaged or not completely synced yet (a block's hash)", say(NotOpenState.CannotBeRead(OpenFailure.Damaged("a block's hash"))))
        assertEquals("Wrong password · tap to type it again", say(NotOpenState.WrongPassword))
        assertEquals("Its password was changed elsewhere · tap to type the new one", say(NotOpenState.PasswordChanged))
        assertEquals("Skipped · tap to type its password", say(NotOpenState.Skipped))
    }

    // spec: databases.not-open/AC2
    @Test
    fun theLineSaysNotOpenOrNotSearchedWithItsCountPastTwo() {
        assertEquals("Not open", NotOpenDatabases.title(count = 2, typing = false))
        assertEquals("Not searched", NotOpenDatabases.title(count = 1, typing = true))
        assertEquals("Not searched 3", NotOpenDatabases.title(count = 3, typing = true))
    }

    // spec: databases.several-open/AC4
    @Test
    fun theDatabasesScreenListsTheOpenOnesThoseNotOpenAndRecentTheLastOpenedFirst() {
        val personal = line("Personal")
        val work = line("Work")
        val crypto = line("Crypto", missing = true)
        val old = line("Old", lastOpened = Instant.ofEpochSecond(100))
        val club = line("Club", lastOpened = Instant.ofEpochSecond(200))
        val never = line("Never")
        val notOpen = listOf(NotOpenDatabase(work, NotOpenState.Locked), NotOpenDatabase(crypto, NotOpenState.NotFound()))
        val sections = DatabasesOverview.of(
            lines = listOf(personal, work, crypto, old, club, never),
            window = listOf(personal.id, work.id, crypto.id),
            open = listOf(personal.id),
            notOpen = notOpen,
        )
        assertEquals(listOf("Personal"), sections.open.map { it.name })
        assertEquals(listOf("Work", "Crypto"), sections.notOpen.map { it.line.name })
        assertEquals(listOf("Club", "Old", "Never"), sections.recent.map { it.name }, "the last opened first; never opened last")
        assertEquals(true, sections.canClose)
        assertEquals(false, DatabasesOverview.of(listOf(personal), listOf(personal.id), listOf(personal.id), emptyList()).canClose, "the last one stays")
        assertNull(DatabasesOverview.of(listOf(personal), listOf(personal.id), listOf(personal.id), emptyList()).recent.firstOrNull())
    }
}
