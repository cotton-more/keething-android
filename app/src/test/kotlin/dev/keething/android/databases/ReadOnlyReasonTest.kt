package dev.keething.android.databases

import dev.keething.android.ui.english
import dev.keething.domain.vault.Vault
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SaveRoom
import dev.keething.vault.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An open database that is read-only, and why (keething-spec databases.read-only-mark). */
class ReadOnlyReasonTest {
    private fun unlocked(room: SaveRoom = SaveRoom.Enough, viewOnly: Boolean = false) =
        SessionState.Unlocked(Vault(emptyList()), viewOnly = viewOnly, room = room)

    @Test
    fun oneMarkForBothReasonsWhySaidInWordsAndReadAloudAsReadOnly() {
        assertEquals(ReadOnlyReason.TOO_LARGE, ReadOnlyReason.of(unlocked(SaveRoom.TooLarge(alone = true))))
        assertEquals(ReadOnlyReason.TOO_LARGE, ReadOnlyReason.of(unlocked(SaveRoom.TooLarge(alone = false))))
        assertEquals(ReadOnlyReason.KDBX3, ReadOnlyReason.of(unlocked(viewOnly = true)))
        assertEquals(ReadOnlyReason.TOO_LARGE, ReadOnlyReason.of(unlocked(SaveRoom.TooLarge(alone = true), viewOnly = true)), "updating it would not save either")
        assertEquals("Read only: too large for this phone", ReadOnlyReason.TOO_LARGE.say.english())
        assertEquals("Read only: KDBX 3.1, Edit offers to update it", ReadOnlyReason.KDBX3.say.english())
        assertEquals("Read only", ReadOnlyReason.WORD.english())
    }

    @Test
    fun theMarkGoesAsSoonAsItCanBeChangedAgain() {
        assertNull(ReadOnlyReason.of(unlocked(SaveRoom.Near)), "changeable again, the others locked")
        assertNull(ReadOnlyReason.of(unlocked()), "a KDBX 3.1 one updated")
        assertNull(ReadOnlyReason.of(SessionState.Locked(LockReason.Manual)), "not open: its state's icon instead")
        assertNull(ReadOnlyReason.of(null))
    }

    @Test
    fun theOverviewKnowsTheOpenOnesThatAreReadOnlyInTheWindowsOrder() {
        val large = line("Large")
        val old = line("Old")
        val gone = line("Gone")
        val overview = DatabasesOverview.of(
            lines = listOf(large, old, gone),
            window = listOf(large.id, old.id),
            open = listOf(large.id, old.id),
            notOpen = emptyList(),
            readOnly = listOf(
                ReadOnlyDatabase(gone, ReadOnlyReason.TOO_LARGE, besideOthers = false),
                ReadOnlyDatabase(old, ReadOnlyReason.KDBX3, besideOthers = false),
                ReadOnlyDatabase(large, ReadOnlyReason.TOO_LARGE, besideOthers = true),
            ),
        )
        assertEquals(listOf(large.id, old.id), overview.readOnly.map { it.id })
        assertEquals(ReadOnlyReason.KDBX3, overview.readOnly(old.id))
        assertNull(overview.readOnly(gone.id))
    }

    @Test
    fun onTheLineItsSheetSaysWhyAndOffersWhatMakesItChangeable() {
        val beside = ReadOnlyDatabase.of(line("Large"), unlocked(SaveRoom.TooLarge(alone = false)))!!
        assertTrue(beside.besideOthers, "Lock the others offered")
        assertEquals(
            "Read only: with the other databases open, too large for this phone to save safely. Lock them to change it.",
            beside.say.english(),
        )
        val alone = ReadOnlyDatabase.of(line("Large"), unlocked(SaveRoom.TooLarge(alone = true)))!!
        assertFalse(alone.besideOthers, "locking the others would not help")
        assertEquals(
            "Read only: too large for this phone to save safely. To change it, keep large files outside it or split it into two databases.",
            alone.say.english(),
        )
        val kdbx3 = ReadOnlyDatabase.of(line("Old"), unlocked(viewOnly = true))!!
        assertEquals(ReadOnlyReason.KDBX3, kdbx3.reason, "Update to KDBX 4… offered")
        assertEquals("Read only: a KDBX 3.1 database. Keething changes databases only in KDBX 4: update it to change it.", kdbx3.say.english())
        assertNull(ReadOnlyDatabase.of(line("Large"), unlocked(SaveRoom.Near)), "changeable: it leaves the line")
    }

    private fun line(name: String) = DatabaseLine(
        id = DatabaseId(name),
        label = DatabaseLabel(name),
        fileName = "$name.kdbx",
        path = "/storage/emulated/0/Sync/$name.kdbx",
        folder = "Sync",
        status = null,
        reach = Reach.THERE,
        keyFile = null,
        isCurrent = false,
        lastOpened = null,
        modified = null,
        colorIndex = 0,
    )
}
