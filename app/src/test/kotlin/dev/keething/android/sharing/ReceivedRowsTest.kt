package dev.keething.android.sharing

import dev.keething.android.ui.english
import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.CustomField
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

    @Test
    fun whatTheCardMasksIsASecretRowThoughNotProtected() {
        val entry = SharedEntry(
            "Visa",
            customFields = listOf(
                CustomField.plain("CVC", "123"),
                CustomField.plain("Key", "Garden-Lamp"),
                CustomField.plain("Second", "otpauth://totp/GitHub:alex?secret=JBSWY3DPEHPK3PXP"),
                CustomField.plain("Room", "12"),
            ),
            fieldTypes = mapOf("CVC" to "card.cvc", "Key" to "password"),
        )
        val rows = receivedRows(entry, null).associateBy { it.label.english() }

        for (name in listOf("CVC", "Key", "Second")) assertTrue(rows.getValue(name).secret, "$name: masked, the eye, a sensitive copy")
        assertFalse(rows.getValue("Room").secret)
    }

    // spec: sharing.receiving/AC2
    @Test
    fun anAddressInAnAnswerIsToBeCheckedWithItsSenderNotInAnEnvelope() {
        val entry = SharedEntry(
            "Wallet",
            customFields = listOf(CustomField.plain("Pay to", "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")),
            fieldTypes = mapOf("Pay to" to "crypto.address"),
        )

        assertEquals("Check it with Anna another way before paying to it", receivedRows(entry, "Anna").single().note?.english()?.trimEnd('.'))
        assertEquals(null, receivedRows(entry, null).single().note, "an envelope's comes from whoever gave its pictures")
    }

    // spec: sharing.receiving/AC3
    @Test
    fun aRecoveryPhraseOrItsPassphraseIsNeverCopied() {
        val entry = SharedEntry(
            "Wallet",
            customFields = listOf(CustomField.protected("Phrase", "abandon ".repeat(11) + "about"), CustomField.protected("Passphrase", "Garden-Lamp"), CustomField.plain("Label", "cold")),
            fieldTypes = mapOf("Phrase" to "crypto.seed", "Passphrase" to "crypto.passphrase"),
        )
        val rows = receivedRows(entry, null).associateBy { it.label.english() }

        assertFalse(rows.getValue("Phrase").copyable)
        assertFalse(rows.getValue("Passphrase").copyable)
        assertTrue(rows.getValue("Label").copyable)
    }
}
