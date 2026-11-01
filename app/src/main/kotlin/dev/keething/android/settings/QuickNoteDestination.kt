package dev.keething.android.settings

import dev.keething.android.databases.DatabaseLine
import dev.keething.vault.databases.DatabaseId

/**
 * Settings' "Quick notes go to" (keething-spec app.quick-note AC1; SPEC §6.4; the Mac's `QuickNoteDestination`): Current
 * database first, then the list's databases with their dots. A database set and since removed from the list is
 * none of them: quick notes go to the current one, and [note] says so until another is chosen.
 */
internal class QuickNoteDestination(setting: DatabaseId?, lines: List<DatabaseLine>?) {
    /** The list not read yet: nothing said, nothing offered (rather than a false "no longer in the list"). */
    val isLoading: Boolean = lines == null

    /** [id] null: Current database (no dot). [detail]: under the name. */
    data class Choice(val id: DatabaseId?, val title: String, val detail: String?, val colorIndex: Int?)

    val choices: List<Choice> = if (lines == null) emptyList() else
        listOf(Choice(null, CURRENT, lines.orEmpty().firstOrNull { it.isCurrent }?.let { "${it.label.full} now" }, null)) +
            lines.orEmpty().map { Choice(it.id, it.label.full, it.fileName, it.colorIndex) }

    /** The choice that holds; null for Current database. */
    val selected: DatabaseId? = setting?.takeIf { id -> lines.orEmpty().any { it.id == id } }

    /** Under the row: "Current database", or the chosen database's name. */
    val summary: String = choices.firstOrNull { it.id == selected }?.title.orEmpty()

    /** Said under the row while the database set is no longer in the list. */
    val note: String? = if (lines != null && setting != null && selected == null) FALLEN_BACK else null

    companion object {
        const val CURRENT: String = "Current database"
        const val FALLEN_BACK: String = "The database chosen before is no longer in the list: quick notes go to the current one."
    }
}
