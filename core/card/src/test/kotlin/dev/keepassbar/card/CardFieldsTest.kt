package dev.keepassbar.card

import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.Vault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac `CardFieldsTests` expectations: sections, imported names, dates, links, lists of codes. */
class CardFieldsTest {
    private fun card(fields: List<dev.keepassbar.domain.vault.CustomField>, url: String = "", types: FieldTypes = FieldTypes.NONE) =
        EntryCardBuilder.build(Entry("e", title = "T", url = url, customFields = fields), now, de, types)

    // Dates

    @Test
    fun isoAndTheImportsTextDateAreDatesInAnyField() {
        assertEquals(day(2027, 10, 31), FieldDate.parse("2027-10-31", typed = false)?.date)
        assertEquals(day(2027, 10, 31), FieldDate.parse("2027-10-31T00:00:00Z", typed = false)?.date)
        assertEquals(true, FieldDate.parse("2027-10-31", typed = false)?.isStandard)
        val qt = FieldDate.parse("Sun Oct 31 00:00:00 2027 GMT", typed = false)
        assertEquals(day(2027, 10, 31), qt?.date, "how KeePassXC's 1Password import writes dates")
        assertEquals(false, qt?.isStandard)
        assertEquals(day(2027, 2, 1), FieldDate.parse("Mon Feb 1 00:00:00 2027 GMT", typed = false)?.date)
        assertEquals(day(2027, 2, 1), FieldDate.parse("Mon Feb  1 00:00:00 2027", typed = false)?.date)
    }

    @Test
    fun monthsAndTheOldFormsOnlyInATypedField() {
        for (text in listOf("2027-12", "202712", "12/27", "12/2027", "31.10.2027")) {
            assertNull(FieldDate.parse(text, typed = false), text)
        }
        assertEquals(FieldDate(CalendarDate(2027, 12), isStandard = true), FieldDate.parse("2027-12", typed = true))
        assertEquals(FieldDate(CalendarDate(2027, 12), isStandard = false), FieldDate.parse("202712", typed = true))
        assertEquals(CalendarDate(2027, 12), FieldDate.parse("12/27", typed = true)?.date)
        assertEquals(CalendarDate(2027, 12), FieldDate.parse("12/2027", typed = true)?.date)
        assertEquals(FieldDate(day(2027, 10, 31), isStandard = false), FieldDate.parse("31.10.2027", typed = true))
        assertEquals(day(2027, 2, 1), FieldDate.parse("1.2.2027", typed = true)?.date)
    }

    @Test
    fun whatIsNotARealDateIsLeftAlone() {
        for (text in listOf(
            "2027-13-01", "2027-02-30", "31.02.2027", "99.999.999", "Mon Foo 1 00:00:00 2027", "today", "",
            "2027-10-31 and more", "202713", "13/27",
        )) {
            assertNull(FieldDate.parse(text, typed = true), text)
        }
    }

    @Test
    fun digitsOfOtherScriptsAreText() {
        for (text in listOf(
            "２０２７-１０-３１", "٢٠٢٧-١٠-٣١", "२०२७-१०-३१", "２０２７-１２", "３１.１０.２０２７", "２０２７１２", "１２/２７",
            "Sun Oct ３１ 00:00:00 2027 GMT", "Sun Oct 31 00:00:00 ２０２７ GMT",
        )) {
            assertNull(FieldDate.parse(text, typed = true), text)
            assertNull(FieldDate.parse(text, typed = false), text)
        }
        val vault = Vault(listOf(Entry("e", title = "T", customFields = listOf(field("gültig bis", "２０２７-１０-３１")))))
        assertTrue(DeadlineList.build(vault, now).isEmpty())
        assertEquals(ValueStyle.Text, card(listOf(field("gültig bis", "٢٠٢٧-١٠-٣١"))).rows.first().style)
    }

    @Test
    fun datesAreOrderedByTheLastDayTheyCover() {
        val december = CalendarDate(2026, 12)
        assertTrue(day(2026, 12, 1) < december, "1 Dec is due before a card valid through December")
        assertTrue(day(2026, 12, 31) < december && !(december < day(2026, 12, 31)), "same end: the day first")
        assertTrue(day(2026, 11, 30) < december && december < day(2027, 1, 1))
        assertEquals(listOf(day(2026, 11, 5), day(2026, 12, 1), december), listOf(december, day(2026, 12, 1), day(2026, 11, 5)).sorted())
    }

    @Test
    fun shownAsTheLocalesMediumDateAMonthAsMmYyyy() {
        val d = FieldDate.parse("2027-10-31", typed = false)!!
        assertTrue("2027" in d.displayText(de) && "31" in d.displayText(de), d.displayText(de))
        assertEquals("12/2027", FieldDate.parse("2027-12", typed = true)!!.displayText(de))
    }

    @Test
    fun howAnEndDateStandsAMonthIsValidToItsLastDay() {
        fun status(value: String) = FieldDate.parse(value, typed = true)?.expiry(now)?.status
        assertEquals(ExpiryHint.Status.EXPIRED, status("2026-12-01"))
        assertEquals(ExpiryHint.Status.SOON, status("2027-03-01"), "within 90 days")
        assertEquals(ExpiryHint.Status.VALID, status("2031-05-01"))
        assertEquals(ExpiryHint.Status.SOON, status("2027-01"))
        assertEquals(ExpiryHint.Status.SOON, status("2027-01-15"), "valid on its last day")
        assertEquals(ExpiryHint.Status.EXPIRED, status("2027-01-14"))
    }

    @Test
    fun theStatusTextCountsDaysThenMonthsThenYears() {
        fun text(value: String) = FieldDate.parse(value, typed = false)?.expiry(now)?.text
        assertEquals("today", text("2027-01-15"))
        assertEquals("tomorrow", text("2027-01-16"))
        assertEquals("in 36 days", text("2027-02-20"))
        assertEquals("in 5 months", text("2027-06-20"))
        assertEquals("in 4 years", text("2031-05-01"))
        assertEquals("yesterday", text("2027-01-14"))
        assertEquals("45 days ago", text("2026-12-01"))
        assertEquals("2 years ago", text("2025-01-01"))
        assertEquals("in 12 months", text("2028-01-15"))
        assertEquals("in 2 months", text("2027-03-20"))
    }

    @Test
    fun withoutFieldTypesADateEndsSomethingOnlyWhenItsNameSaysSo() {
        fun status(name: String) = card(listOf(field(name, "2031-05-01"))).rows.first().expiry?.status
        for (name in listOf(
            "gültig bis", "expiry date", "Expires", "Ablaufdatum", "Ende Mindestvertragslaufzeit",
            "Mögliches Vertragsende (berechnet)", "Kündigungsfrist", "Widerruf bis", "valid until", "valid thru",
            "Срок действия", "действителен до",
        )) {
            assertEquals(ExpiryHint.Status.VALID, status(name), name)
        }
        for (name in listOf("Geburtsdatum", "Дата выдачи", "valid from", "Registriert am", "Spende", "Legende", "Dividende")) {
            assertNull(status(name), name)
            assertIs<ValueStyle.Date>(card(listOf(field(name, "2031-05-01"))).rows.first().style, "still a date: $name")
        }
        assertEquals(ValueStyle.Text, card(listOf(field("expiry date", "202712"))).rows.first().style, "no guessing months")
    }

    // Names

    @Test
    fun onePasswordsGeneratedIdsAreRecognised() {
        assertTrue(ImportedFieldName.isGeneratedId("2iit44k5anegzf2dzpr3xhtdyq"))
        assertFalse(ImportedFieldName.isGeneratedId("2iit44k5anegzf2dzpr3xhtdy"), "25 characters")
        assertFalse(ImportedFieldName.isGeneratedId("Verification_o55bsgnoecbjc46jbziwzgvxke"))
        assertFalse(ImportedFieldName.isGeneratedId("abcdefghijklmnopqrstuvwxy1"), "1 is not base32")
    }

    // Sections

    @Test
    fun fieldsSharingASectionPrefixFormATitledSection() {
        val c = card(
            listOf(
                field("Kundennummer", "1"), field("VERTRAG_Anbieter", "A"), field("VERTRAG_Kundennummer", "2"),
                field("TARIF & PREISE_Rabatt", "R"), field("TARIF & PREISE_Router", "F"), field("issuing_country", "DE"),
            ),
        )
        val custom = c.sections.filter { it.kind == CardSection.Kind.Custom }
        assertEquals(listOf(null, "VERTRAG", "TARIF & PREISE", null), custom.map { it.title })
        assertEquals(
            listOf(listOf("Kundennummer"), listOf("Anbieter", "Kundennummer"), listOf("Rabatt", "Router"), listOf("issuing_country")),
            custom.map { s -> s.rows.map { it.label } },
            "a prefix used once is part of the name",
        )
        assertEquals((0..5).map { "custom-$it" }, c.rows.filter { it.kind == FieldRow.Kind.CUSTOM }.map { it.id })
    }

    @Test
    fun aSectionKeepsThePlaceOfItsFirstField() {
        val c = card(listOf(field("A_x"), field("loose1"), field("A_y"), field("loose2"), field("B_1"), field("B_2")))
        val custom = c.sections.filter { it.kind == CardSection.Kind.Custom }
        assertEquals(listOf("A", null, "B"), custom.map { it.title })
        assertEquals(listOf(listOf("x", "y"), listOf("loose1", "loose2"), listOf("1", "2")), custom.map { s -> s.rows.map { it.label } })
    }

    @Test
    fun generatedIdsAreNotShownAsNames() {
        val rows = card(
            listOf(field("2iit44k5anegzf2dzpr3xhtdyq", "a"), field("Verification_Code", "1"), field("Verification_o55bsgnoecbjc46jbziwzgvxke", "2")),
        ).rows.filter { it.kind == FieldRow.Kind.CUSTOM }
        assertEquals(listOf("", "Code", ""), rows.map { it.label })
        assertEquals(listOf("Value", "Code", "Verification"), rows.map { it.noticeLabel })
    }

    @Test
    fun numberedOrIdNamedFieldsOfASectionAreAListOfCodes() {
        val codes = (1..10).map { field("Recovery Codes_%02d".format(it), "code$it", protected = true) }
        val c = card(
            codes + listOf(
                field("Wiederherstellungscodes_3krjvvslphvsk4lyks4q3pw2je", "w1"),
                field("Wiederherstellungscodes_4fpf7l6sdaoo5x3yj2fzqjzfji", "w2"),
                field("Wiederherstellungscodes_5xt7vrm5xa22jr3rjntmi76rrq", "w3"),
                field("Two_01", "a"), field("Two_02", "b"),
            ),
        )
        val custom = c.sections.filter { it.kind == CardSection.Kind.Custom || it.kind == CardSection.Kind.Codes }
        assertEquals(listOf("Recovery Codes", "Wiederherstellungscodes", "Two"), custom.map { it.title })
        assertEquals(listOf(CardSection.Kind.Codes, CardSection.Kind.Codes, CardSection.Kind.Custom), custom.map { it.kind })
        assertEquals("Recovery Codes 01", custom[0].rows.first().noticeLabel)
        assertEquals(listOf("1", "2", "3"), custom[1].rows.map { it.label }, "id-named codes are numbered")
    }

    // Field types

    @Test
    fun fieldTypesFromNotesLinesAndCustomFields() {
        val t = types(
            """
            # dates
            gültig bis = until
              Дата выдачи=date

            a = b = until
            no equals sign
             = date
            Aktenzeichen = text
            """.trimIndent(),
            listOf(field("Erstzulassung", "date"), field("expiry date", "UNTIL")),
        )
        assertTrue(t.isConfigured)
        assertEquals(listOf("gültig bis", "Дата выдачи", "a = b", "Aktenzeichen", "Erstzulassung", "expiry date"), t.rules.map { it.fieldName })
        assertEquals(FieldType.UNTIL, t.type("gültig bis"))
        assertEquals(FieldType.UNTIL, t.type("GÜLTIG BIS "), "names match ignoring case and outer spaces")
        assertEquals(FieldType.DATE, t.type("Дата выдачи"))
        assertEquals(FieldType.UNTIL, t.type("a = b"))
        assertEquals(FieldType.DATE, t.type("Erstzulassung"))
        assertEquals(FieldType.UNTIL, t.type("expiry date"), "type names ignore case")
        assertNull(t.type("Aktenzeichen"))
        assertNull(t.type("gultig bis"), "a name must match, accents included")
        assertEquals(
            listOf("notes, line 6: expected '<field name> = <type>'", "notes, line 7: expected '<field name> = <type>'", "notes, line 8: 'text' is not a type"),
            t.problems,
        )
    }

    @Test
    fun aProtectedFieldOfTheSettingsEntryIsNotReadAsARule() {
        val t = types("", listOf(field("PIN", "s3cret-value", protected = true), field("Erstzulassung", "date"), field("SimpleLogin API key", "k")))
        assertNull(t.type("PIN"))
        assertEquals(FieldType.DATE, t.type("Erstzulassung"))
        assertNull(t.type("SimpleLogin API key"), "an alias provider's key is no rule")
        assertEquals(1, t.problems.size)
        assertFalse(t.problems.joinToString().contains("s3cret-value"))
    }

    @Test
    fun aLaterRuleWinsNoSettingsEntryMeansNotConfigured() {
        assertEquals(FieldType.UNTIL, types("x = date\nX = until").type("x"))
        assertFalse(FieldTypes.from(null).isConfigured)
        assertTrue(types("").isConfigured, "an empty KeePassBar entry still turns guessing off")
    }

    @Test
    fun withFieldTypesOnlyTypedFieldsAreDatesOnlyUntilEndsSomething() {
        val t = types("gültig bis = until\nGeburtsdatum = date\nexpiry date = until\nKarte = until")
        val rows = card(
            listOf(
                field("gültig bis", "2031-05-01"), field("Geburtsdatum", "01.05.1990"), field("expiry date", "202712"),
                field("Spende", "2031-05-01"), field("Vertragsende", "2031-05-01"), field("Karte", "not a date"),
            ),
            types = t,
        ).rows
        val a = assertIs<ValueStyle.Date>(rows[0].style).date
        val b = assertIs<ValueStyle.Date>(rows[1].style).date
        val c = assertIs<ValueStyle.Date>(rows[2].style).date
        assertTrue(a.isStandard && !b.isStandard && !c.isStandard)
        assertEquals(CalendarDate(2027, 12), c.date)
        assertEquals(listOf(true, false, true, false, false, false), rows.map { it.expiry != null })
        assertEquals(ValueStyle.Text, rows[3].style, "untyped: not a date, even in ISO")
        assertEquals(ValueStyle.Text, rows[4].style, "names are not guessed once types are configured")
        assertEquals(ValueStyle.Text, rows[5].style)
        assertEquals("01.05.1990", rows[1].copyText)
    }

    // Values

    @Test
    fun datesShowFormattedCopyAsStoredAndCarryTheirExpiry() {
        val rows = card(
            listOf(field("gültig bis", "Sun Oct 31 00:00:00 2027 GMT"), field("Geburtsdatum", "1990-05-01"), field("eHealth card CAN", "202412")),
        ).rows.filter { it.kind == FieldRow.Kind.CUSTOM }
        assertEquals(day(2027, 10, 31), assertIs<ValueStyle.Date>(rows[0].style).date.date)
        assertEquals("Sun Oct 31 00:00:00 2027 GMT", rows[0].copyText, "copying never changes the value")
        assertEquals("1990-05-01", rows[1].copyText)
        assertEquals(ExpiryHint.Status.VALID, rows[0].expiry?.status)
        assertTrue("2027" in rows[0].shownText!! && "GMT" !in rows[0].shownText!!, rows[0].shownText)
        assertNull(rows[1].expiry)
        assertEquals(ValueStyle.Text, rows[2].style)
    }

    @Test
    fun anUnprotectedValueThatIsPlainlyASecretIsMasked() {
        val rows = card(
            listOf(
                field("2FA", "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP"),
                field("Key", "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk=\n-----END OPENSSH PRIVATE KEY-----"),
                field("Cert", "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"),
                field("Note", "otp is on the phone"),
            ),
        ).rows
        // The otpauth link is a live code: the link itself is never shown.
        assertEquals(listOf(FieldRow.Kind.TOTP, FieldRow.Kind.CUSTOM, FieldRow.Kind.CUSTOM, FieldRow.Kind.CUSTOM), rows.map { it.kind })
        assertEquals(listOf(true, false, false), rows.drop(1).map { it.isConcealed })
        assertTrue(rows.none { it.isProtected }, "presentation only")
    }

    @Test
    fun aProtectedValueIsNeverReadAsADateOrALink() {
        val rows = card(listOf(field("expiry date", "2027-10-31", protected = true), field("url", "https://x.example", protected = true)))
            .rows.filter { it.kind == FieldRow.Kind.CUSTOM }
        assertEquals(listOf(ValueStyle.Text, ValueStyle.Text), rows.map { it.style })
        assertEquals(listOf("2027-10-31", "https://x.example"), rows.map { it.copyText })
    }

    @Test
    fun additionalUrlsAreLinksInTheCredentialsAfterTheUrl() {
        val c = card(
            listOf(field("KP2A_URL_1", "https://two.example"), field("Org", "Acme"), field("KP2A_URL_2", "three.example/login"), field("KP2A_URL_3", "not a url")),
            url = "https://one.example",
        )
        val credentials = c.sections.first { it.kind == CardSection.Kind.Credentials }
        assertEquals(listOf("URL", "URL 2", "URL 3", "URL 4"), credentials.rows.map { it.label })
        // Unlike the Mac card, the URL row itself is a link too when it can be opened (the card's open button).
        assertEquals(
            listOf(ValueStyle.Link("https://one.example"), ValueStyle.Link("https://two.example"), ValueStyle.Link("https://three.example/login"), ValueStyle.Text),
            credentials.rows.map { it.style },
        )
        assertEquals(listOf("Org"), c.sections.filter { it.kind == CardSection.Kind.Custom }.flatMap { s -> s.rows.map { it.label } })
    }

    @Test
    fun webAddressesInOtherFieldsAreLinksABareHostOnlyInAFieldNamedLikeOne() {
        val rows = card(
            listOf(
                field("ZUGÄNGE_Portal-URL", "https://portal.example"), field("ZUGÄNGE_Kundencenter", "App"),
                field("hostname", "db.example.com"), field("Kennzeichen", "B AB 123"), field("note", "see host.example"),
                field("Script", "javascript:alert(1)"),
            ),
        ).rows.filter { it.kind == FieldRow.Kind.CUSTOM }
        assertEquals(
            listOf(ValueStyle.Link("https://portal.example"), ValueStyle.Text, ValueStyle.Link("https://db.example.com"), ValueStyle.Text, ValueStyle.Text, ValueStyle.Text),
            rows.map { it.style },
        )
    }

    @Test
    fun rowsInSectionsAreTheSameRowsInSectionOrder() {
        val c = card(listOf(field("A_x"), field("loose"), field("A_y")), url = "https://u.example")
        assertEquals(c.sections.flatMap { s -> s.rows.map { it.id } }, c.rows.map { it.id })
        assertEquals(listOf(CardSection.Kind.Credentials, CardSection.Kind.Custom, CardSection.Kind.Custom), c.sections.map { it.kind })
    }
}
