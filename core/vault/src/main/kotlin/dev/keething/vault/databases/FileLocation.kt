package dev.keething.vault.databases

import dev.keething.domain.storage.StoragePlace
import dev.keething.domain.storage.nameOf
import java.io.File

/**
 * Where a database or a key file of the list is (keething-spec databases.saf AC2, AC9, AC13): what the list keeps to
 * reach it again, never its contents.
 */
public sealed interface FileLocation {
    /** The file's own name. */
    public val fileName: String

    /**
     * Where it is on the phone's storage: for its label and folder, for the picker to open at, and to tell the same
     * file however it is reached. Null for a path off the phone's storage.
     */
    public val place: StoragePlace?

    /** The grant it is reached through ([GrantedFolder][dev.keething.domain.storage.GrantedFolder]'s id or a file's), or null. */
    public val grant: String?

    /**
     * By its file path, with all-files access: every database and key file of the list before databases.saf (AC13),
     * and those the app adds while it still reaches files by path (until stage S3).
     */
    public data class ByPath(val path: String) : FileLocation {
        override val fileName: String get() = path.substringAfterLast('/')
        override val place: StoragePlace? get() = StoragePlace.ofPath(path)
        override val grant: String? get() = null
    }

    /** The file at [path] inside the folder the user granted: [grant], the folder at [folder] on the storage (AC2, AC9). */
    public data class InFolder(override val grant: String, val folder: StoragePlace, val path: String) : FileLocation {
        override val fileName: String get() = nameOf(path)
        override val place: StoragePlace get() = folder.resolve(path)
    }

    /** A key file the user granted alone in the file picker ([grant]), at [at] on the storage (AC9). */
    public data class Granted(override val grant: String, val at: StoragePlace) : FileLocation {
        override val fileName: String get() = at.name
        override val place: StoragePlace get() = at
    }

    /**
     * The same file, however each is reached: the same place on the storage, case aside (a folder granted inside
     * another reaches the same files), or for paths off the storage, the same path once standardized.
     */
    public fun isSameFile(other: FileLocation): Boolean {
        if (this == other) return true
        val mine = place
        val theirs = other.place
        if (mine != null && theirs != null) return mine.sameAs(theirs)
        return this is ByPath && other is ByPath && standardized(path) == standardized(other.path)
    }

    public companion object {
        /**
         * Whether the file is there as far as a path tells, for a check before an unlock: a file reached through a grant
         * is looked at by its read, which says what is wrong (true here).
         */
        public fun isThereByPath(location: FileLocation): Boolean = (location as? ByPath)?.let { File(it.path).isFile } ?: true

        /** The file at [path], standardized: `.` and `..` resolved, doubled and trailing `/` dropped. */
        public fun byPath(path: String): ByPath = ByPath(standardized(path))

        /** The same file however its path is written: `.` and `..` resolved, doubled and trailing `/` dropped. */
        public fun standardized(path: String): String = File(path).normalize().path
    }
}
