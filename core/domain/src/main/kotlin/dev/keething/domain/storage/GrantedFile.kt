package dev.keething.domain.storage

/**
 * One file the user granted alone in the system's file picker (keething-spec databases.saf AC9): a key file in no
 * granted folder. A key file is not rewritten, so its grant lasts, until a sync replaces the file, which ends it (stage
 * S0 measured it). [read] blocks on the provider: call it off the main thread. It throws a
 * [java.nio.file.AccessDeniedException] when the grant is gone, an [java.io.IOException] for any other failure.
 */
public interface GrantedFile {
    /** Tells this grant from any other while it lasts (the grant's own address). */
    public val id: String

    /** The file's bytes, or null when there is no file there any more. */
    public fun read(): ByteArray?
}

/**
 * The grants the app holds, kept by the system across restarts (keething-spec databases.saf AC14): Android keeps a few
 * hundred per app, so the app gives back each one nothing needs any more. Blocks on the system: call it off the main
 * thread; never throws.
 */
public interface Grants {
    /** The grants the app holds now, by their addresses ([GrantedFolder.id], [GrantedFile.id]). */
    public fun held(): Set<String>

    /** Gives [grant] back: the folder or file cannot be reached through it any more. */
    public fun giveBack(grant: String)
}
