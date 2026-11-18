package dev.keething.card

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * A code of a list (recovery codes) marked as used (SPEC §6.2): the mark is written in front of the code, in the
 * field's value, `used 2026-10-22 · 1234-5678`, so every app shows it as it is and a field renamed anywhere keeps
 * it; taking the mark off gives the code back unchanged. "used" in any case; the day in ISO 8601; then " · ".
 */
public object UsedCode {
    public data class Parsed(val day: LocalDate, val code: String)

    private val PATTERN = Regex("^used (\\d{4}-\\d{2}-\\d{2}) · (.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** The day and the code of a value marked used; null for a value that is not. */
    public fun parse(value: String): Parsed? {
        val match = PATTERN.matchEntire(value) ?: return null
        val day = try {
            LocalDate.parse(match.groupValues[1])
        } catch (_: DateTimeParseException) {
            return null
        }
        return Parsed(day, match.groupValues[2])
    }

    /** [value] marked used on [day]; a value marked already keeps its mark. */
    public fun mark(value: String, day: LocalDate): String = if (parse(value) != null) value else "used $day · $value" // not user text

    /** [value] without its mark: the code as it was. */
    public fun unmark(value: String): String = parse(value)?.code ?: value

    /**
     * The change that marks the custom field at [index] of [entry] used on [day] ([used]), or takes the mark off:
     * an ordinary edit of its value, protected as it was; null when there is nothing to change. Every field's
     * secret in it is a copy of its own: wipe the change once its save is done.
     */
    public fun changes(entry: Entry, index: Int, used: Boolean, day: LocalDate): EntryChanges? {
        val field = entry.customFields.getOrNull(index) ?: return null
        val text = field.text()
        val changed = if (used) mark(text, day) else unmark(text)
        if (changed == text) return null
        val fields = entry.customFields.mapIndexed { i, f ->
            val value = when {
                i == index && f.isProtected -> FieldValue.Protected(Secret.of(changed))
                i == index -> FieldValue.Plain(changed)
                else -> when (val v = f.value) {
                    is FieldValue.Protected -> FieldValue.Protected(v.secret.copy())
                    is FieldValue.Plain -> v
                }
            }
            CustomField(f.name, value)
        }
        return EntryChanges(customFields = fields)
    }

    private fun CustomField.text(): String = when (val v = value) {
        is FieldValue.Plain -> v.text
        is FieldValue.Protected -> v.secret.reveal()
    }
}
