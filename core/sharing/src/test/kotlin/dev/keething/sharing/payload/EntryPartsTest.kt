package dev.keething.sharing.payload

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.TotpChange
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryPartsTest {
    private val code = TotpConfig(Secret.of("12345678901234567890"), digits = 8)
    private val entry = Entry(
        id = "id",
        title = "Amazon",
        username = "alex",
        password = Secret.of("pw"),
        url = "https://amazon.de",
        notes = "",
        tags = listOf("shopping"),
        customFields = listOf(CustomField.protected("PIN", "4711"), CustomField.plain("Valid until", "2027-03-31")),
        fieldTypes = mapOf("PIN" to "card.pin", "Valid until" to "until"),
        totp = code,
        expires = true,
        expiryTime = Instant.parse("2027-01-01T00:00:00Z"),
    )

    @Test
    fun thePartsAreThoseWithAValueTheCodeLast() {
        assertEquals(
            listOf(EntryPart.Username, EntryPart.Password, EntryPart.Website, EntryPart.Field("PIN"), EntryPart.Field("Valid until"), EntryPart.OneTimeCode),
            Sending.parts(entry),
        )
        assertEquals(emptyList(), Sending.parts(Entry("x", title = "Only a title")))
    }

    @Test
    fun byDefaultEverythingButTheCodeGoesAsCopies() {
        val shared = Sending.shared(entry, Sending.leftOutByDefault(entry))

        assertEquals("Amazon", shared.title)
        assertEquals("alex", shared.username)
        assertEquals(Secret.of("pw"), shared.password)
        assertEquals(entry.customFields, shared.customFields)
        assertEquals(entry.fieldTypes, shared.fieldTypes)
        assertEquals(listOf("shopping"), shared.tags)
        assertEquals(entry.expiryTime, shared.expires)
        assertNull(shared.totp)
        shared.wipe()
        assertFalse(entry.password.isWiped, "the vault's own secrets stay")
    }

    @Test
    fun aCodeSecretKeptInCustomFieldsIsLeftOutByDefaultAsTheCodeIs() {
        val keePass = Entry("k", title = "KeePass code", customFields = listOf(CustomField.protected("TimeOtp-Secret", "12345678"), CustomField.plain("HmacOtp-Counter", "3"), CustomField.plain("Note", "x")))

        assertEquals(setOf(EntryPart.Field("TimeOtp-Secret"), EntryPart.Field("HmacOtp-Counter")), Sending.leftOutByDefault(keePass))
        assertEquals(setOf(EntryPart.OneTimeCode), Sending.leftOutByDefault(entry))
        assertTrue(Sending.isSecondStep(EntryPart.Field("timeotp-secret-hex")))
        assertFalse(Sending.isSecondStep(EntryPart.Field("Note")))
    }

    @Test
    fun partsLeftOutAreNotSentAndTheirTypesNeither() {
        val shared = Sending.shared(entry, setOf(EntryPart.Password, EntryPart.Field("PIN"), EntryPart.Username))

        assertTrue(shared.password.isEmpty())
        assertEquals("", shared.username)
        assertEquals(listOf("Valid until"), shared.customFields.map { it.name })
        assertEquals(mapOf("Valid until" to "until"), shared.fieldTypes)
        assertEquals(code, Sending.shared(entry, emptySet()).totp)
        assertNull(Sending.shared(entry.copy(expires = false), emptySet()).expires)
    }

    @Test
    fun aReceivedEntryBecomesAllItsFieldsAndItsCodeAsAnOtpLink() {
        val changes = Receiving.changes(Sending.shared(entry, emptySet()))

        assertEquals("Amazon", changes.title)
        assertEquals(Secret.of("pw"), changes.password)
        assertEquals(entry.customFields, changes.customFields)
        assertEquals(entry.fieldTypes, changes.fieldTypes)
        assertEquals(EntryChanges.Expiry(true, entry.expiryTime), changes.expiry)
        val totp = assertIs<TotpChange.Set>(changes.totp)
        assertEquals(code, totp.config)
        assertTrue(totp.uri.startsWith("otpauth://totp/Amazon:alex?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&period=30&digits=8"))
        assertNull(Receiving.changes(SharedEntry("x")).expiry)
    }

    @Test
    fun aReceivedEntryIsTaggedReceivedOnceBesidesTheSendersTags() {
        assertEquals(listOf("shopping", "received"), Receiving.changes(Sending.shared(entry, emptySet())).tags)
        assertEquals(listOf("received"), Receiving.changes(SharedEntry("x")).tags)
        assertEquals(listOf("Received", "a"), Receiving.changes(SharedEntry("x", tags = listOf("Received", "a"))).tags)
    }
}
