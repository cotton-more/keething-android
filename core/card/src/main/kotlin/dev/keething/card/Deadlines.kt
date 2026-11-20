package dev.keething.card

import dev.keething.domain.text.UserText
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * A date something ends on (Mac `Deadline`): an `until` / `card.expiry` field (and an untyped date in a field
 * named like an end), or KeePass's own entry expiry.
 */
public data class Deadline(
    val id: String,
    val source: Source,
    val entryId: String,
    /** What deadlines of one day are ordered by: the entry's [dev.keething.domain.vault.Entry.sortTitle]. */
    val entryTitle: String,
    val groupPath: List<String>,
    /** The field ("VERTRAG › Ende Mindestlaufzeit"), or "Entry expires" for KeePass's own expiry. */
    val label: UserText,
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
    /** Every deadline of the vault, earliest first, then by title; days are [zone]'s (the phone's). */
    public fun build(vault: Vault, now: Instant, zone: ZoneId): List<Deadline> {
        val byTitle = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }
        return vault.entries.flatMap { deadlines(it, FieldInterpreter(it), now, zone) }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<Deadline>> { it.value.date }
                    .thenComparator { a, b -> byTitle.compare(a.value.entryTitle, b.value.entryTitle) }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    /** How each entry's deadlines stand, for the list: only entries with one expired or due soon. */
    public fun statusByEntry(vault: Vault, now: Instant, zone: ZoneId): Map<String, ExpiryHint.Status> =
        build(vault, now, zone).groupBy { it.entryId }.mapNotNull { (id, deadlines) -> worstStatus(deadlines)?.let { id to it } }.toMap()

    /** The most pressing state of some deadlines: expired before soon; null when all are fine (or none). */
    public fun worstStatus(deadlines: List<Deadline>): ExpiryHint.Status? = when {
        deadlines.any { it.hint.status == ExpiryHint.Status.EXPIRED } -> ExpiryHint.Status.EXPIRED
        deadlines.any { it.hint.status == ExpiryHint.Status.SOON } -> ExpiryHint.Status.SOON
        else -> null
    }

    /** KeePass's own expiry, an instant, as the day it is in [zone]: the day the editor set and the banner shows. */
    internal fun entryExpiryDate(expiry: Instant, zone: ZoneId): CalendarDate? =
        expiry.atZone(zone).let { CalendarDate.valid(it.year, it.monthValue, it.dayOfMonth) }

    /** KeePass's own expiry in a field's words (card.dates AC8); past its time on its last day, "Expired today". */
    internal fun entryExpiryHint(date: CalendarDate, expired: Boolean, now: Instant, zone: ZoneId): ExpiryHint {
        val hint = FieldDate(date, true).expiry(now, zone)
        return if (expired && hint.status != ExpiryHint.Status.EXPIRED) ExpiryHint(ExpiryHint.Status.EXPIRED, UserText.of(CardText.EXPIRED_TODAY)) else hint
    }

    internal fun deadlines(entry: Entry, interpreter: FieldInterpreter, now: Instant, zone: ZoneId): List<Deadline> {
        val out = mutableListOf<Deadline>()
        val expiry = entry.expiryTime
        if (entry.expires && expiry != null) {
            entryExpiryDate(expiry, zone)?.let { date ->
                out += Deadline(
                    "${entry.id}/expiry", Deadline.Source.EntryExpiry, entry.id, entry.sortTitle, entry.groupPath,
                    UserText.of(CardText.ENTRY_EXPIRES), date, entryExpiryHint(date, entry.isExpired(now), now, zone),
                )
            }
        }
        for ((i, field) in entry.customFields.withIndex()) {
            val (date, ends) = interpreter.date(field) ?: continue
            if (!ends) continue
            out += Deadline(
                "${entry.id}/$i", Deadline.Source.Field(i), entry.id, entry.sortTitle, entry.groupPath,
                ImportedFieldName.displayName(field.name), date.date, date.expiry(now, zone),
            )
        }
        return out
    }
}
