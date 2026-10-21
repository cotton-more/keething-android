package dev.keepassbar.card

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A day, or a month for card-style dates (`12/2027`): what a document says, with no time and no time zone. */
public data class CalendarDate(val year: Int, val month: Int, val day: Int? = null) : Comparable<CalendarDate> {
    /** The last day it covers: the day itself, or the last day of the month. */
    internal val lastDay: LocalDate
        get() = if (day != null) LocalDate.of(year, month, day) else YearMonth.of(year, month).atEndOfMonth()

    /**
     * By the last day each covers: a month (valid through its last day) comes after a day early in it, so
     * "earliest first" means what is due first. Equal ends: the day before the month.
     */
    override fun compareTo(other: CalendarDate): Int =
        compareValuesBy(this, other, { it.year }, { it.month }, { it.lastDay.dayOfMonth }, { if (it.day == null) 1 else 0 })

    internal companion object {
        /** Null when the date does not exist (2027-02-30). */
        fun valid(year: Int, month: Int, day: Int?): CalendarDate? {
            if (month !in 1..12 || year !in 1000..9999) return null
            if (day != null && runCatching { LocalDate.of(year, month, day) }.isFailure) return null
            return CalendarDate(year, month, day)
        }
    }
}

/** How an end date stands today. */
public data class ExpiryHint(
    val status: Status,
    /** Neutral, as the field's own name says what ends: "in 5 months", "today", "45 days ago". */
    val text: String,
) {
    public enum class Status { EXPIRED, SOON, VALID }

    public companion object {
        /** Ends within this many days → [Status.SOON]. */
        public const val SOON_DAYS: Int = 90
    }
}

/**
 * A date in a custom field (Mac `FieldDate`). The standard form is ISO 8601: `2027-10-31`, `2027-12` for a
 * month (cards). Untyped fields are read only in forms that cannot be anything else: an ISO day (time dropped)
 * and the text date KeePassXC's 1Password import writes (`Sun Oct 31 00:00:00 2027 GMT`). A field typed as a
 * date also accepts an ISO month and the old forms `31.10.2027`, `20271031`, `202712`, `12/27`, `12/2027`, which
 * are then not standard.
 */
public data class FieldDate(val date: CalendarDate, val isStandard: Boolean) {
    /** What the card shows: the locale's medium date, or `MM/yyyy` for a month. Copying copies the value as stored. */
    public fun displayText(locale: Locale): String {
        if (date.day == null) return "%02d/%04d".format(Locale.ROOT, date.month, date.year)
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date.lastDay)
    }

    /** How the date stands as the end of something; valid through the last day it covers. */
    public fun expiry(now: Instant): ExpiryHint {
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val end = date.lastDay
        val days = ChronoUnit.DAYS.between(today, end)
        if (days < 0) {
            return ExpiryHint(ExpiryHint.Status.EXPIRED, if (days == -1L) "yesterday" else distance(end, today, -days) + " ago")
        }
        val status = if (days <= ExpiryHint.SOON_DAYS) ExpiryHint.Status.SOON else ExpiryHint.Status.VALID
        return when (days) {
            0L -> ExpiryHint(status, "today")
            1L -> ExpiryHint(status, "tomorrow")
            else -> ExpiryHint(status, "in " + distance(today, end, days))
        }
    }

    public companion object {
        public fun parse(text: String, typed: Boolean): FieldDate? {
            val v = text.trim { it == ' ' || it == '\t' }
            numbers(ISO_DAY, v, 1, 2, 3)?.let { (y, m, d) -> return CalendarDate.valid(y, m, d)?.let { FieldDate(it, true) } }
            qtTextDate(v)?.let { return it }
            if (!typed) return null
            numbers(ISO_MONTH, v, 1, 2)?.let { (y, m) -> return CalendarDate.valid(y, m, null)?.let { FieldDate(it, true) } }
            val old = numbers(DOTTED_DAY, v, 3, 2, 1)?.let { (y, m, d) -> CalendarDate.valid(y, m, d) }
                ?: numbers(COMPACT_DAY, v, 1, 2, 3)?.let { (y, m, d) -> CalendarDate.valid(y, m, d) }
                ?: numbers(COMPACT_MONTH, v, 1, 2)?.let { (y, m) -> CalendarDate.valid(y, m, null) }
                ?: numbers(SLASH_MONTH, v, 2, 1)?.let { (y, m) -> CalendarDate.valid(if (y < 100) 2000 + y else y, m, null) }
            return old?.let { FieldDate(it, false) }
        }

        /** "36 days", "5 months", "4 years". */
        private fun distance(start: LocalDate, end: LocalDate, days: Long): String {
            if (days < 60) return "$days days"
            val months = ChronoUnit.MONTHS.between(start, end)
            if (months < 24) return "$months months"
            val years = months / 12
            return "$years year${if (years == 1L) "" else "s"}"
        }

        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val WEEKDAYS = setOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        // `[0-9]`, not `\d`: only ASCII digits are read as numbers.
        private val ISO_DAY =
            Regex("""^([0-9]{4})-([0-9]{2})-([0-9]{2})(T[0-9]{2}:[0-9]{2}(:[0-9]{2}(\.[0-9]+)?)?(Z|[+-][0-9]{2}:?[0-9]{2})?)?$""")
        private val QT_TEXT =
            Regex("""^([A-Za-z]{3}) ([A-Za-z]{3}) +([0-9]{1,2}) [0-9]{2}:[0-9]{2}:[0-9]{2} ([0-9]{4})( [A-Za-z0-9+:-]+)?$""")
        private val ISO_MONTH = Regex("""^([0-9]{4})-([0-9]{2})$""")
        private val DOTTED_DAY = Regex("""^([0-9]{1,2})\.([0-9]{1,2})\.([0-9]{4})$""")
        private val COMPACT_DAY = Regex("""^([0-9]{4})([0-9]{2})([0-9]{2})$""")
        private val COMPACT_MONTH = Regex("""^([0-9]{4})([0-9]{2})$""")
        private val SLASH_MONTH = Regex("""^([0-9]{2})/([0-9]{2}|[0-9]{4})$""")

        /** Qt's text form, as KeePassXC's 1Password import writes it. */
        private fun qtTextDate(v: String): FieldDate? {
            val g = QT_TEXT.matchEntire(v)?.groupValues ?: return null
            val month = MONTHS.indexOf(g[2].lowercase())
            if (g[1].lowercase() !in WEEKDAYS || month < 0) return null
            return CalendarDate.valid(g[4].toInt(), month + 1, g[3].toInt())?.let { FieldDate(it, false) }
        }

        /** The given groups as numbers, in that order; null when the text does not match. */
        private fun numbers(regex: Regex, text: String, vararg indices: Int): List<Int>? {
            val groups = regex.matchEntire(text)?.groupValues ?: return null
            return indices.map { groups[it].toIntOrNull() ?: return null }
        }
    }
}
