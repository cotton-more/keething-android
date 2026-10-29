package dev.keething.domain.storage

/**
 * How the app reaches a database's key file (keething-spec kdbx.key-files AC8): remembered by where it is, never
 * copied, read again at every unlock. [read] blocks on file I/O; call it off the main thread.
 */
public interface KeyFileSource {
    /** The key file's name, as the user knows it. */
    public val fileName: String

    /** The file's contents; an [java.io.IOException] for a read that failed otherwise. */
    public fun read(): KeyFileRead
}

/** The outcome of reading a key file. */
public sealed interface KeyFileRead {
    /** The file's bytes; the reader wipes them once the key is made. */
    public class Found(public val bytes: ByteArray) : KeyFileRead

    /** No file at the place (moved, renamed, or on a card that is not in). */
    public data object Missing : KeyFileRead

    /** The file is there but the app may not read it (the access was revoked). */
    public data object AccessDenied : KeyFileRead
}
