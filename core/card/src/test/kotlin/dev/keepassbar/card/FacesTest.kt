package dev.keepassbar.card

import dev.keepassbar.domain.vault.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The faces of payment cards and Wi-Fi networks (SPEC §6.2), their rows in the list, and the offer to type them (SPEC §6.3). */
class FacesTest {
    private val cardTypes = "Card number = card.number\nCardholder = card.holder\nExpiry = card.expiry\nCVC = card.cvc\nPIN = card.pin\nType = card.type"

    /** As the demo database and a hand-made entry have it: the CVC first, protected. */
    private val cardFields = listOf(
        field("CVC", "123", protected = true), field("Card number", "4111111111111111"), field("Cardholder", "Alex Morgan"),
        field("Expiry", "2027-02"), field("PIN", "0000", protected = true), field("Type", "visa"), field("Bank hotline", "+49 30 1234 5678"),
    )
    private val card = Entry("c", title = "Visa Card", customFields = cardFields)

    private val wifiTypes = "SSID = wifi.ssid\nSecurity = wifi.security\nWi-Fi password = wifi.password"
    private val wifiFields = listOf(field("SSID", "FRITZ!Box 7590 KX"), field("Security", "WPA2"), field("Wi-Fi password", "8823 4412", protected = true))
    private val wifi = Entry("w", title = "Home Wi-Fi", customFields = wifiFields)

    private fun build(entry: Entry, types: String) = EntryCardBuilder.build(typed(entry, types), now, de)

    // The face

    @Test
    fun theCardsFaceShowsNumberHolderExpiryCvcAndTypeTheRestAreRowsUnderIt() {
        val built = build(card, cardTypes)
        val p = built.paymentCard!!
        assertEquals("custom-1", p.numberRowId)
        assertEquals("custom-2", p.holderRowId)
        assertEquals("custom-3", p.expiryRowId)
        assertEquals("custom-0", p.cvcRowId)
        assertEquals("custom-5", p.typeRowId, "a type naming a known network is the face's brand")
        val section = built.sections.first { it.kind is CardSection.Kind.PaymentCard }
        assertEquals(listOf("PIN"), section.rows.filter { it.id !in p.faceRowIds }.map { it.label })
        assertEquals(listOf("Bank hotline"), built.sections.first { it.kind == CardSection.Kind.Custom }.rows.map { it.label })
    }

    @Test
    fun whatTheFaceCannotShowStaysARow() {
        val fields = cardFields.toMutableList().apply {
            this[2] = field("Cardholder", "Alex Morgan", protected = true)
            this[3] = field("Expiry", "soon")
            this[5] = field("Type", "Visa Debit")
        }
        val built = build(card.copy(customFields = fields), cardTypes)
        val p = built.paymentCard!!
        assertNull(p.holderRowId, "a protected holder is not read")
        assertNull(p.expiryRowId, "no date: no expiry on the face")
        assertNull(p.typeRowId, "the number names the network; the type says more")
        val rows = built.sections.first { it.kind is CardSection.Kind.PaymentCard }.rows.filter { it.id !in p.faceRowIds }
        assertEquals(listOf("Cardholder", "Expiry", "PIN", "Type"), rows.map { it.label })
    }

    @Test
    fun aTypeIsTheBrandWhenTheNumberIsProtected() {
        val fields = cardFields.toMutableList().apply { this[1] = field("Card number", "4111111111111111", protected = true); this[5] = field("Type", "Girocard") }
        val p = build(card.copy(customFields = fields), cardTypes).paymentCard!!
        assertEquals("Girocard", p.brand)
        assertEquals("custom-5", p.typeRowId)
    }

    @Test
    fun theNetworksFaceShowsNameSecurityAndPassword() {
        val network = build(wifi, wifiTypes).wifi!!
        assertEquals("WPA2", network.securityLabel)
        assertEquals(setOf("custom-0", "custom-1", "custom-2"), network.faceRowIds)
        val open = build(Entry("o", customFields = listOf(field("SSID", "Cafe"), field("Security", "WPA2"))), wifiTypes).wifi!!
        assertEquals("Open", open.securityLabel, "no password: anyone may join, whatever the field says")
        assertEquals(true, open.isOpen)
    }

    // The list

    @Test
    fun theListSaysWhichCardOrNetworkItIs() {
        assertEquals(EntryFace.Card(CardBrand.VISA, "Visa", "1111", CalendarDate(2027, 2)), EntryFace.of(typed(card, cardTypes)))
        assertEquals("Visa •• 1111 · 02/27", EntryFace.of(typed(card, cardTypes))!!.subtitle)
        val hidden = card.copy(customFields = cardFields.toMutableList().apply { this[1] = field("Card number", "4111111111111111", protected = true) })
        assertEquals("Visa · 02/27", EntryFace.of(typed(hidden, cardTypes))!!.subtitle, "a protected number: the type's brand, no digits")
        assertEquals("WPA2 · FRITZ!Box 7590 KX", EntryFace.of(typed(wifi, wifiTypes))!!.subtitle)
        assertNull(EntryFace.of(card), "no types: no face")
        assertNull(EntryFace.of(typed(card, "Bank hotline = text")))
    }

    // The offer

    @Test
    fun cardFieldsWithoutTypesAreOfferedTheirs() {
        val offer = TypeSuggestion.of(card)!!
        assertEquals(TypeSuggestion.Kind.PAYMENT_CARD, offer.kind)
        assertEquals(
            mapOf(
                "Card number" to FieldType.CARD_NUMBER, "Cardholder" to FieldType.CARD_HOLDER, "Expiry" to FieldType.CARD_EXPIRY,
                "CVC" to FieldType.CARD_CVC, "PIN" to FieldType.CARD_PIN, "Type" to FieldType.CARD_TYPE,
            ),
            offer.types,
        )
        val saved = card.copy(fieldTypes = offer.changes(card).fieldTypes!!)
        assertEquals(EntryCardBuilder.build(typed(card, cardTypes), now, de).paymentCard, EntryCardBuilder.build(saved, now, de).paymentCard)
        assertNull(TypeSuggestion.of(saved), "typed: nothing more to offer")
    }

    @Test
    fun onePasswordsNamesAndSectionsCount() {
        val imported = Entry(
            "i",
            customFields = listOf(
                field("cardholder name", "Jane Example"), field("number", "4111 1111 1111 1111"), field("expiry date", "202702"),
                field("verification number", "123", protected = true), field("Additional Details_PIN", "0000", protected = true),
            ),
        )
        assertEquals(
            listOf(FieldType.CARD_NUMBER, FieldType.CARD_HOLDER, FieldType.CARD_EXPIRY, FieldType.CARD_CVC, FieldType.CARD_PIN),
            TypeSuggestion.of(imported)!!.types.values.toList(),
        )
    }

    @Test
    fun noOfferWithoutACardsNumberAndOneMorePart() {
        assertNull(TypeSuggestion.of(Entry("a", customFields = listOf(field("Number", "4111111111111111"), field("Bank", "Example")))))
        assertNull(TypeSuggestion.of(Entry("b", customFields = listOf(field("Number", "4711-0815"), field("Expiry", "2027-02")))), "too few digits")
        assertNull(TypeSuggestion.of(Entry("c", customFields = listOf(field("Number", "A12345678901"), field("Expiry", "2027-02")))))
        val hidden = TypeSuggestion.of(Entry("d", customFields = listOf(field("Card number", "x", protected = true), field("CVC", "1", protected = true))))
        assertEquals(TypeSuggestion.Kind.PAYMENT_CARD, hidden?.kind, "a protected number counts by its name")
        val partly = typed(card, "Card number = text")
        assertNull(TypeSuggestion.of(partly), "a field typed otherwise is left alone")
    }

    @Test
    fun networkFieldsWithoutTypesAreOfferedTheirs() {
        val offer = TypeSuggestion.of(wifi)!!
        assertEquals(TypeSuggestion.Kind.WIFI, offer.kind)
        assertEquals(
            mapOf("SSID" to FieldType.WIFI_SSID, "Wi-Fi password" to FieldType.WIFI_PASSWORD, "Security" to FieldType.WIFI_SECURITY),
            offer.types,
        )
        val router = Entry(
            "r",
            customFields = listOf(field("network name", "Example"), field("wireless security", "WPA2"), field("wireless network password", "pw", protected = true)),
        )
        assertEquals(TypeSuggestion.Kind.WIFI, TypeSuggestion.of(router)?.kind, "1Password's router")
    }

    @Test
    fun aNetworkWithoutAPasswordFieldOnlyWhenItSaysItIsOpen() {
        val noKey = Entry("n", password = dev.keepassbar.domain.secret.Secret.of("pw"), customFields = listOf(field("SSID", "Home"), field("Security", "WPA2")))
        assertNull(TypeSuggestion.of(noKey), "its password is elsewhere: the face would call it open")
        val open = Entry("o", customFields = listOf(field("SSID", "Cafe"), field("Security", "Open")))
        assertEquals(TypeSuggestion.Kind.WIFI, TypeSuggestion.of(open)?.kind)
        assertNull(TypeSuggestion.of(Entry("s", customFields = listOf(field("SSID", "Home")))))
    }
}
