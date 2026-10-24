package dev.keething.card.history

import dev.keething.domain.secret.Secret
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
            changes.map { it.field },
        )
        val c = VersionChange.Kind.CHANGED
        val a = VersionChange.Kind.ADDED
        val r = VersionChange.Kind.REMOVED
        assertEquals(listOf(c, c, r, c, c, a, r, c, c, a, r), changes.map { it.kind })
        assertEquals(VersionChange("Password", c, "old", "new", isProtected = true), changes[1])
        assertEquals("not protected" to "protected", changes[3].old to changes[3].new)
        assertTrue(changes[4].isProtected)
        assertEquals("a, b", changes[7].new)
        assertEquals("10 bytes" to "12 bytes", changes[8].old to changes[8].new)
        assertEquals(emptyList(), EntryVersionDiff.changes(old, old))

        val reordered = old.copy(customFields = listOf(old.customFields[2], old.customFields[0], old.customFields[1]))
        assertEquals(listOf("Custom field order"), EntryVersionDiff.changes(old, reordered).map { it.field })
    }

    @Test
    fun aFieldsTypeExpiryAndTheOneTimeCodeWithoutItsValues() {
        val dated = old.copy(customFields = listOf(field("Ablauf", "2027-10-31")), fieldTypes = mapOf("Ablauf" to "until"))
        val retyped = dated.copy(fieldTypes = mapOf("Ablauf" to "date"), expires = true, expiryTime = Instant.parse("2027-01-02T10:00:00Z"),
            totp = TotpConfig(Secret.of("12345678901234567890")))

        val changes = EntryVersionDiff.changes(dated, retyped, day = { "2 Jan 2027" })

        assertEquals(
            listOf(
                VersionChange("Ablauf", VersionChange.Kind.CHANGED, "type Date until (expiry)", "type Date", isProtected = false),
                VersionChange("Expires", VersionChange.Kind.ADDED, null, "2 Jan 2027", isProtected = false),
                VersionChange("One-time code", VersionChange.Kind.ADDED, null, null, isProtected = true),
            ),
            changes,
        )
    }

    @Test
    fun theVersionsNewestFirstEachWithWhatItChangedNothingBeforeTheOldest() {
        val middle = old.copy(title = "Mail 2", lastModified = Instant.ofEpochSecond(200))
        val current = middle.copy(password = Secret.of("newest"), lastModified = Instant.ofEpochSecond(300))

        val versions = HistoryVersions.of(listOf(old, middle, current))

        assertEquals(listOf(2, 1, 0), versions.map { it.id })
        assertEquals(listOf(true, false, false), versions.map { it.isCurrent })
        assertEquals(listOf("Password"), versions[0].changes?.map { it.field })
        assertEquals(listOf("Title"), versions[1].changes?.map { it.field })
        assertNull(versions[2].changes, "nothing before the oldest")
    }
}
