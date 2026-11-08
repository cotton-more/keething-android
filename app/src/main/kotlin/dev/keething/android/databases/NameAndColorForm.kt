package dev.keething.android.databases

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
    fun nameNote(name: String): String {
        val stem = databaseFile.substringBeforeLast('.').ifEmpty { databaseFile }
        return when {
            name.isBlank() -> "Empty: it is called by its file name, $stem."
            DatabaseRecord.nameFromFile(name) == null -> "“${name.trim()}” counts as no name: it is called by its file name, $stem."
            else -> "Its file stays $databaseFile."
        }
    }

    /** Under the colours. */
    fun colorNote(color: Int?): String = when {
        color == null -> "None: it takes a colour no other database has."
        color == fileColorIndex && DatabaseRecord.colorFromFile(fileColor) != "#%06X".format(DatabasePalette.shades[color].dotLight) ->
            "Its file's own colour, shown as the nearest of these; it stays as it is until another is chosen."
        else -> "KeePassXC shows it beside the name."
    }

    companion object {
        /** The palette's colours by name, in its order, for a screen reader. */
        val NAMES: List<String> = listOf("Teal", "Orange", "Green", "Purple", "Pink", "Indigo", "Brown", "Red", "Grey")
    }
}
