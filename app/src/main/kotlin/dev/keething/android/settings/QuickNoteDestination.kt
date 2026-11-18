package dev.keething.android.settings

import dev.keething.android.R
import dev.keething.android.databases.DatabaseLine
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiText
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
    data class Choice(val id: DatabaseId?, val title: UiText, val detail: UiText?, val colorIndex: Int?)

    val choices: List<Choice> = if (lines == null) emptyList() else
        listOf(
            Choice(
                null,
                uiText(R.string.settings_quick_notes_current),
                lines.orEmpty().firstOrNull { it.isCurrent }?.let { uiText(R.string.settings_quick_notes_current_now, it.label.full) },
                null,
            ),
        ) + lines.orEmpty().map { Choice(it.id, uiPlain(it.label.full), uiPlain(it.fileName), it.colorIndex) }

    /** The choice that holds; null for Current database. */
    val selected: DatabaseId? = setting?.takeIf { id -> lines.orEmpty().any { it.id == id } }

    /** Under the row: "Current database", or the chosen database's name. */
    val summary: UiText = choices.firstOrNull { it.id == selected }?.title ?: uiPlain("")

    /** Said under the row while the database set is no longer in the list. */
    val note: UiText? = if (lines != null && setting != null && selected == null) uiText(R.string.settings_quick_notes_fallen_back) else null
}
