package dev.keething.android.databases

import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiText
import dev.keething.domain.vault.VaultEdit
import dev.keething.vault.databases.DatabaseRecord

/**
 * A database's page, its name and colour (SPEC §6.2; keething-spec databases.rename-recolour): what the file has
 * ([fileName], `Meta/DatabaseName` as written, and [fileColor], `Meta/Color`), and what is chosen. A colour is the
 * palette's index; null is none.
 */
internal data class NameAndColorForm(val fileName: String, val fileColor: String?, val databaseFile: String) {
    /** The colour shown chosen at first: the file's as its nearest (AC3), or none. */
    val fileColorIndex: Int? = fileColor?.let(DatabasePalette::nearest)

    /**
     * What Save writes for [name] and [color]: only what changed (AC4), a name without its outer spaces, a colour as its
     * light dot `#RRGGBB` or "" for none (AC2, AC3). Null: nothing changed.
     */
    fun edit(name: String, color: Int?): VaultEdit.NameAndColor? {
        val newName = name.trim().takeIf { it != fileName.trim() }
        val newColor = if (color == fileColorIndex) null else color?.let { "#%06X".format(DatabasePalette.shades[it].dotLight) } ?: ""
        if (newName == null && newColor == null) return null
        return VaultEdit.NameAndColor(name = newName, color = newColor)
    }

    /** Under the name: what it is called by (keething-spec databases.name-colour AC1). */
    fun nameNote(name: String): UiText {
        val stem = databaseFile.substringBeforeLast('.').ifEmpty { databaseFile }
        return when {
            name.isBlank() -> uiText(R.string.databases_name_note_empty, stem)
            DatabaseRecord.nameFromFile(name) == null -> uiText(R.string.databases_name_note_no_name, name.trim(), stem)
            else -> uiText(R.string.databases_name_note_file, databaseFile)
        }
    }

    /** Under the colours. */
    fun colorNote(color: Int?): UiText = when {
        color == null -> uiText(R.string.databases_color_note_none)
        color == fileColorIndex && DatabaseRecord.colorFromFile(fileColor) != "#%06X".format(DatabasePalette.shades[color].dotLight) ->
            uiText(R.string.databases_color_note_file)
        else -> uiText(R.string.databases_color_note_shown)
    }

    companion object {
        /** The palette's colours by name, in its order, for a screen reader. */
        val NAMES: List<UiText> = listOf(
            R.string.databases_color_teal,
            R.string.databases_color_orange,
            R.string.databases_color_green,
            R.string.databases_color_purple,
            R.string.databases_color_pink,
            R.string.databases_color_indigo,
            R.string.databases_color_brown,
            R.string.databases_color_red,
            R.string.databases_color_grey,
        ).map { uiText(it) }
    }
}
