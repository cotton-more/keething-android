package dev.keething.domain.storage

import java.util.Locale

/**
 * Where a file or a folder is on the phone's storage (keething-spec databases.saf): its [volume], "primary" for the
 * phone's own storage or a card's id ("1234-5678"), and its [path] from the volume's top ("Sync/KeePass"; "" the top
 * itself). The phone's storage provider names its documents so ("primary:Sync/KeePass", stage S0), and the folder
 * picker opens at one. The storage takes names regardless of case: [sameAs] and [inside] do too.
 */
public data class StoragePlace(val volume: String, val path: String) {
    /** The last part of [path]: the file's or the folder's own name ("" for the volume's top). */
    val name: String get() = nameOf(path)

    /** The folder that holds it; null for the volume's top. */
    val parent: StoragePlace? get() = if (path.isEmpty()) null else StoragePlace(volume, folderOf(path))

    /** The place of [path] inside this folder ("" is the folder itself). */
    public fun resolve(path: String): StoragePlace = if (path.isEmpty()) this else StoragePlace(volume, pathIn(this.path, path))

    /** The same place, case aside. */
    public fun sameAs(other: StoragePlace): Boolean = folded(volume) == folded(other.volume) && folded(path) == folded(other.path)

    /**
     * This place's path inside [folder], in this place's own spelling ("" when it is the folder itself), or null when it
     * is not in it; case aside, as the storage takes names.
     */
    public fun inside(folder: StoragePlace): String? {
        if (folded(volume) != folded(folder.volume)) return null
        if (folder.path.isEmpty()) return path
        val prefix = folded(folder.path)
        val mine = folded(path)
        return when {
            mine == prefix -> ""
            mine.startsWith("$prefix/") -> path.substring(folder.path.length + 1)
            else -> null
        }
    }

    override fun toString(): String = "$volume:$path"

    public companion object {
        /** The phone's own storage ("Internal storage"), as the storage provider names it. */
        public const val PRIMARY: String = "primary"

        /**
         * The place of a file path on the phone, as a database kept by its path before databases.saf has it (AC13):
         * `/storage/emulated/<user>/…` (and `/sdcard/…`, `/storage/self/primary/…`) on the phone's own storage,
         * `/storage/<card>/…` on a card. Null
         * for any other path. `.` and `..` resolved, doubled and trailing `/` dropped.
         */
        public fun ofPath(path: String): StoragePlace? {
            val parts = ArrayDeque<String>()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(part)
                }
            }
            if (!path.startsWith("/")) return null
            val segments = parts.toList()
            return when {
                segments.firstOrNull() == "sdcard" -> StoragePlace(PRIMARY, segments.drop(1).joinToString("/"))
                segments.firstOrNull() != "storage" || segments.size < 2 -> null
                segments[1] == "emulated" ->
                    if (segments.size >= 3 && segments[2].all(Char::isDigit)) StoragePlace(PRIMARY, segments.drop(3).joinToString("/")) else null
                segments[1] == "self" -> if (segments.getOrNull(2) == "primary") StoragePlace(PRIMARY, segments.drop(3).joinToString("/")) else null
                else -> StoragePlace(segments[1], segments.drop(2).joinToString("/"))
            }
        }

        /** A name or a path as the storage compares it: case aside. */
        public fun folded(text: String): String = text.lowercase(Locale.ROOT)
    }
}
