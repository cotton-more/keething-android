package dev.keething.argon2

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.keething.domain.secret.Secret
import dev.keething.kdbx.creating.NewDatabase
import dev.keething.kdbx.crypto.Argon2
import dev.keething.kdbx.crypto.Argon2Engine
import dev.keething.kdbx.crypto.Argon2Vectors
import dev.keething.kdbx.editing.HostileFiles
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.KeyFile
import dev.keething.kdbx.key.WipedKeyException
import dev.keething.kdbx.reader.KdbxFormat
import dev.keething.kdbx.reader.KdbxReader
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * keething-spec unlock.faster-argon2 on a phone (`task test:device`): the native Argon2 against the Kotlin one and
 * the fixtures, and a derivation given up.
 */
@RunWith(AndroidJUnit4::class)
class NativeArgon2Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val engine = NativeArgon2.load { freeMemory(instrumentation.targetContext) }
        ?: error("the native library does not load")

    @After
    fun cleanUp() {
        Argon2Engine.native = null
    }

    // unlock.faster-argon2 AC1 (task test:device; not run by task spec:verify)
    @Test
    fun everyVectorOfTheKotlinArgon2sTestsGivesItsReferenceValue() {
        for (case in Argon2Vectors.cases) {
            val tag = engine.hash(Argon2Vectors.password, Argon2Vectors.salt, case.parameters, 32) {}

            assertEquals(case.toString(), case.expected, tag.joinToString("") { "%02x".format(it) })
        }
    }

    // unlock.faster-argon2 AC1 (task test:device; not run by task spec:verify)
    @Test
    fun randomParametersGiveTheKotlinArgon2sBytes() {
        val random = Random(20261107)
        repeat(12) { run ->
            val lanes = random.nextInt(1, 9)
            val parameters = Argon2.Parameters(
                if (run % 2 == 0) Argon2.Variant.D else Argon2.Variant.ID,
                random.nextLong(1, 4),
                8 * lanes + random.nextInt(0, 4096),
                lanes,
            )
            val password = random.nextBytes(random.nextInt(0, 80))
            val salt = random.nextBytes(random.nextInt(8, 64))
            val length = random.nextInt(4, 129)

            assertArrayEquals(
                "$parameters, ${password.size}-byte password, ${salt.size}-byte salt, $length bytes",
                Argon2.hash(password, salt, parameters, length),
                engine.hash(password, salt, parameters, length) {},
            )
        }
    }

    // unlock.faster-argon2 AC1 (task test:device; not run by task spec:verify)
    @Test
    fun everyFixtureOpensWithIt() {
        Argon2Engine.native = engine
        val assets = instrumentation.context.assets
        val keyFile = KeyFile.key(assets.open("created/created.keyx").use { it.readBytes() })
        val names = assets.list("")!!.filter { it.endsWith(".kdbx") } +
            assets.list("created")!!.filter { it.endsWith(".kdbx") }.map { "created/$it" } +
            assets.list("interop")!!.filter { it.endsWith(".kdbx") }.map { "interop/$it" }
        var argon2 = 0
        for (name in names) {
            val file = assets.open(name).use { it.readBytes() }
            val key = when {
                name.endsWith("keyfile-only.kdbx") -> DatabaseKey.of(null, keyFile)
                name.endsWith("keyfile.kdbx") -> DatabaseKey.of(Secret.of(PASSWORD), keyFile)
                else -> DatabaseKey.of(Secret.of(PASSWORD))
            }
            if (KdbxReader.format(file).kdf is KdbxFormat.Kdf.Argon2) argon2++

            val started = System.nanoTime()
            KdbxReader.open(file, key) // a wrong key or a damaged file throws
            Log.i("NativeArgon2Test", "$name opened in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        assertTrue("$argon2 of ${names.size} with Argon2", argon2 >= 5)
    }

    // unlock.faster-argon2 AC1 (task test:device; not run by task spec:verify)
    @Test
    fun aFileCreatedWithItOpensWithTheKotlinOne() {
        Argon2Engine.native = engine
        val kdf = KdbxFormat.Kdf.Argon2(KdbxFormat.Kdf.Argon2.Variant.D, 4, 16L shl 20, 2)
        val file = NewDatabase.file("Native", "native.kdbx", KdbxFormat.Cipher.AES256, kdf, DatabaseKey.of(Secret.of(PASSWORD)), Instant.now())
        Argon2Engine.native = null

        KdbxReader.open(file, DatabaseKey.of(Secret.of(PASSWORD)))

        // For KeePassXC on the computer: adb pull the file below, then keepassxc-cli ls it (password pw).
        val out = File(instrumentation.targetContext.getExternalFilesDir(null), "created-native-argon2d.kdbx")
        out.writeBytes(file)
        Log.i("NativeArgon2Test", "created with the native Argon2: ${out.absolutePath}")
    }

    // unlock.faster-argon2 AC4 (task test:device; not run by task spec:verify)
    @Test
    fun aDerivationGivenUpStopsWithinMilliseconds() {
        val sizes = listOf(256L shl 20, 1L shl 30).filter { it <= engine.most() }
        assumeTrue("the phone has 256 MiB to spare", sizes.isNotEmpty())
        val pool = Executors.newSingleThreadExecutor()
        try {
            for (memory in sizes) {
                val parameters = HostileFiles.argon2(iterations = (1L shl 32) - 1, memoryBytes = memory, lanes = 2)
                val file = HostileFiles.withKdfParameters(fixture("kdbx4-aes-argon2d.kdbx"), parameters)
                val key = DatabaseKey.of(Secret.of(PASSWORD))
                Argon2Engine.native = engine
                val open = pool.submit<Throwable?> { runCatching { KdbxReader.open(file, key) }.exceptionOrNull() }
                Thread.sleep(1500)
                val given = System.nanoTime()
                key.abandon()
                val failure = open.get(5, TimeUnit.SECONDS)
                val stoppedIn = (System.nanoTime() - given) / 1_000_000.0

                assertTrue("${memory shr 20} MiB: $failure", failure is WipedKeyException)
                Log.i("NativeArgon2Test", "${memory shr 20} MiB given up, stopped in $stoppedIn ms (memory wiped and freed)")
                // The lanes stop within a few milliseconds; the wipe of the memory (AC5) is most of it, about 0.15 s a
                // GiB on the Pixel 6 (2026-11-07: 1 GiB 147 ms, of it 145 the wipe; 256 MiB 39 ms, 36 the wipe).
                val budget = 20 + 250.0 * memory / (1L shl 30)
                assertTrue("${memory shr 20} MiB stopped $stoppedIn ms after it was given up (budget $budget)", stoppedIn < budget)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fixture(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }

    private companion object {
        const val PASSWORD = "pw"

        fun freeMemory(context: Context): Long =
            ActivityManager.MemoryInfo().also(context.getSystemService(ActivityManager::class.java)::getMemoryInfo).availMem
    }
}
