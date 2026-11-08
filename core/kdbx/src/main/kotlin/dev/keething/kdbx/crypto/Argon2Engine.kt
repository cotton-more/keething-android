package dev.keething.kdbx.crypto

/**
 * An Argon2 whose memory is not the Java heap's: the app's native one where its library loads (keething-spec
 * unlock.faster-argon2). A database's key derivation runs on it when it is set, its own [Argon2] otherwise.
 */
public interface Argon2Engine {
    /** The most Argon2 memory, in bytes, a derivation starting now may take. */
    public fun most(): Long

    /** The words that say what [most] is, after "more than the … ": "this phone can spare now". */
    public val mostSaid: String

    /**
     * As [Argon2.hash], the same bytes. Throws [OutOfMemoryError] when its memory could not be had,
     * [IllegalArgumentException] for parameters outside the specification, and what [checkpoint] threw when it
     * stopped there; [checkpoint] is called on the caller's thread about every millisecond.
     */
    public fun hash(
        password: ByteArray,
        salt: ByteArray,
        parameters: Argon2.Parameters,
        length: Int,
        checkpoint: () -> Unit,
    ): ByteArray

    public companion object {
        /** Set by the app once at its start; null on the JVM and where the native library does not load. */
        @Volatile
        public var native: Argon2Engine? = null
    }
}
