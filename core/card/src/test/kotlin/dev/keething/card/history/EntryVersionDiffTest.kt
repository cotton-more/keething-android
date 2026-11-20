package dev.keething.card.history

import dev.keething.domain.secret.Secret
import dev.keething.domain.text.english
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.FieldValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What changed between versions (the Mac `HistoryTests` expectations for `EntryVersionDiff` and the history model). */
class EntryVersionDiffTest {
    private fun field(name: String, value: String, protected: Boolean = false) =
        CustomField(name, if (protected) FieldValue.Protected(Secret.of(value)) else FieldValue.Plain(value))

    private val old = Entry(
        "e", title = "Mail", username = "me", password = Secret.of("old"), url = "https://mail.example", notes = "n",
        tags = listOf("a"),
        customFields = listOf(field("PIN", "1", protected = true), field("Gone", "x"), field("Note", "same")),
        attachments = listOf(Attachment("a.txt", 10), Attachment("b.txt", 5)),
        lastModified = Instant.ofEpochSecond(100),
    )

    @Test
    fun fieldsCustomFieldsTagsAndAttachmentsProtectedOnesMarked() {
        val new = old.copy(
            title = "Mail 2", password = Secret.of("new"), notes = "", tags = listOf("a", "b"),
            customFields = listOf(field("Note", "same", protected = true), field("PIN", "2", protected = true), field("Empty", "")),
            attachments = listOf(Attachment("a.txt", 12), Attachment("c.txt", 1)),
        )

        val changes = EntryVersionDiff.changes(old, new)

        assertEquals(
            listOf("Title", "Password", "Notes", "Note", "PIN", "Empty", "Gone", "Tags", "Attachment a.txt", "Attachment c.txt", "Attachment b.txt"),
            changes.map { it.field.english() },
        )
        val c = VersionChange.Kind.CHANGED
        val a = VersionChange.Kind.ADDED
        val r = VersionChange.Kind.REMOVED
        assertEquals(listOf(c, c, r, c, c, a, r, c, c, a, r), changes.map { it.kind })
        assertEquals(Said("Password", c, "old", "new", isProtected = true), changes[1].english())
        assertEquals("not protected" to "protected", changes[3].old?.english() to changes[3].new?.english())
        assertTrue(changes[4].isProtected)
        assertEquals("a, b", changes[7].new?.english())
        assertEquals("10 bytes" to "12 bytes", changes[8].old?.english() to changes[8].new?.english())
        assertEquals(emptyList(), EntryVersionDiff.changes(old, old))

        val reordered = old.copy(customFields = listOf(old.customFields[2], old.customFields[0], old.customFields[1]))
        assertEquals(listOf("Custom field order"), EntryVersionDiff.changes(old, reordered).map { it.field.english() })
    }

    @Test
    fun aFieldsTypeExpiryAndTheOneTimeCodeWithoutItsValues() {
        val dated = old.copy(customFields = listOf(field("Ablauf", "2027-10-31")), fieldTypes = mapOf("Ablauf" to "until"))
        val retyped = dated.copy(fieldTypes = mapOf("Ablauf" to "date"), expires = true, expiryTime = Instant.parse("2027-01-02T10:00:00Z"),
            totp = TotpConfig(Secret.of("12345678901234567890")))

        val changes = EntryVersionDiff.changes(dated, retyped, day = { "2 Jan 2027" })

        assertEquals(
            listOf(
                Said("Ablauf", VersionChange.Kind.CHANGED, "type Date until (expiry)", "type Date", isProtected = false),
                Said("Expires", VersionChange.Kind.ADDED, null, "2 Jan 2027", isProtected = false),
                Said("One-time code", VersionChange.Kind.ADDED, null, null, isProtected = true),
            ),
            changes.map { it.english() },
        )
    }

    @Test
    fun aChangeOfTheCodeFieldsIsAChangeOfTheCodeWithoutItsValues() {
        fun steam(link: String) = old.copy(hasCodeFields = true, codeFields = listOf(field("otp", link, protected = true)))
        val before = steam("otpauth://totp/S?secret=JBSWY3DPEHPK3PXP&encoder=steam")
        val code = Said("One-time code", VersionChange.Kind.CHANGED, null, null, isProtected = true)

        assertEquals(listOf(code), EntryVersionDiff.changes(before, steam("otpauth://totp/S?secret=JBSWY3DPEHPK3PXQ&encoder=steam")).map { it.english() })
        assertEquals(listOf(code.copy(kind = VersionChange.Kind.REMOVED)), EntryVersionDiff.changes(before, old).map { it.english() })
        assertEquals(listOf(code.copy(kind = VersionChange.Kind.ADDED)), EntryVersionDiff.changes(old, before).map { it.english() })
        assertEquals(emptyList(), EntryVersionDiff.changes(before, steam("otpauth://totp/S?secret=JBSWY3DPEHPK3PXP&encoder=steam")))
    }

    @Test
    fun aValueTheCardMasksIsMaskedInEitherVersionUnprotected() {
        val xprv = "xprv9s21ZrQH143K3h3fDYiay8mocZ3afhfULfb5GX8kCBdno77K4HiA15Tg23wpbeF1pLfs1c5SPmYHrEpTuuRhxMwvKDwqdKiGJS9XFKzUsAF"
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----"
        val before = old.copy(
            customFields = listOf(field("Password 2", "first"), field("Code", "x"), field("Key", pem), field("Wallet key", "xpub-old"), field("Plain", "a")),
            fieldTypes = mapOf("Password 2" to "password", "Wallet key" to "crypto.xpub"),
        )
        val after = before.copy(
            customFields = listOf(
                field("Password 2", "second"), field("Code", "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP"), field("Key", "gone"),
                field("Wallet key", xprv), field("Plain", "b"),
            ),
        )

        val masked = EntryVersionDiff.changes(before, after).associate { it.field.english() to it.isProtected }

        assertEquals(mapOf("Password 2" to true, "Code" to true, "Key" to true, "Wallet key" to true, "Plain" to false), masked)
    }

    @Test
    fun theVersionsNewestFirstEachWithWhatItChangedNothingBeforeTheOldest() {
        val middle = old.copy(title = "Mail 2", lastModified = Instant.ofEpochSecond(200))
        val current = middle.copy(password = Secret.of("newest"), lastModified = Instant.ofEpochSecond(300))

        val versions = HistoryVersions.of(listOf(old, middle, current))

        assertEquals(listOf(2, 1, 0), versions.map { it.id })
        assertEquals(listOf(true, false, false), versions.map { it.isCurrent })
        assertEquals(listOf("Password"), versions[0].changes?.map { it.field.english() })
        assertEquals(listOf("Title"), versions[1].changes?.map { it.field.english() })
        assertNull(versions[2].changes, "nothing before the oldest")
    }
}

/** A change in the app's English: the words the history shows. */
private data class Said(val field: String, val kind: VersionChange.Kind, val old: String?, val new: String?, val isProtected: Boolean)

private fun VersionChange.english() = Said(field.english(), kind, old?.english(), new?.english(), isProtected)
