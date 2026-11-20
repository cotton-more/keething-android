package dev.keething.card

import dev.keething.domain.text.UserText
import dev.keething.domain.text.english
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
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

    private fun visaWith(index: Int, replacement: dev.keething.domain.vault.CustomField) =
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

    @Test
    fun everyBrandsLeadingDigitsAndDinersGrouping() {
        val brands = mapOf(
            "340000000000009" to CardBrand.AMEX, "370000000000002" to CardBrand.AMEX, "2204000000000000" to CardBrand.MIR,
            "5100000000000000" to CardBrand.MASTERCARD, "2720990000000000" to CardBrand.MASTERCARD, "6500000000000000" to CardBrand.DISCOVER,
            "6440000000000000" to CardBrand.DISCOVER, "6490000000000000" to CardBrand.DISCOVER, "3589000000000000" to CardBrand.JCB,
            "36000000000008" to CardBrand.DINERS, "38000000000000" to CardBrand.DINERS, "39000000000000" to CardBrand.DINERS,
            "30000000000004" to CardBrand.DINERS, "30500000000000" to CardBrand.DINERS, "5000000000000000" to CardBrand.MAESTRO,
            "5600000000000000" to CardBrand.MAESTRO, "5800000000000000" to CardBrand.MAESTRO, "6300000000000000" to CardBrand.MAESTRO,
            "6700000000000000" to CardBrand.MAESTRO, "6201000000000000" to CardBrand.UNION_PAY,
        )
        for ((number, brand) in brands) assertEquals(brand, CardBrand.detect(number), number)
        for (number in listOf("2205000000000000", "2721000000000000", "6430000000000000", "3060000000000000")) assertNull(CardBrand.detect(number), number)
        assertEquals("3600 000000 0008", CardBrand.group("36000000000008", CardBrand.grouping(14, CardBrand.DINERS)))
        assertEquals("Mastercard", CardBrand.name("mastercard"))
        assertEquals(CardBrand.DINERS, CardBrand.known("Diners Club"))
    }

    @Test
    fun theSecurityFieldsWordsAsTheCriterionNamesThem() {
        fun security(text: String?, password: String? = "pw"): String {
            val fields = listOfNotNull(field("SSID", "N"), password?.let { field("Key", it) }, text?.let { field("Sec", it) })
            return build(Entry("w", customFields = fields), "SSID = wifi.ssid\nKey = wifi.password\nSec = wifi.security").wifi!!.security
        }
        for (open in listOf("none", "OPEN", "Offen", "нет", "Открытая")) assertEquals("nopass", security(open), open)
        assertEquals("nopass", security("WPA2", password = null), "no password")
        assertEquals("WEP", security("Shared WEP-128"))
        for (other in listOf("WPA2", "WPA3-Personal", "", "anything")) assertEquals("WPA", security(other), other)
    }

    @Test
    fun aProtectedOrEmptySsidIsNoNetwork() {
        val types = "SSID = wifi.ssid\nKey = wifi.password"
        assertNull(build(Entry("p", customFields = listOf(field("SSID", "N", protected = true), field("Key", "pw"))), types).wifi)
        assertNull(build(Entry("e", customFields = listOf(field("SSID", ""), field("Key", "pw"))), types).wifi)
    }

    // Payment card

    @Test
    fun theCardFieldsBecomeTheCardTheRestStays() {
        val card = build(visa, cardTypes)
        val p = card.paymentCard!!

        assertEquals(listOf(CardSection.Kind.PaymentCard(p), CardSection.Kind.Custom), card.sections.map { it.kind })
        assertEquals(6, card.sections[0].rows.size)
        assertNull(card.sections[0].title, "the fields come from different sections: full names")
        assertEquals(listOf("Bank"), card.sections[1].rows.map { it.label.english() })
        assertEquals("Additional Details › PIN", card.sections[0].rows.first { it.type == FieldType.CARD_PIN }.label.english())
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
        assertEquals("4111 1111 1111 1111", number.clipboardText())
        assertEquals("4111 1111 1111 1111", RevealState().toggleAll(card.rows).displayValue(number), "revealed with the protected rows")
        // Stored without spaces: shown grouped, copied as stored; the CVC and the PIN masked, copied as stored.
        val plain = build(visaWith(1, field("number", "4111111111111111")), cardTypes)
        val plainNumber = plain.rows.first { it.id == plain.paymentCard?.numberRowId }
        assertEquals("4111 1111 1111 1111", RevealState().toggle(plainNumber.id).displayValue(plainNumber))
        assertEquals("4111111111111111", plainNumber.clipboardText())
        val secrets = plain.rows.filter { it.type == FieldType.CARD_CVC || it.type == FieldType.CARD_PIN }
        assertEquals(listOf("123", "0000"), secrets.map { it.clipboardText() })
        assertTrue(secrets.all { it.isConcealed })
    }

    @Test
    fun aMaskedValueIsSpokenAsHiddenWithOnlyItsClearPartNeverTheDots() {
        val card = build(visa, cardTypes)
        val number = card.rows.first { it.id == card.paymentCard?.numberRowId }
        val cvc = card.rows.first { it.type == FieldType.CARD_CVC }

        assertEquals("Hidden, ending 1111", RevealState.spoken(RevealState().displayValue(number)).english())
        assertEquals("Hidden", RevealState.spoken(RevealState().displayValue(cvc)).english(), "a protected value: nothing of it")
        assertEquals("4111 1111 1111 1111", RevealState.spoken(RevealState().toggle(number.id).displayValue(number)).english(), "shown: as text")
        assertEquals("Visa, hidden, ending 1111", RevealState.spoken("Visa •• 1111").english())
        assertEquals("Visa, hidden", RevealState.spoken("visa ••").english(), "the part in clear starts the sentence")
        assertFalse(RevealState.spoken(RevealState.MASK).english().contains('•'))
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
        assertEquals(ExpiryHint.Status.SOON, noNumber.rows.first { it.label.english() == "expiry date" }.expiry?.status, "card.expiry alone still ends something")
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
        assertEquals(listOf("Kartennummer", "Karteninhaber", "gültig bis"), section.rows.map { it.label.english() })
        assertEquals("Karte › Karteninhaber", section.rows[1].noticeLabel.english(), "the notice still says which field")
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
        val network = WiFiNetwork("ExampleNet", "custom-1", "WEP", UserText.plain("WEP"), ssidRowId = "custom-0", securityRowId = "custom-2")

        assertEquals(listOf(CardSection.Kind.WiFi(network), CardSection.Kind.Custom), card.sections.map { it.kind })
        assertEquals(network, card.wifi)
        assertEquals("nopass", build(Entry("o", customFields = listOf(field("network name", "Cafe"))), wifiTypes).wifi?.security)
        assertNull(build(e, "x = date").wifi)
    }

    @Test
    fun aNetworksKeyIsAConcealedRowUnprotected() {
        val card = build(Entry("w", customFields = listOf(field("SSID", "ExampleNet"), field("Key", "pw"))), "SSID = wifi.ssid\nKey = wifi.password")
        val key = card.rows.single { it.id == "custom-1" }
        assertTrue(key.isConcealed && key.isCopyable, "masked, its copy sensitive as a concealed row's is")
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
        val all = DeadlineList.build(Vault(listOf(passport, contract, visa).map { typed(it, deadlineTypes) }), now, utc)

        assertEquals(listOf("Entry expires", "VERTRAG › Ende", "gültig bis", "Kündigungsfrist"), all.map { it.label.english() })
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
        assertEquals(listOf("VERTRAG › Ende", "gültig bis"), DeadlineList.build(Vault(listOf(passport)), now, utc).map { it.label.english() })
    }

    @Test
    fun theCardsBannerIsItsMostPressingFieldDeadline() {
        assertEquals("VERTRAG › Ende", build(passport, deadlineTypes).deadline?.label?.english())
        assertEquals("Expired 45 days ago", build(passport, deadlineTypes).deadline?.hint?.text?.english(), "the banner says it as the field does")
        assertNull(build(contract, deadlineTypes).deadline, "KeePass's own expiry has its own banner; the field is far off")
    }

    @Test
    fun aFacesOwnExpiryIsLeftOutBeforeTheMostPressingIsChosen() {
        // 2027-01-15: the card ends with January, the notice period a month later; the document has expired.
        val card = Entry(
            "c", title = "Visa",
            customFields = listOf(field("Number", "4111 1111 1111 1111"), field("Expiry", "01/27"), field("Kündigungsfrist", "2027-03-16")),
        )
        val cardTypes = "Number = card.number\nExpiry = card.expiry\nKündigungsfrist = until"
        assertEquals("Kündigungsfrist", build(card, cardTypes).deadline?.label?.english(), "the card's expiry is its chip")
        val document = Entry(
            "d", title = "Pass",
            customFields = listOf(field("Number", "C01X00T47"), field("Expiry", "2026-12-01"), field("Visa", "2027-04-01")),
        )
        val documentTypes = "Number = doc.number\nExpiry = doc.expiry\nVisa = until"
        assertEquals("Visa", build(document, documentTypes).deadline?.label?.english(), "the document's expiry is its chip")
        assertNull(build(card.copy(customFields = card.customFields.take(2)), cardTypes).deadline, "nothing else: no banner")
    }

    @Test
    fun todayIsThePhonesDayEastAndWestOfUtc() {
        val berlin = java.time.ZoneId.of("Europe/Berlin")
        val newYork = java.time.ZoneId.of("America/New_York")
        fun said(entry: Entry, at: String, zone: java.time.ZoneId) =
            DeadlineList.build(Vault(listOf(typed(entry, "Ende = until"))), Instant.parse(at), zone).single().let { it.date to it.hint.text.english() }
        val ends19 = Entry("a", title = "A", customFields = listOf(field("Ende", "2026-11-19")))
        val ends20 = Entry("b", title = "B", customFields = listOf(field("Ende", "2026-11-20")))
        // 00:30 on the 20th in Berlin is still the 19th in UTC.
        assertEquals("Expired yesterday", said(ends19, "2026-11-19T23:30:00Z", berlin).second)
        // 19:30 on the 20th in New York is the 21st in UTC.
        assertEquals("Expires today", said(ends20, "2026-11-21T00:30:00Z", newYork).second)

        // KeePass's own expiry: its day is its instant in the phone's zone, as the editor set it and the banner shows it.
        val dec1 = CalendarDate(2026, 12, 1)
        val atHalfPastMidnightInBerlin = Entry("c", title = "C", expires = true, expiryTime = Instant.parse("2026-11-30T23:30:00Z"))
        assertEquals(dec1 to "Expires tomorrow", said(atHalfPastMidnightInBerlin, "2026-11-30T19:00:00Z", berlin))
        val atEightInNewYork = Entry("d", title = "D", expires = true, expiryTime = Instant.parse("2026-12-02T01:00:00Z"))
        assertEquals(dec1 to "Expires today", said(atEightInNewYork, "2026-12-01T15:00:00Z", newYork))
        val card = EntryCardBuilder.build(atEightInNewYork, Instant.parse("2026-12-01T15:00:00Z"), de, newYork)
        assertEquals("Expires today", card.entryExpiry?.hint?.text?.english(), "the card's banner alike")
        assertEquals("Expires tomorrow", said(atEightInNewYork, "2026-12-01T15:00:00Z", utc).second, "the same instant is the 2nd in UTC")
    }

    @Test
    fun theListKnowsHowEachEntrysDeadlinesStand() {
        val status = DeadlineList.statusByEntry(Vault(listOf(passport, contract, visa).map { typed(it, deadlineTypes) }), now, utc)

        assertEquals(ExpiryHint.Status.EXPIRED, status["p"])
        assertEquals(ExpiryHint.Status.EXPIRED, status["c"])
        assertNull(status["v"])
    }
}
