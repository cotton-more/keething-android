package dev.keething.domain.storage

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * One folder the user granted the app in the system's folder picker (keething-spec databases.saf AC2), reached through
 * the storage's provider, never by a file path. What is in it is named by its path inside it ("KeePass/Personal.kdbx";
 * "" is the folder itself) and looked up again at every call, so a file a sync replaced is simply the file at that
 * path. The functions block on the provider: call them off the main thread. Each throws a
 * [java.nio.file.AccessDeniedException] when the grant is gone, an [java.io.IOException] for any other failure.
 */
public interface GrantedFolder {
    /** Tells this grant from any other while it lasts (the grant's own address). */
    public val id: String

    /** What is directly in [folder], or null when there is no folder at that path. */
    public fun list(folder: String): List<FolderItem>?

    /** The version of the file at [path], read from the file itself (databases.saf AC8), or null when there is none. */
    public fun version(path: String): FileVersion?

    /** The bytes of the file at [path] with the version of the one file they were read from, or null when there is none. */
    public fun read(path: String): FolderRead?

    /**
     * As [read], the bytes handed to [reading] as they are read from the one file, never held whole (a large database's
     * save; keething-spec app.large-database AC2): what [reading] returns with the version of that file, or null when
     * there is none.
     */
    public fun <T> read(path: String, reading: (InputStream) -> T): FolderReading<T>? =
        read(path)?.let { read -> FolderReading(ByteArrayInputStream(read.bytes).use(reading), read.version) }

    /**
     * Makes an empty file named [name] in [folder] and returns the name it got: the provider gives another one
     * ("Personal (1).kdbx") when [name] is taken, and never makes a file over another. Throws a
     * [java.nio.file.NoSuchFileException] when there is no folder at [folder].
     */
    public fun create(folder: String, name: String): String

    /**
     * Writes [bytes] into the file at [path], which [create] has just made empty, and flushes them to the storage.
     * Only into such a file: the provider does not cut an existing file's tail (measured on the Pixel 6), so a
     * shorter content written over a longer one would keep the old end.
     */
    public fun write(path: String, bytes: ByteArray)

    /**
     * Renames the file at [path] to [name] in its folder and returns the name it got: the provider gives another one
     * when [name] is taken, and never renames over a file. Throws a [java.nio.file.NoSuchFileException] when there is
     * no file at [path].
     */
    public fun rename(path: String, name: String): String

    /** Deletes the file at [path]; false when there was none. */
    public fun delete(path: String): Boolean

    /**
     * Calls [onChange] on a background thread when something in [folder] may have changed, until the handle is closed
     * or the provider's process ends (databases.saf AC7). A notice names no file; one change may bring several, and
     * one may come before a version read shows the change (each is given again a moment later). [onChange] may wait
     * for a save of the database to end: starting a watch, and the handle's [FolderWatch.isAlive] and close, never wait
     * for the thread that gives the notices.
     */
    public fun watch(folder: String, onChange: () -> Unit): FolderWatch
}

/** A file's bytes and the version they were read at. */
public class FolderRead(public val bytes: ByteArray, public val version: FileVersion)

/** What a streamed read made of a file's bytes, and the version they were read at. */
public class FolderReading<out T>(public val result: T, public val version: FileVersion)

/** A file or a folder inside a granted folder. */
public data class FolderItem(val name: String, val isFolder: Boolean)

/** A [GrantedFolder.watch] under way. */
public interface FolderWatch : AutoCloseable {
    /**
     * Whether notices still come: false once the provider's process ended, which ends its watches without a word
     * (S0 measured it), or once closed. Asks the provider, quickly; never throws.
     */
    public fun isAlive(): Boolean
}

/** The folder that holds [path] inside a granted folder ("" for the granted folder itself). */
public fun folderOf(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

/** The last part of [path]: the file's own name. */
public fun nameOf(path: String): String = path.substringAfterLast('/')

/** The path of [name] in [folder] inside a granted folder. */
public fun pathIn(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"
