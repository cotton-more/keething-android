package dev.keething.android.sharing

import dev.keething.android.ui.english
import dev.keething.domain.vault.Entry
import dev.keething.sharing.payload.EntryPart
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the review's rows show of the parts being sent: never a protected value (SPEC §10.1). */
class PartPreviewTest {
    @Test
    fun protectedNotesAreMaskedUnprotectedOnesShowTheirFirstLine() {
        assertEquals("Dune", EntryPart.Notes.preview(Entry("n", title = "Films", notes = "Dune\nArrival")).english())
        assertEquals("••••••••", EntryPart.Notes.preview(Entry("p", title = "Films", notes = "Zanzibar\nArrival", notesProtected = true)).english())
    }
}
