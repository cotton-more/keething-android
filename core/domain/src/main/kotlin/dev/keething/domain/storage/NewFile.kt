package dev.keething.domain.storage

/**
 * A file to be made where there is none yet: a new database or a new key file (keething-spec kdbx.new-file AC8,
 * kdbx.new-key-file AC3). Nothing is ever made over a file already there, not even over a dangling link, and never by
 * a check followed by a move: a file a sync puts there meanwhile stays as it was. The functions block on file I/O;
 * call them off the main thread.
 */
public interface NewFile {
    /** The file's name, as the user sees it. */
    public val fileName: String

    /** Whether the file could be made now: said early, while nothing is written; [create] refuses on its own. */
    public fun check(): NewFilePlace

    /**
     * Makes the file with [bytes] in one step that refuses a file already there, readable by its owner only where
     * the storage has permissions ([readOnly]: not writable either). Throws a
     * [java.nio.file.FileAlreadyExistsException] when something is at the path, a [java.nio.file.NoSuchFileException]
     * when the folder is not there, an [java.io.IOException] for any other failure; nothing is left behind then.
     */
    public fun create(bytes: ByteArray, readOnly: Boolean = false)

    /** Removes the file [create] made, and only that one (a key file that did not read back as written). */
    public fun removeCreated()
}

public enum class NewFilePlace {
    /** Nothing at the path, and its folder is there. */
    FREE,

    /** Something is at the path: a file, a folder or a link, even one that leads nowhere. */
    TAKEN,

    /** The folder is not there. */
    NO_FOLDER,
}
