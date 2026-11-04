package dev.keething.kdbx.xml

import dev.keething.kdbx.crypto.Bytes
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Base64

/** The times in the XML (Mac `EntryMapper.date` and `KDBXTime`). */
internal object KdbxTime {
    private const val SECONDS_FROM_YEAR_1_TO_1970 = 62_135_596_800L

    /** The times KeePass's own dates hold (.NET `DateTime`): 0001-01-01 to 9999-12-31, in seconds since 0001-01-01. */
    private val SECONDS_KEEPASS_HOLDS = 0L..315_537_897_599L

    /**
     * KDBX 4 (and its export) writes base64 of the seconds since 0001-01-01 UTC as a little-endian 64-bit
     * number; KDBX 3 and hand-made files ISO 8601. Null for anything else, and for a time outside the years 1 to
     * 9999, which no KeePass app writes (keething-spec kdbx.hardening AC8: `AAAAAAAAAIA=` is no time, not a crash).
     */
    fun parse(text: String): Instant? {
        val s = text.trim()
        try {
            return OffsetDateTime.parse(s).takeIf { it.year in 1..9999 }?.toInstant()
        } catch (_: DateTimeParseException) {
            // Not ISO 8601.
        }
        if (s.length != 12) return null
        val bytes = try {
            Base64.getDecoder().decode(s)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size != 8) return null
        val seconds = Bytes.longLe(bytes, 0).takeIf { it in SECONDS_KEEPASS_HOLDS } ?: return null
        return Instant.ofEpochSecond(seconds - SECONDS_FROM_YEAR_1_TO_1970)
    }

    /** As KDBX 4 writes a time: base64 of the whole seconds since 0001-01-01 UTC, little-endian. */
    fun base64(time: Instant): String =
        Base64.getEncoder().encodeToString(Bytes.le64(time.epochSecond + SECONDS_FROM_YEAR_1_TO_1970))

    /** As KDBX 3 wrote a time; a file with such times keeps them. */
    fun iso(time: Instant): String = DateTimeFormatter.ISO_INSTANT.format(time.truncatedTo(ChronoUnit.SECONDS))

    fun isIso(text: String): Boolean = '-' in text && ':' in text
}
