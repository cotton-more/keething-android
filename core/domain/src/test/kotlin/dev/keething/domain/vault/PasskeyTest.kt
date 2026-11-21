package dev.keething.domain.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** keething-spec card.passkey: KeePassXC's passkey fields (`BrowserService::addPasskeyToEntry`). */
class PasskeyTest {
    private val keePassXc = listOf(
        CustomField.plain("KPEX_PASSKEY_USERNAME", "alice"),
        CustomField.protected("KPEX_PASSKEY_CREDENTIAL_ID", "kLYSiHc0RiozVthp6tdefQ"),
        CustomField.protected("KPEX_PASSKEY_PRIVATE_KEY_PEM", "-----BEGIN PRIVATE KEY-----\nMIG\n-----END PRIVATE KEY-----"),
        CustomField.plain("KPEX_PASSKEY_RELYING_PARTY", "webauthn.io"),
        CustomField.protected("KPEX_PASSKEY_USER_HANDLE", "ZOwzMXc7UnUbMNun4ut4Vg"),
        CustomField.plain("KPEX_PASSKEY_FLAG_BE", "1"),
        CustomField.plain("KPEX_PASSKEY_FLAG_BS", "1"),
    )

    // spec: card.passkey/AC1
    @Test
    fun aFieldStartingWithKpexPasskeyMakesOneItsSiteTheRelyingPartyItsUserStrongboxsNameFirst() {
        assertEquals(Passkey("webauthn.io", "alice"), Passkey.of(keePassXc))
        assertEquals(
            Passkey("webauthn.io", "alice.strongbox"),
            Passkey.of(keePassXc + CustomField.plain("KPXC_PASSKEY_USERNAME", "alice.strongbox")),
        )
        assertEquals(Passkey("", ""), Passkey.of(listOf(CustomField.plain("KPEX_PASSKEY_FLAG_BE", "1"))))
        assertNull(Passkey.of(listOf(CustomField.plain("KPXC_PASSKEY_USERNAME", "alice"))), "Strongbox's user name alone is none")
        assertNull(Passkey.of(listOf(CustomField.plain("kpex_passkey_username", "alice"))), "exact case, as KeePassXC")
        assertTrue(Passkey.isField("KPEX_PASSKEY_ANYTHING_LATER") && Passkey.isField("KPXC_PASSKEY_USERNAME"))
        assertFalse(Passkey.isField("Passkey") || Passkey.isField("KPEX_PASSKE"))
    }

    // spec: card.passkey/AC6
    @Test
    fun aPasskeysFieldNameIsOneTheAppKeeps() {
        assertTrue(EntryChanges.isReserved("KPEX_PASSKEY_PRIVATE_KEY_PEM"))
        assertTrue(EntryChanges.isReserved("KPEX_PASSKEY_NEW"))
        assertTrue(EntryChanges.isReserved("KPXC_PASSKEY_USERNAME"))
        assertTrue(EntryChanges.isReserved("otp") && EntryChanges.isReserved("Password"))
        assertFalse(EntryChanges.isReserved("Passkey"))
    }

    @Test
    fun anEntryWithAPasskeyIsNoNote() {
        val entry = Entry("n", notes = "Only a text", passkeyFields = keePassXc)
        assertFalse(entry.isNote)
        assertEquals(Passkey("webauthn.io", "alice"), entry.passkey)
        assertNull(Entry("m").passkey)
    }
}
