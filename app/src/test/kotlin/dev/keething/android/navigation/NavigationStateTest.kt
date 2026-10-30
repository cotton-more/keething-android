package dev.keething.android.navigation

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.keething.android.entry.PasswordView
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        navigation.open(Screen.Password(p("same"), PasswordView.QR_CODE))
        navigation.open(Screen.Settings)

        navigation.keepOpen(mapOf(personal to 1))

        assertEquals(
            listOf(Screen.Entries, Screen.Card(p("same"), "ALL"), Screen.Password(p("same"), PasswordView.QR_CODE), Screen.Settings),
            navigation.backStack.toList(),
        )
        navigation.keepOpen(mapOf(personal to 3))
        assertEquals(listOf(Screen.Entries, Screen.Settings), navigation.backStack.toList(), "another session of Personal: its screens go too")
    }

    @Test
    fun theCurrentDatabaseLockingAloneWhileShownLeavesTheVaultOnTheOpenOnes() {
        val navigation = NavigationState()
        navigation.shown(2)
        navigation.query.setTextAndPlaceCursorAtEnd("git")
        navigation.open(Screen.Card(p("same"), "RESULTS"))
        navigation.open(Screen.Groups)
        navigation.open(Screen.Editor(entryId = null))

        assertEquals(personal, navigation.lockedAlone(2, mapOf(personal to 1)), "Work (session 2, shown) locked: Personal")

        assertEquals(listOf(Screen.Entries, Screen.Card(p("same"), "RESULTS")), navigation.backStack.toList(), "the current one's new entry and groups go")
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
}
