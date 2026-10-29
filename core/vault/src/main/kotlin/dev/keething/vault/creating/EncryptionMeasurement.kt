package dev.keething.vault.creating

import dev.keething.domain.encryption.DatabaseEncryption
import dev.keething.domain.encryption.MeasuredEncryption
import dev.keething.domain.secret.Secret
import dev.keething.kdbx.creating.KdfCalibration
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxFormat
import dev.keething.kdbx.reader.KdbxReader
import kotlin.time.Duration

/**
 * A new database's encryption measured on this phone (keething-spec kdbx.kdf-calibration; the Mac's
 * `KDBXFileCreator.measure`). Slow, about the time asked for, more than once: call it off the main thread.
 */
public object EncryptionMeasurement {
    /**
     * The iterations (AES-KDF: rounds) for [encryption]'s time, [encryption] clamped first. [checkCancelled] throws to
     * stop between runs. Throws [CreateFailure.NotEnoughMemory] when Argon2's memory does not fit the app's heap.
     */
    public fun measure(encryption: DatabaseEncryption, checkCancelled: () -> Unit = {}): MeasuredEncryption {
        val e = encryption.clamped
        val kind = when (e.keyDerivation) {
            DatabaseEncryption.KeyDerivation.ARGON2D -> KdfCalibration.Kind.ARGON2D
            DatabaseEncryption.KeyDerivation.ARGON2ID -> KdfCalibration.Kind.ARGON2ID
            DatabaseEncryption.KeyDerivation.AES_KDF -> KdfCalibration.Kind.AES
        }
        val result = try {
            KdfCalibration.calibrate(
                KdfCalibration.Request(kind, e.memoryMiB.toLong() shl 20, e.lanes, e.seconds),
                checkCancelled = checkCancelled,
            )
        } catch (_: OutOfMemoryError) {
            throw CreateFailure.NotEnoughMemory()
        }
        return MeasuredEncryption(e, units(result.kdf), result.seconds)
    }

    /**
     * How long this phone takes to derive [file]'s key from [password]: a database made elsewhere timed here. Throws a
     * [dev.keething.kdbx.reader.KdbxException] for a file that does not open with it.
     */
    public fun keyDerivationTime(file: ByteArray, password: Secret): Duration {
        val key = DatabaseKey.of(password)
        try {
            val database = KdbxReader.open(file, key)
            database.binaries.forEach(Secret::wipe)
            return checkNotNull(database.keyDerivation)
        } finally {
            key.wipe()
        }
    }

    internal fun cipher(cipher: DatabaseEncryption.Cipher): KdbxFormat.Cipher = when (cipher) {
        DatabaseEncryption.Cipher.AES256 -> KdbxFormat.Cipher.AES256
        DatabaseEncryption.Cipher.CHACHA20 -> KdbxFormat.Cipher.CHACHA20
    }

    internal fun kdf(measured: MeasuredEncryption): KdbxFormat.Kdf {
        val e = measured.encryption
        val memory = e.memoryMiB.toLong() shl 20
        return when (e.keyDerivation) {
            DatabaseEncryption.KeyDerivation.AES_KDF -> KdbxFormat.Kdf.AesKdf(measured.units)
            DatabaseEncryption.KeyDerivation.ARGON2D -> KdbxFormat.Kdf.Argon2(KdbxFormat.Kdf.Argon2.Variant.D, measured.units, memory, e.lanes)
            DatabaseEncryption.KeyDerivation.ARGON2ID -> KdbxFormat.Kdf.Argon2(KdbxFormat.Kdf.Argon2.Variant.ID, measured.units, memory, e.lanes)
        }
    }

    private fun units(kdf: KdbxFormat.Kdf): Long = when (kdf) {
        is KdbxFormat.Kdf.AesKdf -> kdf.rounds
        is KdbxFormat.Kdf.Argon2 -> kdf.iterations
    }
}
