package dev.keething.card

import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.FieldValue
import java.time.Instant
import java.util.Locale

/** 2027-01-15 08:00 UTC, as in the Mac tests. */
internal val now: Instant = Instant.ofEpochSecond(1_800_000_000)
internal val de: Locale = Locale.GERMANY

internal fun field(name: String, value: String = "x", protected: Boolean = false) =
    if (protected) CustomField.protected(name, value) else CustomField.plain(name, value)

/**
 * The entry with the types [rules] gives its fields (`<field name> = <type>` per line, the name matched ignoring
 * case), as the editor would set them (Mac test `typed`).
 */
internal fun typed(entry: Entry, rules: String): Entry {
    val types = entry.fieldTypes.toMutableMap()
    for (line in rules.lines()) {
        val eq = line.lastIndexOf('=')
        if (eq < 0) continue
        val name = line.substring(0, eq).trim().lowercase()
        val type = line.substring(eq + 1).trim()
        for (field in entry.customFields) if (field.name.lowercase() == name) types[field.name] = type
    }
    return entry.copy(fieldTypes = types)
}

internal fun day(year: Int, month: Int, day: Int) = CalendarDate(year, month, day)

/** What copying a row puts on the clipboard, as text (tests only). */
internal val FieldRow.copyText: String
    get() = when (val v = value) {
        is FieldValue.Plain -> v.text
        is FieldValue.Protected -> v.secret.reveal()
    }
