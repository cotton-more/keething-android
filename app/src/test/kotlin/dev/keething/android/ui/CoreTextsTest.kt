package dev.keething.android.ui

import dev.keething.android.R
import dev.keething.card.CardText
import dev.keething.domain.text.DomainText
import dev.keething.domain.text.UserText
import dev.keething.generator.GeneratorText
import dev.keething.totp.TotpText
import dev.keething.vault.VaultText
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreTextsTest {
    /** The resources by id, strings and plurals: a key's resource is found by its name in the core's tests. */
    private val names = (R.string::class.java.fields + R.plurals::class.java.fields).associate { it.getInt(null) to it.name }

    @Test
    fun everyCoreTextMapsToTheResourceItsKeyNames() {
        for (key in CardText.entries + TotpText.entries + DomainText.entries + GeneratorText.entries + VaultText.entries) assertEquals(key.resource, names[resourceOf(key)], key.toString())
    }

    @Test
    fun aCoreTextReadsAsTheUserSeesIt() {
        assertEquals("Expires in 5 months", UserText.of(CardText.EXPIRES_IN, UserText.counted(CardText.MONTHS, 5)).ui().english())
        assertEquals("Visa •• 1111 · 02/27", UserText.Joined(listOf(UserText.plain("Visa •• 1111"), UserText.plain("02/27")), " · ").ui().english())
        assertEquals("Expires in 1 month", UserText.of(CardText.EXPIRES_IN, UserText.counted(CardText.MONTHS, 1)).ui().english())
    }
}
