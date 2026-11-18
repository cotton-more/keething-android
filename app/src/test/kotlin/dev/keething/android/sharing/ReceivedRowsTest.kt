package dev.keething.android.sharing

import dev.keething.android.ui.english
import dev.keething.domain.secret.Secret
import dev.keething.sharing.payload.SharedEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A received entry's rows (keething-spec sharing.receiving AC9): Notes sent protected are a secret, masked until shown. */
class ReceivedRowsTest {
    // spec: sharing.receiving/AC9
    @Test
    fun protectedNotesAreASecretRowAndANotesTextIsCalledNote() {
        val routerAdmin = receivedRows(SharedEntry("Router admin", password = Secret.of("pw"), notes = "Reset button", notesProtected = true), null)
        val notes = routerAdmin.single { it.label.english() == "Notes" }
        assertTrue(notes.secret, "masked, with the eye, copied as a secret")
        assertTrue(notes.text, "shown as text once revealed")

        val note = receivedRows(SharedEntry("Films to watch", notes = "Dune\nArrival", notesProtected = true, entryType = " Note "), null).single()
        assertEquals("Note", note.label.english())
        assertTrue(note.secret)

        // A note by its fields alone (a content 01 has no kind) is called Note too, as its card will call it.
        val byFields = receivedRows(SharedEntry("Shopping", notes = "milk"), null).single()
        assertEquals("Note", byFields.label.english())
        assertFalse(byFields.secret)
        assertEquals("Notes", receivedRows(SharedEntry("Shop", username = "me", notes = "milk"), null).single { !it.secret && it.value == "milk" }.label.english())
        assertEquals("Notes", receivedRows(SharedEntry("Shopping", notes = "milk", entryType = "checklist"), null).single().label.english())
    }
}
