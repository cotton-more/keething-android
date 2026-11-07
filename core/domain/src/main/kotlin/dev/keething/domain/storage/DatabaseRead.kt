package dev.keething.domain.storage

/** The outcome of reading the database file. */
public sealed interface DatabaseRead {
    /**
     * The file's bytes, and the version they were read at. [stoppedSave]: a save the app did not finish (it ended in
     * the middle) was put right before this read or an earlier one, and the app is to say it (keething-spec
     * databases.saf AC5; [DatabaseStore.stoppedSaveSaid]).
     */
    public class Found(
        public val bytes: ByteArray,
        public val version: FileVersion,
        public val stoppedSave: StoppedSave? = null,
    ) : DatabaseRead

    /** No file at the place; Syncthing may be in the middle of replacing it (SPEC §3.2). */
    public data object Missing : DatabaseRead

    /** The file is there but the app may not read it (the access was revoked). */
    public data object AccessDenied : DatabaseRead
}

/** The outcome of a streamed read of the database file ([DatabaseStore.read] with a reading). */
public sealed interface DatabaseReading<out T> {
    /** What the reading made of the file's bytes, and the version they were read at; [stoppedSave] as [DatabaseRead.Found]'s. */
    public class Found<out T>(
        public val result: T,
        public val version: FileVersion,
        public val stoppedSave: StoppedSave? = null,
    ) : DatabaseReading<T>

    public data object Missing : DatabaseReading<Nothing>

    public data object AccessDenied : DatabaseReading<Nothing>
}

/** Which way a save stopped in the middle was put right (keething-spec databases.saf AC5). */
public enum class StoppedSave {
    /** The save's new file is the database now: the save was finished. */
    FINISHED,

    /** The save's content is not in the database: it is the file as it was before the save, or as a sync made it since. */
    UNDONE,
}
