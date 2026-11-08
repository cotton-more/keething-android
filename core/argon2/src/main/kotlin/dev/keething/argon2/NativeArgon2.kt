package dev.keething.argon2

import android.util.Log
import dev.keething.kdbx.crypto.Argon2
import dev.keething.kdbx.crypto.Argon2Engine

/**
 * The reference Argon2 in C (keething-spec unlock.faster-argon2), its memory the device's, not the Java heap's: a
 * database's key derivation of 1 GiB opens where the phone has it free. The same bytes as the Kotlin [Argon2]
 * (AC1), which stays where the library does not load (AC6).
 */
public class NativeArgon2 private constructor(private val freeMemory: () -> Long) : Argon2Engine {
    /** Half of what the device has free (AC3): the rest of the phone keeps room, and the app is not the one killed. */
    override fun most(): Long = freeMemory() / 2

    override val mostSaid: String = "this phone can spare now, half of its free memory"

    override fun hash(
        password: ByteArray,
        salt: ByteArray,
        parameters: Argon2.Parameters,
        length: Int,
        checkpoint: () -> Unit,
    ): ByteArray {
        val p = parameters
        require(p.version == 0x13 && p.parallelism in 1..0xFF_FFFF && p.iterations in 1..0xFFFF_FFFFL && length >= 4)
        require(p.memoryKiB.toLong() >= 8L * p.parallelism && salt.size >= 8)
        val threads = minOf(p.parallelism, Runtime.getRuntime().availableProcessors())
        val job = Bridge.start(
            password, salt, variant(p.variant), p.iterations.toInt(), p.memoryKiB, p.parallelism, threads, length,
        )
        if (job == 0L) throw OutOfMemoryError("Argon2 could not start")
        val out = ByteArray(length)
        var stoppedBy: Throwable? = null
        val result: Int
        try {
            // A checkpoint between waits, on this thread, as the Kotlin one calls it between its steps (AC4).
            while (!Bridge.await(job, WAIT_MILLIS)) {
                try {
                    checkpoint()
                } catch (failure: Throwable) {
                    stoppedBy = failure
                    Bridge.stop(job)
                    break
                }
            }
        } finally {
            result = Bridge.finish(job, out)
        }
        stoppedBy?.let { throw it } // finish wrote nothing
        return when (result) {
            OK -> out
            MEMORY_ALLOCATION_ERROR -> throw OutOfMemoryError("Argon2 memory of ${p.memoryKiB} KiB")
            else -> throw IllegalArgumentException("Argon2 failed: $result")
        }
    }

    /** The reference code's argon2_type. */
    private fun variant(variant: Argon2.Variant): Int = when (variant) {
        Argon2.Variant.D -> 0
        Argon2.Variant.ID -> 2
    }

    public companion object {
        private const val TAG = "Argon2"
        private const val WAIT_MILLIS = 1
        private const val OK = 0
        private const val MEMORY_ALLOCATION_ERROR = -22

        /**
         * The native engine, or null where its library does not load or does not give the reference value (AC6).
         * [freeMemory] is what the device has free now, in bytes (`ActivityManager.MemoryInfo.availMem`).
         */
        public fun load(freeMemory: () -> Long): NativeArgon2? {
            try {
                System.loadLibrary("keething_argon2")
            } catch (_: UnsatisfiedLinkError) {
                Log.w(TAG, "native Argon2 not loaded, the Kotlin one runs")
                return null
            }
            val engine = NativeArgon2(freeMemory)
            // OpenSSL's Argon2d of "password", 1 iteration, 8 KiB, 1 lane: a library built wrong is not used.
            val tag = engine.hash(
                "password".encodeToByteArray(), "somesaltsomesalt".encodeToByteArray(),
                Argon2.Parameters(Argon2.Variant.D, 1, 8, 1), 32, {},
            )
            val expected = "8f6b35da93486025c6a1c6361da620487040700d56c30b87eb870865781d3274"
            if (tag.joinToString("") { "%02x".format(it) } != expected) {
                Log.w(TAG, "native Argon2 gave a wrong value, the Kotlin one runs")
                return null
            }
            Log.i(TAG, "native Argon2 loaded")
            return engine
        }
    }
}

/** The JNI functions of `keething_argon2.c`. */
internal object Bridge {
    @JvmStatic external fun start(
        password: ByteArray, salt: ByteArray, variant: Int, iterations: Int,
        memoryKiB: Int, lanes: Int, threads: Int, length: Int,
    ): Long

    @JvmStatic external fun await(job: Long, millis: Int): Boolean

    @JvmStatic external fun stop(job: Long)

    @JvmStatic external fun finish(job: Long, out: ByteArray): Int
}
