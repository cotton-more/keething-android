package dev.keething.card

import dev.keething.domain.text.UserText
import dev.keething.domain.text.english
import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac `PresentationTests` expectations for the card, the reveal state, codes, links and avatars. */
class EntryCardTest {
    private val sha1 = "12345678901234567890"

    private fun richEntry() = Entry(
        "gh", title = "GitHub", username = "octocat", password = Secret.of("gh-secret"), url = "https://github.com/login",
        notes = "Work account\nsecond line", groupPath = listOf("Work", "Git"), tags = listOf("dev"),
        customFields = listOf(
            CustomField.protected("Recovery code", "abcd-efgh"),
            CustomField.plain("Org", "Acme GmbH"),
            CustomField.plain("Empty field", ""),
        ),
        totp = TotpConfig(Secret.of(sha1)),
        attachments = listOf(Attachment("scan.pdf", 1_500_000), Attachment("mystery.bin", null)),
        lastModified = Instant.parse("2026-10-03T10:00:00Z"),
        created = Instant.parse("2019-03-12T10:00:00Z"),
    )

    private fun build(entry: Entry) = EntryCardBuilder.build(entry, now, Locale.ENGLISH)

    private fun FieldRow.shown(reveal: RevealState) = reveal.displayValue(this)

    // spec: card.passkey/AC2
    @Test
    fun aPasskeyIsOneRowAfterTheCodeWithItsSiteAndUserNothingToShowOrCopy() {
        val passkey = listOf(
            CustomField.plain("KPEX_PASSKEY_USERNAME", "alice"),
            CustomField.protected("KPEX_PASSKEY_CREDENTIAL_ID", "kLYSiHc0RiozVthp6tdefQ"),
            CustomField.protected("KPEX_PASSKEY_PRIVATE_KEY_PEM", "-----BEGIN PRIVATE KEY-----\nMIG\n-----END PRIVATE KEY-----"),
            CustomField.plain("KPEX_PASSKEY_RELYING_PARTY", "webauthn.io"),
            CustomField.protected("KPEX_PASSKEY_USER_HANDLE", "ZOwzMXc7UnUbMNun4ut4Vg"),
            CustomField.plain("KPEX_PASSKEY_FLAG_BE", "1"),
        )
        val card = build(richEntry().copy(passkeyFields = passkey))

        assertEquals(
            listOf("username", "password", "url", "totp", "passkey", "notes", "custom-0", "custom-1", "custom-2", "attachment-0", "attachment-1"),
            card.rows.map { it.id },
        )
        val row = card.rows.single { it.id == RowIds.PASSKEY }
        assertEquals(FieldRow.Kind.PASSKEY, row.kind)
        assertEquals("Passkey", row.label.english())
        assertEquals("webauthn.io · alice", row.shown(RevealState()))
        assertFalse(row.isCopyable || row.isConcealed || row.link != null, "nothing to show, copy or open")
        assertEquals(CardSection.Kind.Credentials, card.sections.single { row in it.rows }.kind)

        val siteOnly = build(Entry("p", title = "Site", passkeyFields = passkey.filter { it.name != "KPEX_PASSKEY_USERNAME" }))
        assertEquals("webauthn.io", siteOnly.rows.single().shown(RevealState()), "an empty part is left out")
        assertEquals(listOf("passkey"), siteOnly.rows.map { it.id })
    }

    // spec: card.passkey/AC3
    @Test
    fun noRowOfTheCardHoldsThePasskeysSecrets() {
        val card = build(
            Entry(
                "p", title = "webauthn.io (Passkey)", username = "alice", url = "https://webauthn.io", tags = listOf("Passkey"),
                passkeyFields = listOf(
                    CustomField.plain("KPEX_PASSKEY_RELYING_PARTY", "webauthn.io"),
                    CustomField.protected("KPEX_PASSKEY_CREDENTIAL_ID", "credential-id"),
                    CustomField.protected("KPEX_PASSKEY_PRIVATE_KEY_PEM", "private-key"),
                    CustomField.protected("KPEX_PASSKEY_USER_HANDLE", "user-handle"),
                ),
            ),
        )
        val everything = card.rows.flatMap { listOf(it.storedText(), it.revealedText(), it.label.english()) } + card.customFieldNames
        for (secret in listOf("credential-id", "private-key", "user-handle", "KPEX")) {
            assertTrue(everything.none { secret in it }, "$secret is nowhere on the card")
        }
    }

    @Test
    fun rowsAreUsernamePasswordUrlCodeNotesCustomFieldsAttachments() {
        val card = build(richEntry())

        assertEquals("GitHub", card.title.english())
        assertEquals(listOf("Work", "Git"), card.groupPath)
        assertEquals("Git", card.groupName)
        assertEquals(
            listOf("username", "password", "url", "totp", "notes", "custom-0", "custom-1", "custom-2", "attachment-0", "attachment-1"),
            card.rows.map { it.id },
        )
        assertEquals(
            listOf("Username", "Password", "URL", "One-time code", "Notes", "Recovery code", "Org", "Empty field", "scan.pdf", "mystery.bin"),
            card.rows.map { it.label.english() },
        )
        assertEquals(Instant.parse("2026-10-03T10:00:00Z"), card.lastModified)
        assertEquals(Instant.parse("2019-03-12T10:00:00Z"), card.created)
    }

    @Test
    fun emptyStandardFieldsAreOmittedCustomFieldsNeverAre() {
        val card = build(Entry("1", title = "T", customFields = listOf(CustomField.plain("Empty", ""))))

        assertEquals(listOf("custom-0"), card.rows.map { it.id })
    }

    @Test
    fun protectionAndCopyability() {
        val rows = build(richEntry()).rows.associateBy { it.id }

        assertTrue(rows.getValue("password").isProtected)
        assertTrue(rows.getValue("custom-0").isProtected)
        assertFalse(rows.getValue("username").isProtected)
        assertFalse(rows.getValue("custom-1").isProtected)
        assertFalse(rows.getValue("totp").isProtected)
        assertTrue(rows.getValue("username").isCopyable)
        assertTrue(rows.getValue("totp").isCopyable)
        assertFalse(rows.getValue("custom-2").isCopyable, "an empty value has nothing to copy")
        assertFalse(rows.getValue("attachment-0").isCopyable, "attachments are opened or saved, not copied")
    }

    @Test
    fun attachmentRowsCarryTheirSize() {
        val rows = build(richEntry()).rows.associateBy { it.id }

        assertEquals(1_500_000, rows.getValue("attachment-0").size)
        assertNull(rows.getValue("attachment-1").size)
        assertEquals(FieldRow.Kind.ATTACHMENT, rows.getValue("attachment-0").kind)
    }

    @Test
    fun theCodeRowCarriesItsConfigurationNotAValue() {
        val totp = build(richEntry()).rows.single { it.kind == FieldRow.Kind.TOTP }

        assertEquals(TotpConfig(Secret.of(sha1)), totp.totp)
        assertTrue(build(Entry("1", title = "x")).rows.none { it.kind == FieldRow.Kind.TOTP })
    }

    @Test
    fun aCodeThatCannotBeUsedShowsItsRowWithTheReasonAndNothingToCopy() {
        val steam = CustomField.protected("otp", "otpauth://totp/Steam:alex?secret=GEZDGNBVGY3TQOJQ&encoder=steam")
        val entry = Entry("1", title = "Steam", codeFields = listOf(steam), hasCodeFields = true)
        val row = build(entry).rows.single { it.kind == FieldRow.Kind.TOTP }
        assertNull(row.totp)
        assertEquals("This code cannot be used here: Steam codes.", row.codeProblem?.english())
        assertFalse(row.isCopyable)
        assertEquals("One-time code", row.label.english())
        assertTrue(build(entry).rows.none { it.kind == FieldRow.Kind.CUSTOM }, "the code field stays apart (kdbx.otp-storage AC2)")
        assertNull(build(richEntry()).rows.single { it.kind == FieldRow.Kind.TOTP }.codeProblem, "a live code has none")
    }

    @Test
    fun entryExpiry() {
        val day = Duration.ofDays(1)
        assertNull(build(Entry("1")).entryExpiry)
        assertNull(build(Entry("1", expires = true)).entryExpiry)
        assertEquals(EntryExpiry.Expired(now - day, ExpiryHint(ExpiryHint.Status.EXPIRED, UserText.of(CardText.EXPIRED_YESTERDAY))), build(Entry("1", expires = true, expiryTime = now - day)).entryExpiry)
        assertEquals(EntryExpiry.Expires(now + day, ExpiryHint(ExpiryHint.Status.SOON, UserText.of(CardText.EXPIRES_TOMORROW))), build(Entry("1", expires = true, expiryTime = now + day)).entryExpiry)
        assertEquals("Expired today", build(Entry("1", expires = true, expiryTime = now - Duration.ofSeconds(1))).entryExpiry?.hint?.text?.english(), "past its time on its last day")
    }

    // Reveal state

    @Test
    fun protectedValuesAreMaskedOthersShown() {
        val rows = build(richEntry()).rows.associateBy { it.id }
        val reveal = RevealState()

        assertEquals(RevealState.MASK, rows.getValue("password").shown(reveal))
        assertEquals(RevealState.MASK, rows.getValue("custom-0").shown(reveal))
        assertEquals("octocat", rows.getValue("username").shown(reveal))
        assertEquals("Acme GmbH", rows.getValue("custom-1").shown(reveal))
    }

    @Test
    fun theMaskDoesNotLeakTheLength() {
        fun row(value: String) = FieldRow("a", FieldRow.Kind.PASSWORD, UserText.plain("P"), dev.keething.domain.vault.FieldValue.Protected(Secret.of(value)), noticeLabel = UserText.plain("P"))
        assertEquals(RevealState().displayValue(row("x")), RevealState().displayValue(row("x".repeat(64))))
        assertEquals("", RevealState().displayValue(row("")), "nothing to hide in an empty value")
    }

    @Test
    fun togglingOneRowAllRowsAndReset() {
        val rows = build(richEntry()).rows
        val byId = rows.associateBy { it.id }
        var reveal = RevealState()

        reveal = reveal.toggle("password")
        assertEquals("gh-secret", byId.getValue("password").shown(reveal))
        assertEquals(RevealState.MASK, byId.getValue("custom-0").shown(reveal))
        reveal = reveal.toggle("password")
        assertEquals(RevealState.MASK, byId.getValue("password").shown(reveal))

        reveal = reveal.toggleAll(rows)
        assertEquals("gh-secret", byId.getValue("password").shown(reveal))
        assertEquals("abcd-efgh", byId.getValue("custom-0").shown(reveal))
        reveal = reveal.toggleAll(rows)
        assertEquals(RevealState.MASK, byId.getValue("custom-0").shown(reveal))

        reveal = RevealState().toggle("password").toggleAll(rows)
        assertEquals("abcd-efgh", byId.getValue("custom-0").shown(reveal), "toggle all with one revealed reveals the rest")
    }

    @Test
    fun aReadAgainKeepsARowRevealedOnlyWhenItIsTheSameField() {
        val pin = CustomField.protected("PIN", "1234")
        val puk = CustomField.protected("PUK", "99887766")
        val before = build(Entry("1", password = Secret.of("pw"), customFields = listOf(pin, puk)))
        val reveal = RevealState().toggle(RowIds.PASSWORD).toggle(RowIds.custom(1))
        val synced = build(Entry("1", password = Secret.of("pw2"), customFields = listOf(CustomField.protected("Card", "4711"), pin, puk)))
        val kept = reveal.keptOn(before, synced)
        assertTrue(kept.isRevealed(RowIds.PASSWORD), "not a custom field: kept")
        assertFalse(kept.isRevealed(RowIds.custom(1)), "now PIN, revealed was PUK")
        assertEquals(RevealState.MASK, kept.displayValue(synced.rows.single { it.id == RowIds.custom(1) }))
        val same = build(Entry("1", customFields = listOf(pin, CustomField.protected("PUK", "11112222"))))
        assertTrue(reveal.keptOn(before, same).isRevealed(RowIds.custom(1)), "the same field with a new value stays shown")
        assertFalse(reveal.keptOn(before, build(Entry("1", customFields = listOf(pin)))).isRevealed(RowIds.custom(1)), "the field gone")
    }

    // One-time codes

    @Test
    fun codeDisplayGroupsAndCountsDown() {
        val d8 = TotpDisplay.of(TotpConfig(Secret.of(sha1), digits = 8), Instant.ofEpochSecond(59))
        assertEquals("94287082", d8.code)
        assertEquals("9428 7082", d8.formatted)
        assertEquals(1, d8.secondsRemaining)
        assertEquals(30, d8.period)
        val d6 = TotpDisplay.of(TotpConfig(Secret.of(sha1)), Instant.ofEpochSecond(59))
        assertEquals("287082", d6.code)
        assertEquals("287 082", d6.formatted)
    }

    @Test
    fun theNextCodeIsTheOneTheNextPeriodStartsWith() {
        val c = TotpConfig(Secret.of(sha1))
        val now = TotpDisplay.of(c, Instant.ofEpochSecond(45))
        assertEquals(TotpDisplay.of(c, Instant.ofEpochSecond(60)).code, now.next)
        assertEquals(TotpDisplay.of(c, Instant.ofEpochSecond(89)).code, now.next, "the whole next period")
        assertEquals(now.next.substring(0, 3) + " " + now.next.substring(3), now.nextFormatted)
        assertEquals(TotpDisplay.of(c, Instant.ofEpochSecond(90)).code, TotpDisplay.of(c, Instant.ofEpochSecond(89)).next, "in a code's last second")
    }

    @Test
    fun codeProgressGoesFromFullToEmpty() {
        val c = TotpConfig(Secret.of(sha1))
        assertEquals(1.0, TotpDisplay.of(c, Instant.ofEpochSecond(60)).progress)
        assertEquals(0.5, TotpDisplay.of(c, Instant.ofEpochSecond(75)).progress)
        assertEquals(1.0 / 30.0, TotpDisplay.of(c, Instant.ofEpochSecond(89)).progress)
    }

    // Links

    @Test
    fun onlyHttpAndHttpsOpenSchemelessHostsGetHttps() {
        val ok = listOf(
            "https://github.com/login" to "https://github.com/login",
            "HTTP://Example.com" to "HTTP://Example.com",
            "  https://a.example/x?y=1  " to "https://a.example/x?y=1",
            "github.com" to "https://github.com",
            "www.example.org/path?q=1" to "https://www.example.org/path?q=1",
            "localhost.example:8080/a" to "https://localhost.example:8080/a",
        )
        for ((input, expected) in ok) assertEquals(expected, OpenableUrl.of(input), input)
        for (bad in listOf(
            "", "   ", "javascript:alert(1)", "file:///etc/passwd", "ssh://host", "ftp://host/x", "mailto:a@b.c",
            "not a url", "https://", "data:text/html,hi", "x",
        )) {
            assertNull(OpenableUrl.of(bad), "should refuse '$bad'")
        }
    }

    // Avatars

    @Test
    fun theInitialIsTheFirstLetterOrDigitUppercased() {
        assertEquals("G", EntryAvatar.of("GitHub").initial)
        assertEquals("N", EntryAvatar.of("  nalogia.ru").initial)
        assertEquals("П", EntryAvatar.of("Пятёрочка").initial)
        assertEquals("S", EntryAvatar.of("süwag").initial)
        assertEquals("1", EntryAvatar.of("123 abc").initial)
        assertEquals("S", EntryAvatar.of("*** secure").initial)
        assertEquals("S", EntryAvatar.of("🙂 smile").initial)
        assertEquals("•", EntryAvatar.of("").initial)
        assertEquals("•", EntryAvatar.of("   ").initial)
        assertEquals("•", EntryAvatar.of("***").initial)
    }

    @Test
    fun theColourIsStablePinnedAndSpread() {
        assertEquals(EntryAvatar.of("GitHub").colorIndex, EntryAvatar.of("  GITHUB ").colorIndex)
        // Pinned on the Mac so an entry keeps its colour on both.
        assertEquals(2, EntryAvatar.of("GitHub").colorIndex)
        assertEquals(7, EntryAvatar.of("nalogia.ru").colorIndex)
        assertEquals(4, EntryAvatar.of("Пятёрочка").colorIndex)
        val indices = ((0 until 60).map { "entry number $it" } + listOf("GitHub", "Bank", "Mail", "Steam", "Süwag", "Пятёрочка"))
            .map { EntryAvatar.of(it).colorIndex }
        assertTrue(indices.all { it in 0 until EntryAvatar.PALETTE_SIZE })
        assertTrue(indices.toSet().size >= 6)
        assertEquals(EntryAvatar.NEUTRAL_INDEX, EntryAvatar.of("").colorIndex)
    }

    @Test
    fun cardsUseTheDisplayTitle() {
        val untitled = Entry("1", title = "", url = "https://www.example.org/x")

        assertEquals(EntryAvatar.of("www.example.org"), build(untitled).avatar)
        assertNotEquals(EntryAvatar.of(""), build(untitled).avatar)
    }
}
