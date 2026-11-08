package dev.keething.kdbx.key

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.Fixtures
import dev.keething.kdbx.crypto.Argon2
import dev.keething.kdbx.crypto.Argon2Engine
import dev.keething.kdbx.editing.HostileFiles
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * keething-spec unlock.faster-argon2 on the JVM: what a database's key derivation does with an [Argon2Engine] set
 * (the native one is checked on the phone, core/argon2's NativeArgon2Test) and without one.
 */
class Argon2EngineTest {
    @AfterTest
    fun cleanUp() {
        Argon2Engine.native = null
        AppMemory.most = { Runtime.getRuntime().maxMemory() }
    }

    /**
     * The Kotlin Argon2 behind the engine's door, its memory limit [most]; [failure] thrown instead when set, and
     * zeros given without running when not [runs].
     */
    private class FakeEngine(private val most: Long, private val failure: Throwable? = null, private val runs: Boolean = true) :
        Argon2Engine {
        val hashed = mutableListOf<Argon2.Parameters>()

        override fun most(): Long = most

        override val mostSaid = "this phone can spare now, half of its free memory"

        override fun hash(password: ByteArray, salt: ByteArray, parameters: Argon2.Parameters, length: Int, checkpoint: () -> Unit): ByteArray {
            hashed += parameters
            failure?.let { throw it }
            if (!runs) return ByteArray(length)
            return Argon2.hash(password, salt, parameters, length, checkpoint)
        }
    }

    private fun key() = DatabaseKey.of(Secret.of(Fixtures.PASSWORD))

    private fun argon2File(memoryBytes: Long) =
        HostileFiles.withKdfParameters(Fixtures.bytes("kdbx4-aes-argon2d.kdbx"), HostileFiles.argon2(memoryBytes = memoryBytes))

    // spec: unlock.faster-argon2/AC3
    @Test
    fun withTheEngineTheLimitIsItsAndMoreIsRefusedBeforeItRuns() {
        val engine = FakeEngine(most = 512L shl 20, runs = false)
        Argon2Engine.native = engine
        AppMemory.most = { 256L shl 20 }

        // Above the Java heap's limit, within the engine's: given to the engine (its key then fails the header's HMAC),
        // not refused.
        assertFailsWith<KdbxException.WrongPassword> { KdbxReader.open(argon2File(384L shl 20), key()) }
        assertEquals(384 * 1024, engine.hashed.single().memoryKiB)

        val refusal = assertFailsWith<KdbxException.NotEnoughMemory> { KdbxReader.open(argon2File(1L shl 30), key()) }

        assertEquals("Argon2 memory of 1 GiB, more than the 512 MiB this phone can spare now, half of its free memory", refusal.kdf)
        assertEquals(1, engine.hashed.size, "nothing run for the refused one")
    }

    // spec: unlock.faster-argon2/AC3
    @Test
    fun anAllocationTheEngineCouldNotMakeIsTheSameRefusalNotACrash() {
        Argon2Engine.native = FakeEngine(most = 2L shl 30, failure = OutOfMemoryError("Argon2 memory of 1048576 KiB"))

        val refusal = assertFailsWith<KdbxException.NotEnoughMemory> { KdbxReader.open(argon2File(1L shl 30), key()) }

        assertEquals("Argon2 memory of 1 GiB, more than the 2 GiB this phone can spare now, half of its free memory", refusal.kdf)
    }

    // spec: unlock.faster-argon2/AC6
    @Test
    fun withoutTheEngineTheKotlinArgon2DerivesWithinTheJavaHeapAsBefore() {
        AppMemory.most = { 256L shl 20 }

        val database = KdbxReader.open(Fixtures.bytes("kdbx4-aes-argon2d.kdbx"), key())
        val refusal = assertFailsWith<KdbxException.NotEnoughMemory> { KdbxReader.open(argon2File(1L shl 30), key()) }

        assertTrue(database.document.entries.isNotEmpty())
        assertEquals("Argon2 memory of 1 GiB, more than the 256 MiB this phone gives Keething", refusal.kdf)
    }

    @Test
    fun aFixtureOpensThroughTheEngineWithItsKey() {
        val engine = FakeEngine(most = 2L shl 30)
        Argon2Engine.native = engine

        val database = KdbxReader.open(Fixtures.bytes("kdbx4-chacha20-argon2id.kdbx"), key())

        assertTrue(database.document.entries.isNotEmpty())
        assertEquals(Argon2.Variant.ID, engine.hashed.single().variant)
    }
}
