package dev.keething.vault.creating

import dev.keething.domain.encryption.DatabaseEncryption
import dev.keething.domain.encryption.DatabaseEncryption.Cipher
import dev.keething.domain.encryption.DatabaseEncryption.KeyDerivation
import dev.keething.domain.encryption.MeasuredEncryption
import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.NewFile
import dev.keething.domain.storage.NewFilePlace
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxFormat
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.session.fixtureBytes
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A new database's encryption measured here, and written as measured (keething-spec kdbx.kdf-calibration, kdbx.new-file AC4). */
class EncryptionMeasurementTest {
    @Test
    fun theRecommendedEncryptionIsKeePassXcs() {
        assertEquals(DatabaseEncryption(Cipher.AES256, KeyDerivation.ARGON2D, 64, 4, 1.0), DatabaseEncryption.recommended(cores = 8))
        assertEquals(1, DatabaseEncryption.recommended(cores = 0).lanes)
    }

    @Test
    fun clampedToWhatIsWritten() {
        val wild = DatabaseEncryption(Cipher.CHACHA20, KeyDerivation.ARGON2ID, 4096, 64, Double.NaN).clamped
        assertEquals(DatabaseEncryption(Cipher.CHACHA20, KeyDerivation.ARGON2ID, 2047, 16, 1.0), wild)
        assertEquals(0.1, DatabaseEncryption(Cipher.AES256, KeyDerivation.AES_KDF, 0, 0, 0.0).clamped.seconds)
    }

    @Test
    fun measuredWithTheMemoryAndLanesChosenAndWrittenAsMeasured() {
        val measured = EncryptionMeasurement.measure(DatabaseEncryption(Cipher.CHACHA20, KeyDerivation.ARGON2ID, 1, 2, 0.1))
        assertTrue(measured.units >= 2, "${measured.units}")
        assertTrue(measured.seconds > 0)
        var made: ByteArray? = null
        val target = object : NewFile {
            override val fileName = "Measured.kdbx"
            override fun check() = NewFilePlace.FREE
            override fun create(bytes: ByteArray, readOnly: Boolean) { made = bytes }
            override fun removeCreated() = Unit
        }

        val key = DatabaseCreator { Instant.EPOCH }.create(target, "Measured", Secret.of("pw"), null, measured)

        assertEquals(
            KdbxFormat("4.0", KdbxFormat.Cipher.CHACHA20, KdbxFormat.Kdf.Argon2(KdbxFormat.Kdf.Argon2.Variant.ID, measured.units, 1L shl 20, 2), true),
            KdbxReader.format(made!!),
        )
        assertEquals(null, KdbxReader.open(made!!, key.key).keyDerivation, "the key comes back derived")
        key.wipe()
        assertFailsWith<IllegalStateException> { KdbxReader.open(made!!, key.key) }
    }

    @Test
    fun aesKdfAsRounds() {
        val measured = MeasuredEncryption(DatabaseEncryption(Cipher.AES256, KeyDerivation.AES_KDF, 64, 4, 1.0), 123_456, 1.0)
        assertEquals(KdbxFormat.Kdf.AesKdf(123_456), EncryptionMeasurement.kdf(measured))
    }

    @Test
    fun memoryThatDoesNotFitTheHeapIsSaidNotThrownAsAnError() {
        // The test JVM's heap is 512 MiB at most (Gradle's default), as the phone's large heap.
        val tooMuch = DatabaseEncryption(Cipher.AES256, KeyDerivation.ARGON2D, 2047, 1, 0.1)
        assertTrue(Runtime.getRuntime().maxMemory() < 2047L shl 20, "${Runtime.getRuntime().maxMemory()}")

        assertFailsWith<CreateFailure.NotEnoughMemory> { EncryptionMeasurement.measure(tooMuch) }
    }

    @Test
    fun aCancelledMeasurementStops() {
        assertFailsWith<IllegalStateException> {
            EncryptionMeasurement.measure(DatabaseEncryption.recommended()) { throw IllegalStateException("cancelled") }
        }
    }

    @Test
    fun theKeyDerivationOfAFileMadeElsewhereIsTimed() {
        val file = File(System.getProperty("keething.fixtures"), "created/created-chacha20-argon2id.kdbx").readBytes()
        assertTrue(EncryptionMeasurement.keyDerivationTime(file, Secret.of("pw")).isPositive())
        assertFailsWith<KdbxException.WrongPassword> { EncryptionMeasurement.keyDerivationTime(fixtureBytes("kdbx4-aes-argon2d"), Secret.of("no")) }
    }
}
