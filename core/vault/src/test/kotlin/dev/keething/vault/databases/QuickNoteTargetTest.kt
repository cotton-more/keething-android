package dev.keething.vault.databases

import dev.keething.vault.biometric.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where a quick note goes (SPEC §6.4; keething-spec app.quick-note AC1; the Mac's `QuickNoteTests`, "Where it goes"). */
class QuickNoteTargetTest {
    @Test
    fun theDatabaseSetWhileItIsInTheListElseTheCurrentOne() {
        var ids = 0
        val list = DatabaseList(MapSettings(), makeId = { DatabaseId("db-${++ids}") })
        assertNull(QuickNoteTarget.resolve(null, list.state.value), "no database at all")
        assertNull(QuickNoteTarget.resolve(DatabaseId("gone"), list.state.value), "set, but no database at all")

        val personal = list.add("/a/Personal.kdbx")
        val work = list.add("/a/Work.kdbx")
        val family = list.add("/a/Family.kdbx")
        list.setCurrent(work.id)
        assertEquals(work.id, QuickNoteTarget.resolve(null, list.state.value), "unset: the current one")
        assertEquals(family.id, QuickNoteTarget.resolve(family.id, list.state.value), "set: that one, current or not")
        assertEquals(personal.id, QuickNoteTarget.resolve(personal.id, list.state.value))

        list.remove(family.id)
        assertEquals(work.id, QuickNoteTarget.resolve(family.id, list.state.value), "set to one no longer in the list: the current one")
        assertEquals(work.id, QuickNoteTarget.resolve(DatabaseId("never"), list.state.value))
    }

    @Test
    fun noCurrentOneTheListsFirst() {
        val personal = DatabaseRecord(DatabaseId("P"), "/a/Personal.kdbx")
        val work = DatabaseRecord(DatabaseId("W"), "/a/Work.kdbx")
        assertEquals(personal.id, QuickNoteTarget.resolve(null, Databases(listOf(personal, work), currentId = null)))
        assertEquals(personal.id, QuickNoteTarget.resolve(null, Databases(listOf(personal, work), currentId = DatabaseId("gone"))), "a current one not in the list")
        assertEquals(work.id, QuickNoteTarget.resolve(work.id, Databases(listOf(personal, work), currentId = null)))
    }
}
