package dev.keething.android.sharing

import dev.keething.android.ui.english
import dev.keething.domain.vault.CustomField
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

    @Test
    fun whatTheCardMasksIsMaskedThoughNotProtected() {
        val card = Entry(
            "c",
            title = "Visa",
            customFields = listOf(
                CustomField.plain("CVC", "123"),
                CustomField.plain("Key", "Garden-Lamp"),
                CustomField.plain("Second", "otpauth://totp/GitHub:alex?secret=JBSWY3DPEHPK3PXP"),
                CustomField.plain("Room", "12"),
            ),
            fieldTypes = mapOf("CVC" to "card.cvc", "Key" to "password"),
        )

        for (name in listOf("CVC", "Key", "Second")) assertEquals("••••••••", EntryPart.Field(name).preview(card).english(), name)
        assertEquals("12", EntryPart.Field("Room").preview(card).english())
    }
}
