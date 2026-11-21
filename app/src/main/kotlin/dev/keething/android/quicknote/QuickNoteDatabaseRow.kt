package dev.keething.android.quicknote

import dev.keething.android.R
import dev.keething.android.databases.DatabaseMark
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiText
import dev.keething.vault.databases.DatabaseId

/**
 * The quick note's editor names its database (keething-spec app.quick-note AC2): on the phone the setting chooses it,
 * the editor only says which, with where it is chosen under it, read as one item.
 */
internal object QuickNoteDatabaseRow {
    /** The setting's database, while the list has two or more to tell apart; null otherwise. */
    fun shown(window: List<DatabaseMark>, database: DatabaseId): DatabaseMark? =
        window.takeIf { it.size >= 2 }?.firstOrNull { it.id == database }

    /** Under its name: where it is chosen. */
    fun where(): UiText = uiText(R.string.quicknote_database_where, uiText(R.string.settings_title), uiText(R.string.settings_quick_notes))

    /** The row as a screen reader says it. */
    fun said(mark: DatabaseMark): UiText = uiText(R.string.quicknote_database_said, mark.label.full, where())
}
