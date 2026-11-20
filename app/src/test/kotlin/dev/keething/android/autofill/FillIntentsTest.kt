package dev.keething.android.autofill

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FillIntentsTest {
    private val intents = FillIntents(kept = 3)

    @Test
    fun anIntentAnswersWithoutAScreenOnce() {
        val id = intents.issue()
        assertNotEquals(id, intents.issue(), "each its own id")
        assertTrue(intents.answersAtOnce(id))
        assertFalse(intents.answersAtOnce(id), "started again: a screen")
    }

    @Test
    fun anUnknownIdOrNoneShowsAScreen() {
        intents.issue()
        assertFalse(intents.answersAtOnce("made-up"))
        assertFalse(intents.answersAtOnce(null))
        // After a restart the ids are gone.
        assertFalse(FillIntents().answersAtOnce(intents.issue()))
    }

    @Test
    fun onlyTheLatestIdsAreKept() {
        val oldest = intents.issue()
        val kept = List(3) { intents.issue() }
        assertFalse(intents.answersAtOnce(oldest))
        assertTrue(kept.all(intents::answersAtOnce))
    }
}
