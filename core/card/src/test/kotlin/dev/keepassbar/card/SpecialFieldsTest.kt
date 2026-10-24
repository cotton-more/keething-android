package dev.keepassbar.card

import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.Vault
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac `SpecialFieldsTests` expectations: payment cards, Wi-Fi networks and deadlines. */
class SpecialFieldsTest {
    private val cardTypes = """
        number = card.number
        cardholder name = card.holder
        expiry date = card.expiry
        verification number = card.cvc
        Additional Details_PIN = card.pin
        type = card.type
    """.trimIndent()

    private fun build(entry: Entry, notes: String) = EntryCardBuilder.build(typed(entry, notes), now, de)

    private val visaFields = listOf(
        field("cardholder name", "Jane Example"), field("number", "4111 1111 1111 1111"), field("expiry date", "202702"),
        field("type", "visa"), field("verification number", "123", protected = true),
        field("Additional Details_PIN", "0000", protected = true), field("Bank", "Example Bank"),
    )
    private val visa = Entry("v", title = "Visa", customFields = visaFields)

    private fun visaWith(index: Int, replacement: dev.keepassbar.domain.vault.CustomField) =
        visa.copy(customFields = visaFields.toMutableList().also { it[index] = replacement })

    // Brands

    @Test
    fun cardBrandByLeadingDigitsGroupedAsPrintedLuhnChecked() {
        assertEquals(CardBrand.VISA, CardBrand.detect("4111111111111111"))
        assertEquals(CardBrand.MASTERCARD, CardBrand.detect("5500000000000004"))
        assertEquals(CardBrand.MASTERCARD, CardBrand.detect("2221000000000009"))
        assertEquals(CardBrand.MIR, CardBrand.detect("2200123456789010"))
        assertEquals(CardBrand.AMEX, CardBrand.detect("378282246310005"))
        assertEquals(CardBrand.DISCOVER, CardBrand.detect("6011111111111117"))
        assertEquals(CardBrand.JCB, CardBrand.detect("3530111333300000"))
        assertEquals(CardBrand.UNION_PAY, CardBrand.detect("6200000000000005"))
        assertNull(CardBrand.detect("1234"))
        assertEquals("3782 822463 10005", CardBrand.group("378282246310005", CardBrand.grouping(15, CardBrand.AMEX)))
        assertEquals("4111 1111 1111 1111", CardBrand.group("4111111111111111", CardBrand.grouping(16, CardBrand.VISA)))
        assertTrue(CardBrand.luhn("4111111111111111"))
        assertFalse(CardBrand.luhn("4111111111111112"))
        assertEquals("Mastercard", CardBrand.name("MC"))
        assertEquals("Mir", CardBrand.name("МИР"))
        assertEquals("Girocard", CardBrand.name("Girocard"))
    }

    // Payment card

    @Test
    fun theCardFieldsBecomeTheCardTheRestStays() {
        val card = build(visa, cardTypes)
        val p = card.paymentCard!!

        assertEquals(listOf(CardSection.Kind.PaymentCard(p), CardSection.Kind.Custom), card.sections.map { it.kind })
        assertEquals(6, card.sections[0].rows.size)
        assertNull(card.sections[0].title, "the fields come from different sections: full names")
        assertEquals(listOf("Bank"), card.sections[1].rows.map { it.label })
        assertEquals("Additional Details › PIN", card.sections[0].rows.first { it.type == FieldType.CARD_PIN }.label)
        assertEquals("Visa", p.brand)
        assertEquals("4111 1111 1111 1111", p.groupedNumber)
        assertEquals("•••• •••• •••• 1111", p.maskedNumber)
        assertEquals(true, p.checkDigitOk)
        assertEquals("Jane Example", p.holder)
        assertEquals(CalendarDate(2027, 2), p.expiry?.date)
        assertEquals(ExpiryHint.Status.SOON, p.expiryHint?.status)
    }

    @Test
    fun theNumberIsMaskedToItsLastDigitsUntilRevealedAndCopiedAsStored() {
        val card = build(visa, cardTypes)
        val number = card.rows.first { it.id == card.paymentCard?.numberRowId }

        assertTrue(number.isConcealed && !number.isProtected, "concealed on screen, not protected in the database")
        assertEquals("•••• •••• •••• 1111", RevealState().displayValue(number))
        assertEquals("4111 1111 1111 1111", RevealState().toggle(number.id).displayValue(number))
        assertEquals("4111 1111 1111 1111", number.copyText)
        assertEquals("4111 1111 1111 1111", RevealState().toggleAll(card.rows).displayValue(number), "revealed with the protected rows")
    }

    @Test
    fun aProtectedNumberIsNeverReadTheTypeFieldNamesTheBrand() {
        val p = build(visaWith(1, field("number", "4111111111111111", protected = true)), cardTypes).paymentCard!!

        assertEquals("Visa", p.brand, "from the type field")
        assertNull(p.groupedNumber)
        assertEquals(RevealState.MASK, p.maskedNumber)
        assertNull(p.checkDigitOk)
    }

    @Test
    fun aTypoFailsTheCheckNoCardWithoutFieldTypesOrANumber() {
        assertEquals(false, build(visaWith(1, field("number", "4111 1111 1111 1112")), cardTypes).paymentCard?.checkDigitOk)
        assertNull(EntryCardBuilder.build(visa, now, de).paymentCard, "without types nothing is a card")
        val noNumber = build(visa, "expiry date = card.expiry")
        assertNull(noNumber.paymentCard)
        assertEquals(ExpiryHint.Status.SOON, noNumber.rows.first { it.label == "expiry date" }.expiry?.status, "card.expiry alone still ends something")
    }

    @Test
    fun cvcAndPinAreMaskedEvenWhenTheDatabaseDoesNotProtectThem() {
        val e = visaWith(4, field("verification number", "123")).let {
            it.copy(customFields = it.customFields.toMutableList().also { f -> f[5] = field("Additional Details_PIN", "0000") })
        }
        val rows = build(e, cardTypes).rows
        for (type in listOf(FieldType.CARD_CVC, FieldType.CARD_PIN)) {
            val row = rows.first { it.type == type }
            assertTrue(row.isConcealed && !row.isProtected, "$type")
            assertEquals(RevealState.MASK, RevealState().displayValue(row))
        }
        assertFalse(rows.first { it.type == FieldType.CARD_HOLDER }.isConcealed, "the holder's name is not a secret")
    }

    @Test
    fun fieldsFromOneSectionGiveItsTitleAndLabelsWithoutThePrefix() {
        val e = Entry(
            "k", title = "Sparkasse",
            customFields = listOf(field("Karte_Kartennummer", "4111 1111 1111 1111"), field("Karte_Karteninhaber", "Erika Mustermann"), field("Karte_gültig bis", "2027-02")),
        )
        val card = build(e, "Karte_Kartennummer = card.number\nKarte_Karteninhaber = card.holder\nKarte_gültig bis = card.expiry")
        val section = card.sections.first { it.kind is CardSection.Kind.PaymentCard }

        assertEquals("Karte", section.title)
        assertEquals(listOf("Kartennummer", "Karteninhaber", "gültig bis"), section.rows.map { it.label })
        assertEquals("Karte › Karteninhaber", section.rows[1].noticeLabel, "the notice still says which field")
    }

    // Wi-Fi

    @Test
    fun theQrTextEscapesSpecialCharactersAndFollowsTheSecurity() {
        assertEquals(
            """WIFI:T:WPA;S:Home\;Net;P:a\:b\,c\"d\\;;""",
            WiFiNetwork("Home;Net", "p", "WPA").qrPayload("""a:b,c"d\"""),
        )
        assertEquals("WIFI:T:nopass;S:Cafe;;", WiFiNetwork("Cafe", null, "nopass").qrPayload(""))
    }

    @Test
    fun anEntryWithAnSsidGetsTheNetwork() {
        val wifiTypes = "network name = wifi.ssid\nwireless network password = wifi.password\nsecurity = wifi.security"
        val e = Entry(
            "w", title = "Home",
            customFields = listOf(field("network name", "ExampleNet"), field("wireless network password", "pw", protected = true), field("security", "WEP"), field("router", "Fritz")),
        )
        val card = build(e, wifiTypes)
        val network = WiFiNetwork("ExampleNet", "custom-1", "WEP", "WEP", ssidRowId = "custom-0", securityRowId = "custom-2")

        assertEquals(listOf(CardSection.Kind.WiFi(network), CardSection.Kind.Custom), card.sections.map { it.kind })
        assertEquals(network, card.wifi)
        assertEquals("nopass", build(Entry("o", customFields = listOf(field("network name", "Cafe"))), wifiTypes).wifi?.security)
        assertNull(build(e, "x = date").wifi)
    }

    @Test
    fun theQrCodeOfANetworkWithAPasswordNeedsThePasswordRevealed() {
        val types = "SSID = wifi.ssid\nWLAN-Passwort = wifi.password"
        val home = build(Entry("w", customFields = listOf(field("SSID", "ExampleNet"), field("WLAN-Passwort", "pw;1", protected = true))), types)

        assertNull(home.wifiQrPayload(RevealState()), "masked: no code")
        assertEquals("WIFI:T:WPA;S:ExampleNet;P:pw\\;1;;", home.wifiQrPayload(RevealState().toggle("custom-1")))
        val open = build(Entry("o", customFields = listOf(field("SSID", "Cafe"))), types)
        assertEquals("WIFI:T:nopass;S:Cafe;;", open.wifiQrPayload(RevealState()), "an open network has nothing to hide")
        assertNull(build(Entry("x"), types).wifiQrPayload(RevealState()))
    }

    // Deadlines

    private val passport = Entry(
        "p", title = "Reisepass", groupPath = listOf("Pässe"),
        customFields = listOf(field("gültig bis", "2027-03-01"), field("Дата выдачи", "2017-03-01"), field("VERTRAG_Ende", "2026-12-01")),
    )
    private val contract = Entry(
        "c", title = "DSL", customFields = listOf(field("Kündigungsfrist", "2031-05-01")),
        expires = true, expiryTime = Instant.ofEpochSecond(1_790_000_000), // 2026-09-21
    )
    private val deadlineTypes = "gültig bis = until\nДата выдачи = date\nVERTRAG_Ende = until\nKündigungsfrist = until"

    @Test
    fun untilFieldsAndKeePasssOwnExpiryEarliestFirst() {
        val all = DeadlineList.build(Vault(listOf(passport, contract, visa).map { typed(it, deadlineTypes) }), now)

        assertEquals(listOf("Entry expires", "VERTRAG › Ende", "gültig bis", "Kündigungsfrist"), all.map { it.label })
        assertEquals(listOf("DSL", "Reisepass", "Reisepass", "DSL"), all.map { it.entryTitle })
        assertEquals(
            listOf(ExpiryHint.Status.EXPIRED, ExpiryHint.Status.EXPIRED, ExpiryHint.Status.SOON, ExpiryHint.Status.VALID),
            all.map { it.hint.status },
        )
        assertEquals(ExpiryHint.Status.EXPIRED, DeadlineList.worstStatus(all.filter { it.entryId == "p" }))
        assertNull(DeadlineList.worstStatus(all.filter { it.entryId == "v" }))
    }

    @Test
    fun withoutFieldTypesNamesDecide() {
        assertEquals(listOf("VERTRAG › Ende", "gültig bis"), DeadlineList.build(Vault(listOf(passport)), now).map { it.label })
    }

    @Test
    fun theCardsBannerIsItsMostPressingFieldDeadline() {
        assertEquals("VERTRAG › Ende", build(passport, deadlineTypes).deadline?.label)
        assertNull(build(contract, deadlineTypes).deadline, "KeePass's own expiry has its own banner; the field is far off")
    }

    @Test
    fun theListKnowsHowEachEntrysDeadlinesStand() {
        val status = DeadlineList.statusByEntry(Vault(listOf(passport, contract, visa).map { typed(it, deadlineTypes) }), now)

        assertEquals(ExpiryHint.Status.EXPIRED, status["p"])
        assertEquals(ExpiryHint.Status.EXPIRED, status["c"])
        assertNull(status["v"])
    }
}
