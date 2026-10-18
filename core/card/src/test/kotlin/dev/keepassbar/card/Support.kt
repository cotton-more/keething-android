package dev.keepassbar.card

import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.FieldValue
import java.time.Instant
import java.util.Locale

/** 2027-01-15 08:00 UTC, as in the Mac tests. */
internal val now: Instant = Instant.ofEpochSecond(1_800_000_000)
internal val de: Locale = Locale.GERMANY

internal fun field(name: String, value: String = "x", protected: Boolean = false) =
    if (protected) CustomField.protected(name, value) else CustomField.plain(name, value)

internal fun settings(notes: String, fields: List<CustomField> = emptyList()) =
    Entry("kb", title = "KeePassBar", notes = notes, customFields = fields)

internal fun types(notes: String, fields: List<CustomField> = emptyList()) = FieldTypes.from(settings(notes, fields))

internal fun day(year: Int, month: Int, day: Int) = CalendarDate(year, month, day)

/** What copying a row puts on the clipboard, as text (tests only). */
internal val FieldRow.copyText: String
    get() = when (val v = value) {
        is FieldValue.Plain -> v.text
        is FieldValue.Protected -> v.secret.reveal()
    }
