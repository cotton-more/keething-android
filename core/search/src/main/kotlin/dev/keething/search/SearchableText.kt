package dev.keething.search

import dev.keething.domain.vault.Entry

/**
 * The single place that decides which parts of an entry search can find (Mac `SearchableText`). Passwords,
 * protected custom values, protected Notes and one-time code keys are deliberately absent.
 */
internal object SearchableText {
    fun of(entry: Entry): String {
        val parts = mutableListOf(entry.title, entry.username, entry.url)
        // A protected text (a note's, by default) is not searched: keething-spec types.note AC9.
        if (!entry.notesProtected) parts += entry.notes
        parts += entry.tags
        parts += entry.groupPath
        for (field in entry.customFields) {
            parts += field.name
            // A further one-time code's link holds its key (keething-spec app.codes-view): its name is searched, not it.
            field.plainText?.takeUnless { it.trimStart().startsWith("otpauth://", ignoreCase = true) }?.let { parts += it }
        }
        // A passkey by its site and user, never its other fields (keething-spec card.passkey AC4).
        entry.passkey?.let { parts += it.site; parts += it.user }
        // A newline between fields keeps whitespace-free terms from matching across them.
        return parts.joinToString("\n")
    }
}
