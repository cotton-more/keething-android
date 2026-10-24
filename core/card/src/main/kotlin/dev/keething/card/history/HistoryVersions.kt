package dev.keething.card.history

import dev.keething.domain.vault.Entry
import java.time.Instant

/** One version of an entry in its history (Mac `HistoryVersion`). */
public data class HistoryVersion(
    /** Its place in the entry's history from the oldest (0); the current version is the last. */
    val id: Int,
    val entry: Entry,
    val isCurrent: Boolean,
    /** What it changed from the version before it; null for the oldest (nothing to compare with). */
    val changes: List<VersionChange>?,
)

public object HistoryVersions {
    /** [entries]: the versions oldest first, the current one last. Newest first, each with what it changed. */
    public fun of(
        entries: List<Entry>,
        day: (Instant) -> String = { it.toString() },
        size: (Int?) -> String = { it?.let { bytes -> "$bytes bytes" } ?: "size unknown" },
    ): List<HistoryVersion> = entries.indices.map { i ->
        HistoryVersion(
            id = i,
            entry = entries[i],
            isCurrent = i == entries.lastIndex,
            changes = if (i == 0) null else EntryVersionDiff.changes(entries[i - 1], entries[i], day, size),
        )
    }.asReversed()
}
