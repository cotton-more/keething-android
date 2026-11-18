package dev.keething.domain.encryption

/**
 * How a new database is encrypted (keething-spec kdbx.new-file; the Mac's `DatabaseEncryption`): KDBX 4.0, a cipher,
 * a key derivation with its memory and lanes (Argon2), and how long opening it should take on this phone; the
 * iterations (AES-KDF: rounds) are measured for that time ([MeasuredEncryption]).
 */
public data class DatabaseEncryption(
    val cipher: Cipher,
    val keyDerivation: KeyDerivation,
    /** Argon2's memory in MiB (whole MiB, under 2 GiB: KeePass 2.x refuses more); not used by AES-KDF. */
    val memoryMiB: Int,
    /** Argon2's lanes; not used by AES-KDF. */
    val lanes: Int,
    /** How long opening the database should take on this phone. */
    val seconds: Double,
) {
    public enum class Cipher(public val label: String) { AES256("AES-256"), CHACHA20("ChaCha20") } // not user text

    public enum class KeyDerivation(public val label: String) {
        ARGON2D("Argon2d"), // not user text
        ARGON2ID("Argon2id"), // not user text
        AES_KDF("AES-KDF"),
        ;

        public val isArgon2: Boolean get() = this != AES_KDF
    }

    /** Within what is written: memory and lanes in their ranges, the time in its range. */
    public val clamped: DatabaseEncryption
        get() = copy(
            memoryMiB = memoryMiB.coerceIn(MEMORY_RANGE),
            lanes = lanes.coerceIn(LANE_RANGE),
            seconds = (if (seconds.isFinite()) seconds else 1.0).coerceIn(SECONDS_RANGE),
        )

    public companion object {
        public val MEMORY_RANGE: IntRange = 1..2047
        public val LANE_RANGE: IntRange = 1..16
        public val SECONDS_RANGE: ClosedFloatingPointRange<Double> = 0.1..5.0

        /** KeePassXC's defaults (kdbx.new-file AC4): AES-256, Argon2d, 64 MiB, min(cores, 4) lanes, 1 s. */
        public fun recommended(cores: Int = Runtime.getRuntime().availableProcessors(), seconds: Double = 1.0): DatabaseEncryption =
            DatabaseEncryption(Cipher.AES256, KeyDerivation.ARGON2D, 64, cores.coerceIn(1, 4), seconds)
    }
}

/** A [DatabaseEncryption] measured on this phone: the Argon2 iterations or AES-KDF rounds for its time. */
public data class MeasuredEncryption(
    val encryption: DatabaseEncryption,
    /** Argon2's iterations, or AES-KDF's rounds. */
    val units: Long,
    /** How long opening the database is expected to take on this phone. */
    val seconds: Double,
)
