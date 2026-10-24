package dev.keething.domain.storage

/** The outcome of reading the database file. */
public sealed interface DatabaseRead {
    /** The file's bytes, and the version they were read at. */
    public class Found(public val bytes: ByteArray, public val version: FileVersion) : DatabaseRead

    /** No file at the place; Syncthing may be in the middle of replacing it (SPEC §3.2). */
    public data object Missing : DatabaseRead

    /** The file is there but the app may not read it (the access was revoked). */
    public data object AccessDenied : DatabaseRead
}
