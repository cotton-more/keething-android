package dev.keepassbar.search

import dev.keepassbar.domain.vault.Entry

/**
 * The single place that decides which parts of an entry search can find (Mac `SearchableText`). Passwords,
 * protected custom values and one-time code keys are deliberately absent.
 */
internal object SearchableText {
    fun of(entry: Entry): String {
        val parts = mutableListOf(entry.title, entry.username, entry.url, entry.notes)
        parts += entry.tags
        parts += entry.groupPath
        for (field in entry.customFields) {
            parts += field.name
            field.plainText?.let { parts += it }
        }
        // A newline between fields keeps whitespace-free terms from matching across them.
        return parts.joinToString("\n")
    }
}
