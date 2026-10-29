package dev.keething.kdbx.creating

import dev.keething.kdbx.reader.KdbxFormat.Cipher
import dev.keething.kdbx.reader.KdbxFormat.Kdf
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * How hard a new database's key derivation is (keething-spec kdbx.kdf-calibration; the Mac's `KDFCalibration`),
 * measured on this phone as KeePassXC's benchmark does: the KDF run with the chosen memory and lanes, the iterations
 * (AES-KDF: rounds) then scaled to the time asked for. Slow (about the time asked for, run more than once): call it
 * off the main thread.
 *
 * A run is timed at a size long enough to measure (doubled from a small probe until two runs take an eighth of the
 * time asked for), then twice at twice that size; the fastest of each counts (a run slowed by something else is
 * noise) and the two give the cost of one iteration apart from what every run costs once (Argon2 fills its memory),
 * that fixed cost believed only under half a run. Never under a floor: 2 Argon2 iterations, 100,000 AES-KDF rounds,
 * Keething's own choice, so a hiccup cannot make a weak file.
 */
public object KdfCalibration {
    public enum class Kind { ARGON2D, ARGON2ID, AES }

    public data class Request(
        val kind: Kind,
        /** Argon2's memory in bytes (whole KiB); not used by AES-KDF. */
        val memoryBytes: Long,
        /** Argon2's lanes; not used by AES-KDF. */
        val parallelism: Int,
        /** How long opening the database should take on this phone. */
        val seconds: Double,
    ) {
        public companion object {
            /** KeePassXC's defaults (kdbx.new-file AC4): Argon2d, 64 MiB, min(cores, 4) lanes, 1 s. */
            public fun recommended(cores: Int = Runtime.getRuntime().availableProcessors(), seconds: Double = 1.0): Request =
                Request(Kind.ARGON2D, 64L shl 20, cores.coerceIn(1, 4), seconds)
        }
    }

    public data class Result(
        /** The KDF to write ([NewDatabase.file]). */
        val kdf: Kdf,
        /** How long it is expected to take on this phone. */
        val seconds: Double,
    )

    /** The runs never took any time: the clock does not move. */
    public class ClockStuck : Exception("the clock does not move")

    /** No time, a negative one, or not a number. */
    public class InvalidTime : Exception("no valid time asked for")

    internal const val ARGON2_FLOOR: Long = 2
    internal const val AES_FLOOR: Long = 100_000

    /**
     * [clock] in seconds, monotonic; [derive] runs the KDF once (by default the real one): both for the tests.
     * [checkCancelled] is called before every run and throws to stop (a coroutine's `ensureActive`). Throws
     * [dev.keething.kdbx.reader.KdbxException.UnsupportedKdf] for parameters KeePass would not run, before
     * anything is measured.
     */
    public fun calibrate(
        request: Request,
        clock: () -> Double = { System.nanoTime() / 1e9 },
        checkCancelled: () -> Unit = {},
        derive: (Kdf) -> Unit = ::run,
    ): Result {
        fun kdf(n: Long): Kdf = when (request.kind) {
            Kind.AES -> Kdf.AesKdf(n)
            Kind.ARGON2D -> Kdf.Argon2(Kdf.Argon2.Variant.D, n, request.memoryBytes, request.parallelism)
            Kind.ARGON2ID -> Kdf.Argon2(Kdf.Argon2.Variant.ID, n, request.memoryBytes, request.parallelism)
        }
        fun time(n: Long): Double {
            checkCancelled()
            val start = clock()
            derive(kdf(n))
            return clock() - start
        }

        if (!request.seconds.isFinite() || request.seconds <= 0) throw InvalidTime()
        val isAes = request.kind == Kind.AES
        if (!isAes) NewDatabase.check(kdf(1)) // refused before a second is spent measuring
        val sample = max(request.seconds / 8, 0.02)
        val cap = if (isAes) 1L shl 40 else 1L shl 30
        var n = if (isAes) 10_000L else 1L
        var once: Double
        while (true) {
            // Long enough twice in a row: one slow run alone (a hiccup) does not stop the probe at a size too small.
            val first = time(n)
            if (first >= sample) {
                once = min(first, time(n))
                if (once >= sample / 2) break
            }
            n *= 2
            if (n > cap) throw ClockStuck()
        }
        val twice = min(time(2 * n), time(2 * n))

        // A run's fixed cost is believed only while it is under half a run: the n-size runs slowed by something else
        // would otherwise make an iteration look nearly free and the file take minutes to open. Else the plain
        // estimate (every run's cost spread over its iterations), which errs towards a file that opens sooner.
        var perUnit = (twice - once) / n
        var fixed = once - perUnit * n
        if (perUnit <= 0 || fixed < 0 || fixed > once / 2) {
            perUnit = once / n
            fixed = 0.0
        }
        val floor = if (isAes) AES_FLOOR else ARGON2_FLOOR
        // KeePass allows 2³²−1 iterations; the phone's own Argon2 (and so its reader) counts them in an Int.
        val ceiling = if (isAes) Long.MAX_VALUE else Int.MAX_VALUE.toLong()
        val wanted = (request.seconds - fixed) / perUnit
        val units = if (wanted.isFinite() && wanted > floor) min(wanted.roundToLong(), ceiling) else floor
        return Result(kdf(units), fixed + perUnit * units)
    }

    /** The real KDF on a throwaway key and salt. */
    private fun run(kdf: Kdf) {
        val template = NewDatabase.template(Cipher.AES256, kdf)
        template.kdf.transform(ByteArray(32) { 0x4B }).fill(0)
    }
}
