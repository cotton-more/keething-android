package dev.keething.kdbx.key

import dev.keething.kdbx.reader.KdbxFormat.Kdf
import java.util.Locale

/**
 * The key derivation's costs KeePass 2.x itself keeps to (`Argon2Kdf.cs`, `AesKdf.cs`; keething-spec kdbx.hardening
 * AC6, kdbx.new-file AC3): a file outside them is refused before anything is derived, the parameter named; a new file
 * is never made outside them. Argon2: 1 to 2³²−1 iterations, 1 to 2²⁴−1 lanes, memory in whole KiB from 8 KiB a lane
 * up to `int.MaxValue` bytes (under 2 GiB); AES-KDF: at least one round. Numbers the file stores unsigned are taken so
 * ([Kdf.AesKdf.rounds], [Kdf.Argon2.iterations], [Kdf.Argon2.memoryBytes] above `Long.MAX_VALUE` are negative).
 */
internal object KdfLimits {
    const val MAX_UINT32 = 0xFFFF_FFFFL
    const val MAX_LANES = 0xFF_FFFF
    const val MAX_MEMORY_BYTES = Int.MAX_VALUE.toLong()

    /** What is outside the limits, as the lock screen names it ("Argon2 memory of 4 TiB …"); null when within them. */
    fun problem(kdf: Kdf): String? = when (kdf) {
        is Kdf.AesKdf -> "AES-KDF with no rounds".takeIf { kdf.rounds == 0L }
        is Kdf.Argon2 -> when {
            kdf.iterations !in 1..MAX_UINT32 ->
                "Argon2 with ${kdf.iterations.toULong()} iterations (KeePass takes 1 to ${grouped(MAX_UINT32)})"
            kdf.parallelism !in 1..MAX_LANES -> lanes(kdf.parallelism.toLong())
            kdf.memoryBytes !in 0..MAX_MEMORY_BYTES ->
                "Argon2 memory of ${size(kdf.memoryBytes.toULong())} (KeePass reads less than 2 GiB)"
            kdf.memoryBytes % 1024 != 0L -> "Argon2 memory of ${kdf.memoryBytes} bytes (not whole KiB)"
            kdf.memoryBytes / 1024 < 8L * kdf.parallelism ->
                "Argon2 memory of ${kdf.memoryBytes / 1024} KiB for ${kdf.parallelism} lanes (at least 8 KiB a lane)"
            else -> null
        }
    }

    /** Argon2's lanes outside 1 to 2²⁴−1, named; null within (a file's are read as an unsigned 32-bit number). */
    fun lanes(lanes: Long): String? = "Argon2 with $lanes lanes (KeePass takes 1 to ${grouped(MAX_LANES.toLong())})".takeIf { lanes !in 1..MAX_LANES }

    private fun grouped(number: Long): String = String.format(Locale.ROOT, "%,d", number)

    /** "64 MiB", "4 TiB", "1.5 GiB": bytes in the largest unit that leaves at least 1. */
    fun size(bytes: ULong): String {
        val units = listOf("bytes", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
        var unit = 0
        var value = bytes.toDouble()
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0 || value == Math.floor(value)) "${value.toLong()} ${units[unit]}" else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }
}
