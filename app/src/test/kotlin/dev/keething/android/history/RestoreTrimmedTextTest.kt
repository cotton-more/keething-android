package dev.keething.android.history

import dev.keething.domain.vault.Entry
import dev.keething.vault.opening.EntryVersions
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What Go back to this version? says the history loses (keething-spec edit.history-restore AC2; the canvas "Restoring a version"). */
class RestoreTrimmedTextTest {
    /** [count] earlier versions, the one at index i dated i. */
    private fun versions(count: Int, max: Int) = EntryVersions(
        current = Entry("e"),
        history = List(count) { Entry("e", lastModified = Instant.ofEpochSecond(it.toLong())) },
        maxItems = max,
    )

    private val date: (Instant?) -> String = { "day ${it?.epochSecond}" }

    @Test
    fun nothingGoesWhileTheHistoryHasRoomOrKeepsAnyNumber() {
        assertNull(trimmedText(versions(3, 10), 1, date))
        assertNull(trimmedText(versions(3, 4), 0, date), "the current one fills the last place")
        assertNull(trimmedText(versions(30, -1), 0, date))
    }

    @Test
    fun aFullHistoryLosesItsOldestOrTheVersionRestoredLeavesIt() {
        assertEquals("This database keeps 3 earlier versions, so the oldest, from day 0, goes.", trimmedText(versions(3, 3), 2, date))
        assertEquals(
            "This database keeps 3 earlier versions: this one leaves the history as it becomes the current one.",
            trimmedText(versions(3, 3), 0, date),
        )
    }

    @Test
    fun aHistoryOverItsLimitLosesSeveral() {
        assertEquals("This database keeps 2 earlier versions, so the 3 oldest go, the newest of them from day 2.", trimmedText(versions(4, 2), 3, date))
        assertEquals(
            "This database keeps 1 earlier version, so the 4 oldest go, the newest of them from day 3. This one becomes the current one.",
            trimmedText(versions(4, 1), 1, date),
        )
    }
}
