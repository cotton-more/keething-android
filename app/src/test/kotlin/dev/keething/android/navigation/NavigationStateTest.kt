package dev.keething.android.navigation

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.keething.android.entry.PasswordView
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The screens over several open databases (plan AD9): one locking alone takes its entries' screens with it, the others'
 * stay; the current one locking alone while another is open leaves the vault on the open ones.
 */
class NavigationStateTest {
    private val personal = DatabaseId("P")
    private val work = DatabaseId("W")

    private fun p(id: String) = EntryRef(personal, id).text
    private fun w(id: String) = EntryRef(work, id).text

    @Test
    fun aDatabaseThatLockedAloneTakesItsEntriesScreensTheOthersStay() {
        val navigation = NavigationState()
        navigation.keepOpen(mapOf(personal to 1, work to 2))
        navigation.open(Screen.Card(p("same"), "ALL"))
        navigation.open(Screen.History(w("same")))
        navigation.open(Screen.Editor(w("x")))
        navigation.open(Screen.Editor(entryId = null, database = work.value))
        navigation.open(Screen.Password(p("same"), PasswordView.QR_CODE))
        navigation.open(Screen.Settings)

        navigation.keepOpen(mapOf(personal to 1))

        assertEquals(
            listOf(
                Screen.Entries, Screen.Card(p("same"), "ALL"), Screen.Editor(entryId = null, database = work.value),
                Screen.Password(p("same"), PasswordView.QR_CODE), Screen.Settings,
            ),
            navigation.backStack.toList(),
            "a new entry's draft is its editor's to close: it may have moved to another database (databases.one-list AC6)",
        )
        navigation.keepOpen(mapOf(personal to 3))
        assertEquals(
            listOf(Screen.Entries, Screen.Editor(entryId = null, database = work.value), Screen.Settings),
            navigation.backStack.toList(),
            "another session of Personal: its screens go too",
        )
    }

    @Test
    fun theCurrentDatabaseLockingAloneWhileShownLeavesTheVaultOnTheOpenOnes() {
        val navigation = NavigationState()
        navigation.shown(2)
        navigation.query.setTextAndPlaceCursorAtEnd("git")
        navigation.open(Screen.Card(p("same"), "RESULTS"))
        navigation.open(Screen.Groups)
        navigation.open(Screen.ShareReceived("code"))
        navigation.open(Screen.Editor(entryId = null, database = personal.value))

        assertEquals(personal, navigation.lockedAlone(2, mapOf(personal to 1)), "Work (session 2, shown) locked: Personal")

        assertEquals(
            listOf(
                Screen.Entries, Screen.Card(p("same"), "RESULTS"), Screen.Groups, Screen.ShareReceived("code"),
                Screen.Editor(entryId = null, database = personal.value),
            ),
            navigation.backStack.toList(),
            "Groups & tags stays (its block goes); a new entry and a received one follow their own database (plan AD11)",
        )
        assertEquals("git", navigation.query.text.toString(), "the search stays")
        assertEquals(1, navigation.sessionNumber, "the screens are Personal's now")
    }

    @Test
    fun aLockScreenThatWasNotShowingTheVaultOrWithNothingElseOpenIsNoLoneLock() {
        val navigation = NavigationState()
        navigation.shown(1)
        assertNull(navigation.lockedAlone(2, mapOf(personal to 1)), "another database chosen: its own lock screen shows")
        assertNull(navigation.lockedAlone(1, emptyMap()), "nothing else open: the lock screen")
    }

    // spec: databases.not-open/AC3
    @Test
    fun aLockScreenAskedForFromTheVaultKeepsTheSearchForWhenItOpensUnlessEverythingLocks() {
        val navigation = NavigationState()
        navigation.shown(1)
        navigation.query.setTextAndPlaceCursorAtEnd("git")
        navigation.open(Screen.Card(p("same"), "RESULTS"))

        navigation.ask(2)
        assertEquals(2, navigation.sessionNumber, "the screens are Work's already: nothing resets when it becomes current")
        assertEquals(listOf(Screen.Entries), navigation.backStack.toList(), "its lock screen over the list")
        navigation.lockScreen(2, anyOpen = true)
        assertEquals("git", navigation.query.text.toString(), "the search stays for when it opens (Not searched → its entries)")
        navigation.shown(2)
        navigation.lockScreen(2, anyOpen = true)
        assertEquals("", navigation.query.text.toString(), "once shown it is no longer asked for: its next lock screen resets")

        navigation.query.setTextAndPlaceCursorAtEnd("git")
        navigation.ask(3)
        navigation.lockScreen(3, anyOpen = false)
        assertEquals("", navigation.query.text.toString(), "Lock all meanwhile: nothing lingers")
    }

    @Test
    fun aLockScreenAskedForFromGroupsAndTagsGoesBackThere() {
        val navigation = NavigationState()
        navigation.shown(1)
        navigation.open(Screen.Groups)

        navigation.ask(2)
        navigation.lockScreen(2, anyOpen = true)
        assertEquals(listOf(Screen.Entries, Screen.Groups), navigation.backStack.toList(), "Groups & tags is every open database's")
    }

    // What the app shows over its databases (plan AD13; the Mac's WindowScreenTests)

    private val family = DatabaseId("F")

    // spec: databases.chooser/AC1
    // spec: databases.chooser/AC2
    @Test
    fun severalInTheWindowNothingOpenTheChooserOneOpenTheVaultAllLockedTheChooserAgain() {
        val navigation = NavigationState()
        assertEquals(Shown.CHOOSER, navigation.screenFor(personal, 1, open = emptyList(), windowSize = 2))
        navigation.showing(Shown.CHOOSER)
        assertEquals(Shown.VAULT, navigation.screenFor(personal, 1, open = listOf(personal), windowSize = 2))
        navigation.shown(1)
        navigation.showing(Shown.VAULT)

        navigation.locked()
        navigation.lockScreen(1, anyOpen = false)
        assertEquals(Shown.CHOOSER, navigation.screenFor(personal, 1, open = emptyList(), windowSize = 2), "Lock all: the chooser")
    }

    // spec: databases.chooser/AC2
    @Test
    fun oneDatabaseInTheWindowWithNothingOpenShowsItsLockScreenWithoutBack() {
        val navigation = NavigationState()
        assertEquals(Shown.LOCK_SCREEN, navigation.screenFor(personal, 1, open = emptyList(), windowSize = 1))
        assertNull(navigation.lockScreenBack(personal, open = emptyList(), windowSize = 1))
    }

    @Test
    fun aLockScreenAskedForWithNothingOpenAndSeveralInTheWindowGoesBackToTheChooser() {
        // The lock screen's sheet, Open other… on it or in the chooser: the Mac's "Back to the chooser".
        val navigation = NavigationState()
        navigation.lockAsked = work
        assertEquals(Shown.LOCK_SCREEN, navigation.screenFor(work, 2, open = emptyList(), windowSize = 2))
        assertEquals(LockBack.CHOOSER, navigation.lockScreenBack(work, open = emptyList(), windowSize = 2))

        navigation.backToChooser()
        assertEquals(Shown.CHOOSER, navigation.screenFor(work, 2, open = emptyList(), windowSize = 2))

        navigation.lockAsked = work
        assertEquals(Shown.CHOOSER, navigation.screenFor(personal, 1, open = emptyList(), windowSize = 2), "another made current since")
        navigation.locked()
        assertEquals(Shown.CHOOSER, navigation.screenFor(work, 2, open = emptyList(), windowSize = 2), "a lock forgets the lock screen asked")
    }

    @Test
    fun aLockScreenWithOthersOpenGoesBackToThemAsBefore() {
        val navigation = NavigationState()
        navigation.showing(Shown.VAULT)
        assertEquals(Shown.LOCK_SCREEN, navigation.screenFor(work, 2, open = listOf(personal), windowSize = 2))
        assertEquals(LockBack.VAULT, navigation.lockScreenBack(work, open = listOf(personal), windowSize = 2))
        navigation.showing(Shown.LOCK_SCREEN)
        assertNull(navigation.openedToShow(work, open = listOf(personal)), "asked for: it stays")
    }

    // spec: databases.chooser/AC5
    @Test
    fun theRunShowsWhileItGoesAlsoOverWhatItOpened() {
        val navigation = NavigationState()
        assertEquals(Shown.RUN, navigation.screenFor(personal, 1, open = emptyList(), windowSize = 3, running = true))
        assertEquals(Shown.RUN, navigation.screenFor(personal, 1, open = listOf(personal), windowSize = 3, running = true))
    }

    // spec: databases.chooser/AC1
    @Test
    fun afterTheChooserOrTheRunTheVaultShowsOnTheFirstDatabaseThatOpened() {
        val navigation = NavigationState()
        navigation.showing(Shown.CHOOSER)
        assertEquals(Shown.CHOOSER, navigation.screenFor(personal, 1, open = listOf(work, family), windowSize = 3), "until it is current")
        assertEquals(work, navigation.openedToShow(personal, open = listOf(work, family)))
        assertEquals(Shown.VAULT, navigation.screenFor(work, 2, open = listOf(work, family), windowSize = 3))

        navigation.showing(Shown.RUN)
        assertEquals(family, navigation.openedToShow(personal, open = listOf(family)))
        assertNull(navigation.openedToShow(family, open = listOf(family)), "the current one is open")
    }

    @Test
    fun aDatabaseOpeningWithNothingElseOpenShowsItsLockScreenButNotWhileTheFingerprintOpensSeveral() {
        // A new database made from the chooser opens at once: its lock screen says "Opening…".
        val navigation = NavigationState()
        assertEquals(Shown.LOCK_SCREEN, navigation.screenFor(personal, 1, open = emptyList(), windowSize = 2, currentOpening = true))
        assertEquals(
            Shown.CHOOSER,
            navigation.screenFor(personal, 1, open = emptyList(), windowSize = 2, currentOpening = true, fingerprintRunning = true),
        )
    }

    @Test
    fun theCurrentDatabaseLockedAloneWhileItsVaultShowedLeavesTheVaultAsBefore() {
        val navigation = NavigationState()
        navigation.shown(1)
        navigation.showing(Shown.VAULT)
        assertEquals(Shown.VAULT, navigation.screenFor(personal, 1, open = listOf(work), windowSize = 2))
    }

    // Found by AD13's review

    @Test
    fun theLockScreenAskedGoesOnceItsDatabaseOpensSoLockingItAloneLaterShowsTheChooser() {
        val navigation = NavigationState()
        navigation.lockAsked = work
        navigation.showing(Shown.VAULT)
        assertNull(navigation.lockAsked)
        assertEquals(Shown.CHOOSER, navigation.screenFor(work, 2, open = emptyList(), windowSize = 2))
    }

    @Test
    fun aDatabaseOpeningByItselfHasNoBackOnlyALockScreenAskedForHas() {
        // A new database made from the chooser says "Opening …" for a moment: Back would go nowhere.
        val navigation = NavigationState()
        assertNull(navigation.lockScreenBack(personal, open = emptyList(), windowSize = 2))
        navigation.lockAsked = personal
        assertEquals(LockBack.CHOOSER, navigation.lockScreenBack(personal, open = emptyList(), windowSize = 2))
    }

    @Test
    fun afterTheChooserOrTheRunTheVaultShowsAtOnceWithoutAFrameOfALockScreen() {
        // The screens were reset at the lock that brought the chooser: nothing of another database's is left to show.
        val navigation = NavigationState()
        navigation.sessionNumber = 1
        navigation.showing(Shown.VAULT)
        assertFalse(navigation.vaultReady(2), "another database's screens: reset first")
        navigation.showing(Shown.CHOOSER)
        assertTrue(navigation.vaultReady(2))
        navigation.showing(Shown.RUN)
        assertTrue(navigation.vaultReady(2))
        navigation.showing(Shown.LOCK_SCREEN)
        assertTrue(navigation.vaultReady(1))
    }
}
