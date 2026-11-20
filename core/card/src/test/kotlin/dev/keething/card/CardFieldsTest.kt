package dev.keething.card

import dev.keething.domain.text.english
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac `CardFieldsTests` expectations: sections, imported names, dates, links, lists of codes. */
class CardFieldsTest {
    private fun card(fields: List<dev.keething.domain.vault.CustomField>, url: String = "", types: Map<String, String> = emptyMap()) =
        EntryCardBuilder.build(Entry("e", title = "T", url = url, customFields = fields, fieldTypes = types), now, de)

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
        assertTrue(DeadlineList.build(vault, now, utc).isEmpty())
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
        fun status(value: String) = FieldDate.parse(value, typed = true)?.expiry(now, utc)?.status
        assertEquals(ExpiryHint.Status.EXPIRED, status("2026-12-01"))
        assertEquals(ExpiryHint.Status.SOON, status("2027-03-01"), "within 90 days")
        assertEquals(ExpiryHint.Status.VALID, status("2031-05-01"))
        assertEquals(ExpiryHint.Status.SOON, status("2027-01"))
        assertEquals(ExpiryHint.Status.SOON, status("2027-01-15"), "valid on its last day")
        assertEquals(ExpiryHint.Status.EXPIRED, status("2027-01-14"))
    }

    @Test
    fun theStatusTextCountsDaysThenMonthsThenYears() {
        fun text(value: String) = FieldDate.parse(value, typed = false)?.expiry(now, utc)?.text?.english()
        assertEquals("Expires today", text("2027-01-15"))
        assertEquals("Expires tomorrow", text("2027-01-16"))
        assertEquals("Expires in 36 days", text("2027-02-20"))
        assertEquals("Expires in 5 months", text("2027-06-20"))
        assertEquals("Expires in 4 years", text("2031-05-01"))
        assertEquals("Expired yesterday", text("2027-01-14"))
        assertEquals("Expired 45 days ago", text("2026-12-01"))
        assertEquals("Expired 2 years ago", text("2025-01-01"))
        assertEquals("Expires in 12 months", text("2028-01-15"))
        assertEquals("Expires in 2 months", text("2027-03-20"))
        assertEquals("Expired 5 months ago", text("2026-08-01"))
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
            custom.map { s -> s.rows.map { it.label.english() } },
            "a prefix used once is part of the name",
        )
        assertEquals((0..5).map { "custom-$it" }, c.rows.filter { it.kind == FieldRow.Kind.CUSTOM }.map { it.id })
    }

    @Test
    fun aSectionKeepsThePlaceOfItsFirstField() {
        val c = card(listOf(field("A_x"), field("loose1"), field("A_y"), field("loose2"), field("B_1"), field("B_2")))
        val custom = c.sections.filter { it.kind == CardSection.Kind.Custom }
        assertEquals(listOf("A", null, "B"), custom.map { it.title })
        assertEquals(listOf(listOf("x", "y"), listOf("loose1", "loose2"), listOf("1", "2")), custom.map { s -> s.rows.map { it.label.english() } })
    }

    @Test
    fun generatedIdsAreNotShownAsNames() {
        val rows = card(
            listOf(field("2iit44k5anegzf2dzpr3xhtdyq", "a"), field("Verification_Code", "1"), field("Verification_o55bsgnoecbjc46jbziwzgvxke", "2")),
        ).rows.filter { it.kind == FieldRow.Kind.CUSTOM }
        assertEquals(listOf("", "Code", ""), rows.map { it.label.english() })
        assertEquals(listOf("Value", "Code", "Verification"), rows.map { it.noticeLabel.english() })
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
        assertEquals("Recovery Codes 01", custom[0].rows.first().noticeLabel.english())
        assertEquals(listOf("1", "2", "3"), custom[1].rows.map { it.label.english() }, "id-named codes are numbered")
    }

    @Test
    fun aCodeMarkedUsedShowsItsDayAndCopiesTheCodeAlone() {
        val c = card(
            listOf(
                field("Recovery_1", "used 2026-10-22 \u00B7 1111-2222", protected = true),
                field("Recovery_2", "3333-4444", protected = true),
                field("Recovery_3", "USED 2026-01-05 \u00B7 5555"),
            ),
            types = (1..3).associate { "Recovery_$it" to "code" },
        )
        val rows = c.sections.single { it.kind == CardSection.Kind.Codes }.rows
        assertEquals(listOf(java.time.LocalDate.of(2026, 10, 22), null, java.time.LocalDate.of(2026, 1, 5)), rows.map { it.usedOn })
        assertEquals(listOf("1111-2222", "3333-4444", "5555"), rows.map { it.storedText() })
        val notCodes = card(listOf(field("Note", "used 2026-10-22 \u00B7 not a code"))).sections.flatMap { it.rows }.single { it.label.english() == "Note" }
        assertNull(notCodes.usedOn, "only a list of codes has used ones")
        assertEquals("used 2026-10-22 \u00B7 not a code", notCodes.storedText())
    }

    @Test
    fun whatIsLeftCountsTheUnusedCodesThatHaveAValue() {
        fun codes(vararg values: String) = card(
            values.mapIndexed { i, v -> field("Recovery_${i + 1}", v, protected = true) },
            types = values.indices.associate { "Recovery_${it + 1}" to "code" },
        ).sections.single { it.kind == CardSection.Kind.Codes }
        val some = codes("", "1111", "used 2026-10-22 · 2222", "", "3333")
        assertEquals(2, some.codesLeft)
        assertEquals(5, some.rows.size)
        assertEquals("1111", some.nextUnusedCode?.storedText())
        assertTrue(some.fewCodesLeft)
        assertEquals(listOf(false, true, false, false, true), some.rows.map { some.isLeft(it) }, "the bar's segments")
        val emptyLeft = codes("used 2026-10-22 · 1111", "", "")
        assertEquals(0, emptyLeft.codesLeft, "\"0 of 3 left\" over \"No unused codes left\"")
        assertNull(emptyLeft.nextUnusedCode)
    }

    @Test
    fun theUsedMarkGoesInFrontOfTheCodeAndComesOffAgain() {
        val day = java.time.LocalDate.of(2026, 10, 22)
        assertEquals("used 2026-10-22 \u00B7 1234-5678", UsedCode.mark("1234-5678", day))
        assertEquals("used 2026-10-22 \u00B7 1234-5678", UsedCode.mark("used 2026-10-22 \u00B7 1234-5678", day.plusDays(1)), "marked once")
        assertEquals("1234-5678", UsedCode.unmark(UsedCode.mark("1234-5678", day)))
        assertEquals("1234-5678", UsedCode.unmark("1234-5678"))
        assertEquals("a \u00B7 b", UsedCode.parse("used 2026-10-22 \u00B7 a \u00B7 b")?.code, "the first separator only")
        assertNull(UsedCode.parse("used 2026-13-40 \u00B7 x"), "not a day")
        assertNull(UsedCode.parse("used yesterday \u00B7 x"))
        assertNull(UsedCode.parse("1234 used 2026-10-22 \u00B7 x"))
    }

    @Test
    fun markingACodeIsAnEditOfItsValueProtectedAsItWas() {
        val day = java.time.LocalDate.of(2026, 10, 22)
        val entry = Entry(
            "e", customFields = listOf(field("Recovery_1", "1111", protected = true), field("Recovery_2", "2222", protected = true), field("Note", "n")),
        )
        val marked = assertNotNull(UsedCode.changes(entry, 1, used = true, day)).customFields!!
        assertEquals(listOf(field("Recovery_1", "1111", protected = true), field("Recovery_2", "used 2026-10-22 \u00B7 2222", protected = true), field("Note", "n")), marked)
        assertNull(UsedCode.changes(entry, 1, used = false, day), "not used: nothing to take off")
        assertNull(UsedCode.changes(entry, 9, used = true, day))
        val back = UsedCode.changes(entry.copy(customFields = marked), 1, used = false, day)!!.customFields!!
        assertEquals(entry.customFields, back)
        val first = (entry.customFields[0].value as dev.keething.domain.vault.FieldValue.Protected).secret
        (marked[0].value as dev.keething.domain.vault.FieldValue.Protected).secret.wipe()
        assertEquals("1111", first.reveal(), "the change holds copies: wiping it leaves the entry")
        assertEquals(1, RowIds.customIndex(RowIds.custom(1)))
        assertNull(RowIds.customIndex(RowIds.PASSWORD))
    }

    @Test
    fun undoingAMarkPutsBackTheDayItHadAndOffersNoFurtherUndo() {
        val had = java.time.LocalDate.of(2026, 10, 22)
        val today = java.time.LocalDate.of(2026, 11, 20)
        val unmark = UsedCode.Marking(used = false)
        val undo = assertNotNull(unmark.undo(dayItHad = had))
        assertEquals(UsedCode.Marking(used = true, day = had, offersUndo = false), undo)
        assertEquals(had, undo.dayOr(today))
        assertNull(undo.undo(dayItHad = null), "undoing does not offer another Undo")
        val mark = UsedCode.Marking(used = true)
        assertEquals(today, mark.dayOr(today), "a mark of its own is today's")
        assertEquals(UsedCode.Marking(used = false, offersUndo = false), mark.undo(dayItHad = null))
        val entry = Entry("e", customFields = listOf(field("Recovery_1", "1111")))
        val marked = entry.copy(customFields = UsedCode.changes(entry, 0, used = true, had)!!.customFields!!)
        val off = marked.copy(customFields = UsedCode.changes(marked, 0, unmark.used, unmark.dayOr(today))!!.customFields!!)
        val back = UsedCode.changes(off, 0, undo.used, undo.dayOr(today))!!.customFields!!
        assertEquals(marked.customFields, back, "the field as it was, with its day")
    }

    // Field types (Mac SPEC §3.12)

    @Test
    fun typedFieldsAlsoTakeADayAsEightDigitsImpossibleOnesStayText() {
        val d = FieldDate.parse("20271031", typed = true)
        assertEquals(day(2027, 10, 31), d?.date)
        assertEquals(false, d?.isStandard)
        assertNull(FieldDate.parse("20270231", typed = true))
        assertNull(FieldDate.parse("20271031", typed = false), "untyped: only unmistakable forms")
        assertEquals(CalendarDate(2027, 12), FieldDate.parse("202712", typed = true)?.date)
    }

    @Test
    fun aTypedFieldIsWhatItsTypeSaysUntypedOnesAreReadByTheirValue() {
        val t = mapOf("gültig bis" to "until", "Geburtsdatum" to "date", "expiry date" to "until", "Karte" to "until", "Kundennummer" to "text")
        val rows = card(
            listOf(
                field("gültig bis", "2031-05-01"), field("Geburtsdatum", "01.05.1990"), field("expiry date", "202712"),
                field("Spende", "2031-05-01"), field("Vertragsende", "2031-05-01"), field("Karte", "not a date"),
                field("Kundennummer", "2031-05-01"),
            ),
            types = t,
        ).rows
        val a = assertIs<ValueStyle.Date>(rows[0].style).date
        val b = assertIs<ValueStyle.Date>(rows[1].style).date
        val c = assertIs<ValueStyle.Date>(rows[2].style).date
        assertTrue(a.isStandard && !b.isStandard && !c.isStandard)
        assertEquals(CalendarDate(2027, 12), c.date)
        assertEquals(listOf(true, false, true, false, true, false, false), rows.map { it.expiry != null })
        assertIs<ValueStyle.Date>(rows[3].style, "untyped: an ISO date is a date")
        assertEquals(ValueStyle.Text, rows[5].style, "typed, but not a date")
        assertEquals(ValueStyle.Text, rows[6].style, "typed text: nothing is read from it")
        assertEquals("01.05.1990", rows[1].copyText)
        assertEquals(listOf(FieldType.UNTIL, FieldType.DATE, FieldType.UNTIL, null, null, FieldType.UNTIL, FieldType.TEXT), rows.map { it.type })
    }

    @Test
    fun aUrlFieldOpensABareHostTextAndOtherTypesAreNeverLinks() {
        val rows = card(
            listOf(field("Server", "db.example.com"), field("Notiz", "db.example.com"), field("Seite", "https://example.com/a"), field("Plain", "https://example.com/b")),
            types = mapOf("Server" to "url", "Plain" to "text"),
        ).rows
        assertEquals(listOf("https://db.example.com", null, "https://example.com/a", null), rows.map { (it.style as? ValueStyle.Link)?.url })
        assertEquals(ValueStyle.Text, card(listOf(field("Server", "db.example.com", protected = true)), types = mapOf("Server" to "url")).rows[0].style, "a protected value is never read")
    }

    @Test
    fun aSectionTypedCodeIsAListAtAnySizeOneTypedOtherwiseIsNot() {
        val two = listOf(field("Backup_1", "a", protected = true), field("Backup_2", "b", protected = true))
        assertEquals(CardSection.Kind.Codes, card(two, types = mapOf("Backup_1" to "code", "Backup_2" to "code")).sections.last().kind)
        assertEquals(CardSection.Kind.Custom, card(two).sections.last().kind, "untyped: at least 3")
        val three = two + field("Backup_3", "c", protected = true)
        assertEquals(CardSection.Kind.Codes, card(three).sections.last().kind)
        assertEquals(CardSection.Kind.Custom, card(three, types = mapOf("Backup_3" to "text")).sections.last().kind, "a typed field turns the guess off")
    }

    @Test
    fun aTypeThisVersionDoesNotKnowIsKeptAndCountsAsNone() {
        val t = FieldTypes(Entry("e", customFields = listOf(field("a")), fieldTypes = mapOf("a" to "frist", "b" to " UNTIL ")))
        assertNull(t.type("a"))
        assertEquals(mapOf("a" to "frist"), t.unknown)
        assertEquals(FieldType.UNTIL, t.type("b"), "type names ignore case and outer spaces")
        assertNull(t.type("B"), "field names match exactly")
        assertIs<ValueStyle.Date>(card(listOf(field("a", "2031-05-01")), types = mapOf("a" to "frist")).rows[0].style, "read by its value")
    }

    @Test
    fun guessedTypesSayWhatEachFieldWithoutATypeIsReadAs() {
        val guesses = EntryCardBuilder.guessedTypes(
            listOf(
                field("gültig bis", "2031-05-01"), field("Geburtsdatum", "1990-05-01"), field("Site", "https://example.com"),
                field("Kundennummer", "12"), field("R_1"), field("R_2"), field("R_3"), field("X_1"),
            ),
        )
        assertEquals(
            listOf(FieldType.UNTIL, FieldType.DATE, FieldType.URL, FieldType.TEXT, FieldType.CODE, FieldType.CODE, FieldType.CODE, FieldType.TEXT),
            guesses,
        )
    }

    @Test
    fun aPasswordFieldIsMaskedOnTheCardUntilRevealedProtectedOrNot() {
        val rows = card(
            listOf(field("PIN Tresor", "1234"), field("Admin", "s3cret", protected = true), field("Note", "plain")),
            types = mapOf("PIN Tresor" to "password", "Admin" to "Password", "Note" to "text"),
        ).rows
        val pin = rows.first { it.label.english() == "PIN Tresor" }
        assertTrue(pin.isConcealed && !pin.isProtected, "concealed on screen, not protected in the database")
        assertEquals(FieldType.PASSWORD, pin.type)
        assertTrue(rows.first { it.label.english() == "Admin" }.isConcealed)
        assertFalse(rows.first { it.label.english() == "Note" }.isConcealed)
        assertTrue(FieldType.PASSWORD.isPassword && FieldType.WIFI_PASSWORD.isPassword && !FieldType.CARD_PIN.isPassword)
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
        assertEquals(listOf("URL", "URL 2", "URL 3", "URL 4"), credentials.rows.map { it.label.english() })
        // Unlike the Mac card, the URL row itself is a link too when it can be opened (the card's open button).
        assertEquals(
            listOf(ValueStyle.Link("https://one.example"), ValueStyle.Link("https://two.example"), ValueStyle.Link("https://three.example/login"), ValueStyle.Text),
            credentials.rows.map { it.style },
        )
        assertEquals(listOf("Org"), c.sections.filter { it.kind == CardSection.Kind.Custom }.flatMap { s -> s.rows.map { it.label.english() } })
    }

    @Test
    fun anAppAddressIsReadInTheUrlTheKp2aFieldsAndUrlOrUntypedFieldsOnly() {
        val c = card(
            listOf(
                field("KP2A_URL_1", "androidapp://com.example.two"), field("App", "AndroidApp://com.example.three/"),
                field("Typed", "androidapp://com.example.four"), field("Text", "androidapp://com.example.five"),
                field("Secret", "androidapp://com.example.six", protected = true), field("Short", "androidapp://single"),
            ),
            url = "androidapp://com.example.one",
            types = mapOf("Typed" to "url", "Text" to "text"),
        )
        assertEquals(
            listOf("com.example.one", "com.example.two", "com.example.three", "com.example.four", null, null, null),
            c.rows.filter { it.kind == FieldRow.Kind.URL || it.kind == FieldRow.Kind.CUSTOM }.map { it.app?.packageName },
        )
        assertTrue(c.rows.none { it.app != null && it.link != null }, "an app address is not a link for the browser")
    }

    @Test
    fun aFieldsNameShowsAboveTheAppOnlyWhenItIsTheUsersOwn() {
        val c = card(
            listOf(field("KP2A_URL_1", "androidapp://com.example.two"), field("url 3", "androidapp://com.example.three"), field("Bonus card app", "androidapp://com.example.four")),
            url = "androidapp://com.example.one",
        )
        assertEquals(listOf(null, null, null, "Bonus card app"), c.rows.mapNotNull { it.app }.map { it.fieldName })
    }

    @Test
    fun anAppAddressCopiesAsStored() {
        val row = card(listOf(field("App", " AndroidApp://com.example.app/ ")), url = "androidapp://com.example.one").rows
            .filter { it.app != null }
        assertEquals(listOf("androidapp://com.example.one", " AndroidApp://com.example.app/ "), row.map { it.clipboardText() })
        assertTrue(row.all { it.isCopyable })
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
