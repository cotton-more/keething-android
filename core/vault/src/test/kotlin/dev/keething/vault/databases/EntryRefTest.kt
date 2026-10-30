package dev.keething.vault.databases

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An entry of one of the open databases (plan AD9; keething-spec databases.one-list AC1, search.recent AC7): its ref,
 * and what the ids stored by entry (Recent, the declined type offers) become as the open databases change (the Mac's
 * `StoredEntryIDs`, D6's `DatabasesWindowTests`).
 */
class EntryRefTest {
    private val personal = DatabaseId("P")
    private val work = DatabaseId("W")

    private fun p(id: String) = EntryRef(personal, id).text
    private fun w(id: String) = EntryRef(work, id).text

    @Test
    fun aRefIsTheDatabaseAndTheEntryAndReadsBack() {
        val ref = EntryRef(DatabaseId("0C1D2E3F-AAAA-4BBB-8CCC-1234567890AB"), "q+/2bW9vZg==")
        assertEquals("0C1D2E3F-AAAA-4BBB-8CCC-1234567890AB:q+/2bW9vZg==", ref.text, "':' is in neither, '/' is in a KeePass UUID")
        assertEquals(ref, EntryRef.of(ref.text))
        assertEquals(EntryRef(work, "same"), EntryRef.of("W:same"))
    }

    @Test
    fun theSameUuidInTwoDatabasesIsTwoRefs() {
        assertEquals(2, setOf(p("same"), w("same")).size)
    }

    @Test
    fun anIdWithoutAColonIsAnEntrysOwnIdStoredBeforeSeveralDatabases() {
        val bare = EntryRef.of("q+/2bW9vZg==")
        assertEquals(DatabaseId(""), bare.database)
        assertEquals("q+/2bW9vZg==", bare.entry)
        assertEquals("q+/2bW9vZg==", bare.text)
    }

    @Test
    fun anEntryGoneFromItsOpenDatabaseIsDroppedADatabaseNotOpenKeepsItsOwn() {
        val stored = StoredEntryIds(open = listOf(personal), refs = listOf(EntryRef(personal, "bank")))
        assertEquals(p("bank"), stored.kept(p("bank")))
        assertNull(stored.kept(p("gone")), "deleted from its open database")
        assertEquals(w("same"), stored.kept(w("same")), "Work is not open: kept, its entries come back when it opens")
    }

    @Test
    fun aDatabaseNoLongerInTheListTakesItsRefsWithIt() {
        val refs = listOf(EntryRef(personal, "bank"))
        assertEquals(w("same"), StoredEntryIds(listOf(personal), refs, listed = setOf(personal, work)).kept(w("same")), "listed, not open")
        assertNull(StoredEntryIds(listOf(personal), refs, listed = setOf(personal)).kept(w("same")), "gone from the list")
        assertEquals(w("same"), StoredEntryIds(listOf(personal), refs, listed = null).kept(w("same")), "the list not known: kept")
    }

    @Test
    fun anIdStoredBeforeSeveralDatabasesBecomesTheRefOfTheFirstOpenDatabaseHoldingItElseItIsDropped() {
        val stored = StoredEntryIds(
            open = listOf(personal, work),
            refs = listOf(EntryRef(personal, "tv"), EntryRef(personal, "same"), EntryRef(work, "same"), EntryRef(work, "wbank")),
        )
        assertEquals(p("tv"), stored.kept("tv"))
        assertEquals(p("same"), stored.kept("same"), "the first open database holding it")
        assertEquals(w("wbank"), stored.kept("wbank"))
        assertNull(stored.kept("gone"))
    }

    @Test
    fun keptAsAListInItsOrderOnceEach() {
        val stored = StoredEntryIds(open = listOf(personal), refs = listOf(EntryRef(personal, "tv"), EntryRef(personal, "bank")))
        assertEquals(listOf(p("tv"), w("x"), p("bank")), stored.keep(listOf("tv", "gone", w("x"), p("tv"), "bank")))
    }
}
