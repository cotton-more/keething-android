package dev.keething.android.settings

import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import dev.keething.vault.databases.StoredEntryIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** The declined type offers by refs (plan AD9; the Mac's D6 `DeclinedTypeOffers`). */
class AppSettingsTest {
    private val store = InMemorySettingsStore()
    private val personal = DatabaseId("P")
    private val work = DatabaseId("W")

    private fun p(id: String) = EntryRef(personal, id).text
    private fun w(id: String) = EntryRef(work, id).text

    @Test
    fun theLockTimeoutIsFiveMinutesByDefaultOneOfFourChoicesAndAStoredValueOutsideThemReadsAsFive() {
        assertEquals(5.minutes, AppSettings(store).lockTimeout.value, "nothing stored")
        assertEquals(listOf(1.minutes, 5.minutes, 15.minutes, 60.minutes), AppSettings.LOCK_TIMEOUTS)
        for (choice in AppSettings.LOCK_TIMEOUTS) {
            AppSettings(store).chooseLockTimeout(choice)
            assertEquals(choice, AppSettings(store).lockTimeout.value, "kept: $choice")
        }
        for (stored in listOf("7", "0", "-1", "abc", "")) {
            store.setString("lockTimeoutMinutes", stored)
            assertEquals(5.minutes, AppSettings(store).lockTimeout.value, "stored \"$stored\"")
        }
        assertFailsWith<IllegalArgumentException> { AppSettings(store).chooseLockTimeout(7.minutes) }
    }

    @Test
    fun aRequestIsValidThreeDaysByDefaultOneOfThreeChoicesAndAStoredValueOutsideThemReadsAsThree() {
        assertEquals(3.days, AppSettings(store).requestValidity.value, "nothing stored")
        assertEquals(listOf(1.days, 3.days, 7.days), AppSettings.REQUEST_VALIDITIES)
        for (choice in AppSettings.REQUEST_VALIDITIES) {
            AppSettings(store).chooseRequestValidity(choice)
            assertEquals(choice, AppSettings(store).requestValidity.value, "kept: $choice")
        }
        for (stored in listOf("2", "0", "-3", "30", "abc", "")) {
            store.setString("requestValidityDays", stored)
            assertEquals(3.days, AppSettings(store).requestValidity.value, "stored \"$stored\"")
        }
        assertFailsWith<IllegalArgumentException> { AppSettings(store).chooseRequestValidity(2.days) }
    }

    @Test
    fun notNowIsRememberedByTheEntrysRefTheSameUuidInTheOtherDatabaseIsStillOffered() {
        val settings = AppSettings(store)

        settings.dismissSuggestion(p("same"))

        assertTrue(settings.isSuggestionDismissed(p("same")))
        assertFalse(settings.isSuggestionDismissed(w("same")), "the other database's entry is another entry")
        assertEquals(p("same"), store.string("dismissedTypeSuggestions"))
        assertTrue(AppSettings(store).isSuggestionDismissed(p("same")))
    }

    @Test
    fun theDeclinedOffersFollowTheOpenDatabasesAsRecentDoes() {
        store.setString("dismissedTypeSuggestions", "same gone " + w("x") + " " + p("deleted"))
        val settings = AppSettings(store)

        settings.keepDeclined(StoredEntryIds(listOf(personal), listOf(EntryRef(personal, "same")), listed = setOf(personal, work)))

        assertTrue(settings.isSuggestionDismissed(p("same")), "an id stored before several databases: the open one's that holds it")
        assertTrue(settings.isSuggestionDismissed(w("x")), "Work is not open: kept")
        assertFalse(settings.isSuggestionDismissed(p("deleted")))
        assertEquals(setOf(p("same"), w("x")), store.string("dismissedTypeSuggestions")!!.split(' ').toSet())

        settings.keepDeclined(StoredEntryIds(listOf(personal), listOf(EntryRef(personal, "same")), listed = setOf(personal)))
        assertFalse(settings.isSuggestionDismissed(w("x")), "Work left the list")
        settings.keepDeclined(StoredEntryIds(listOf(personal), emptyList()))
        assertNull(store.string("dismissedTypeSuggestions"), "none left")
    }

    // spec: databases.one-list/AC4
    @Test
    fun theDatabaseTheLastNewEntryWentIntoIsKept() {
        assertNull(AppSettings(store).lastNewEntryDatabase)
        AppSettings(store).lastNewEntryDatabase = work
        assertEquals(work, AppSettings(store).lastNewEntryDatabase, "kept across starts: the process may end between two")
    }
}
