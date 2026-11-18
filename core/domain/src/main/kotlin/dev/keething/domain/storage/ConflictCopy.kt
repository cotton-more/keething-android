package dev.keething.domain.storage

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * A copy Syncthing left next to the database when two devices changed it at the same time (SPEC §3.4).
 * [createdAt] is the creating device's local time, as Syncthing writes it into the name.
 */
public data class ConflictCopy(
    val fileName: String,
    val createdAt: LocalDateTime,
    val deviceId: String,
) {
    public companion object {
        /** The conflict copy of [databaseFileName] that [candidate] names, or null if it names none. */
        public fun of(databaseFileName: String, candidate: String): ConflictCopy? {
            val extension = databaseFileName.substringAfterLast('.', missingDelimiterValue = "")
                .let { if (it.isEmpty()) "" else ".$it" }
            val stem = databaseFileName.removeSuffix(extension)
            if (!candidate.startsWith(stem) || !candidate.endsWith(extension)) return null

            val marks = candidate.substring(stem.length, candidate.length - extension.length)
            if (!CONFLICT_MARKS.matches(marks)) return null
            // A conflict of a conflict copy carries several marks; the last one is the newest.
            val newest = CONFLICT_MARK.findAll(marks).last()
            val (date, deviceId) = newest.destructured

            val createdAt = try {
                LocalDateTime.parse(date, SYNCTHING_TIME)
            } catch (_: DateTimeParseException) {
                return null
            }
            return ConflictCopy(candidate, createdAt, deviceId)
        }

        private const val MARK = """\.sync-conflict-(\d{8}-\d{6})-([A-Z2-7]{7})"""
        private val CONFLICT_MARK = Regex(MARK)
        private val CONFLICT_MARKS = Regex("(?:$MARK)+")
        private val SYNCTHING_TIME = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss") // not user text
    }
}
