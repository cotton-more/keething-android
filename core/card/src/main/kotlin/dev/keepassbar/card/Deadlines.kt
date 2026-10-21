package dev.keepassbar.card

import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.Vault
import java.text.Collator
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * A date something ends on (Mac `Deadline`): an `until` / `card.expiry` field (and an untyped date in a field
 * named like an end), or KeePass's own entry expiry.
 */
public data class Deadline(
    val id: String,
    val source: Source,
    val entryId: String,
    val entryTitle: String,
    val groupPath: List<String>,
    /** The field ("VERTRAG › Ende Mindestlaufzeit"), or "Entry expires" for KeePass's own expiry. */
    val label: String,
    val date: CalendarDate,
    val hint: ExpiryHint,
) {
    public sealed interface Source {
        public data object EntryExpiry : Source

        /** The custom field at this index. */
        public data class Field(val index: Int) : Source
    }
}

public object DeadlineList {
    /** Every deadline of the vault, earliest first, then by title. */
    public fun build(vault: Vault, now: Instant): List<Deadline> {
        val byTitle = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }
        return vault.entries.flatMap { deadlines(it, FieldInterpreter(it), now) }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<Deadline>> { it.value.date }
                    .thenComparator { a, b -> byTitle.compare(a.value.entryTitle, b.value.entryTitle) }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    /** How each entry's deadlines stand, for the list: only entries with one expired or due soon. */
    public fun statusByEntry(vault: Vault, now: Instant): Map<String, ExpiryHint.Status> =
        build(vault, now).groupBy { it.entryId }.mapNotNull { (id, deadlines) -> worstStatus(deadlines)?.let { id to it } }.toMap()

    /** The most pressing state of some deadlines: expired before soon; null when all are fine (or none). */
    public fun worstStatus(deadlines: List<Deadline>): ExpiryHint.Status? = when {
        deadlines.any { it.hint.status == ExpiryHint.Status.EXPIRED } -> ExpiryHint.Status.EXPIRED
        deadlines.any { it.hint.status == ExpiryHint.Status.SOON } -> ExpiryHint.Status.SOON
        else -> null
    }

    internal fun deadlines(entry: Entry, interpreter: FieldInterpreter, now: Instant): List<Deadline> {
        val out = mutableListOf<Deadline>()
        val expiry = entry.expiryTime
        if (entry.expires && expiry != null) {
            val utc = expiry.atZone(ZoneOffset.UTC)
            CalendarDate.valid(utc.year, utc.monthValue, utc.dayOfMonth)?.let { date ->
                out += Deadline(
                    "${entry.id}/expiry", Deadline.Source.EntryExpiry, entry.id, entry.displayTitle, entry.groupPath,
                    "Entry expires", date, FieldDate(date, true).expiry(now),
                )
            }
        }
        for ((i, field) in entry.customFields.withIndex()) {
            val (date, ends) = interpreter.date(field) ?: continue
            if (!ends) continue
            out += Deadline(
                "${entry.id}/$i", Deadline.Source.Field(i), entry.id, entry.displayTitle, entry.groupPath,
                ImportedFieldName.displayName(field.name), date.date, date.expiry(now),
            )
        }
        return out
    }
}
