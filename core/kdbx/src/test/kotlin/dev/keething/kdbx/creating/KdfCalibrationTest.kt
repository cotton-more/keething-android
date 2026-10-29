package dev.keething.kdbx.creating

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.creating.KdfCalibration.Kind
import dev.keething.kdbx.creating.KdfCalibration.Request
import dev.keething.kdbx.creating.NewDatabaseTest.Companion.argon2d
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxFormat.Cipher
import dev.keething.kdbx.reader.KdbxFormat.Kdf
import dev.keething.kdbx.reader.KdbxReader
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Measuring the key derivation (keething-spec kdbx.kdf-calibration; the Mac's `NewDatabaseTests`, "The calibration"). */
class KdfCalibrationTest {
    /** A clock that only moves when the fake KDF runs: [cost] seconds per iteration (or round), plus [overhead] a run. */
    private class FakeKdf(val cost: Double, val overhead: Double = 0.0) {
        var time = 0.0
        val runs = mutableListOf<Kdf>()

        fun derive(kdf: Kdf) {
            runs += kdf
            time += units(kdf) * cost + overhead
        }
    }

    private fun calibrate(request: Request, fake: FakeKdf, derive: (Kdf) -> Unit = fake::derive) =
        KdfCalibration.calibrate(request, clock = { fake.time }, derive = derive)

    @Test
    fun argon2IterationsScaledToTheTimeAskedForMemoryAndLanesAsChosen() {
        val fake = FakeKdf(cost = 0.02) // 20 ms an iteration

        val result = calibrate(Request(Kind.ARGON2D, 64L shl 20, 4, 1.0), fake)

        assertEquals(argon2d(50, 64L shl 20, 4), result.kdf)
        assertTrue(abs(result.seconds - 1) < 0.001, "${result.seconds}")
        assertTrue(fake.runs.all { it is Kdf.Argon2 && it.variant == Kdf.Argon2.Variant.D && it.memoryBytes == 64L shl 20 && it.parallelism == 4 })
        assertTrue(fake.runs.size >= 3, "run a few times")
    }

    @Test
    fun aSlowRunsNoiseIsNotTakenTheFastestOfTheMeasuredRunsCounts() {
        val fake = FakeKdf(cost = 0.01)
        var slowOnce = true

        val result = calibrate(Request(Kind.ARGON2ID, 1L shl 20, 1, 2.0), fake) { kdf ->
            fake.derive(kdf)
            if (slowOnce && fake.runs.size > 1) {
                fake.time += 5
                slowOnce = false
            }
        }

        assertEquals(Kdf.Argon2(Kdf.Argon2.Variant.ID, 200, 1L shl 20, 1), result.kdf)
    }

    @Test
    fun theMeasuredPairSlowedAlikeNeverMakesAnIterationLookNearlyFree() {
        for (delay in listOf(0.12, 0.15, 0.159)) {
            val fake = FakeKdf(cost = 0.01)

            val result = calibrate(Request(Kind.ARGON2D, 1L shl 20, 1, 1.0), fake) { kdf ->
                fake.derive(kdf)
                if (units(kdf) == 16L) fake.time += delay
            }

            val iterations = units(result.kdf)
            assertTrue(iterations <= 100, "delay $delay: $iterations iterations, ${iterations * 0.01} s really")
            assertTrue(iterations >= 2)
        }
    }

    @Test
    fun oneHiccupInTheProbeDoesNotStopItAtASizeTooSmallToMeasure() {
        val fake = FakeKdf(cost = 0.01)

        calibrate(Request(Kind.ARGON2D, 1L shl 20, 1, 1.0), fake) { kdf ->
            fake.derive(kdf)
            if (fake.runs.size == 2) fake.time += 5
        }

        val sizes = fake.runs.map(::units)
        assertTrue(sizes.max() >= 16, "measured at $sizes")
    }

    @Test
    fun noTimeANegativeOneOrNotANumberIsRefusedParametersKeePassWouldNotRunBeforeMeasuring() {
        for (seconds in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<KdfCalibration.InvalidTime>("$seconds") {
                KdfCalibration.calibrate(Request(Kind.ARGON2D, 1L shl 20, 1, seconds), clock = { 0.0 }, derive = {})
            }
        }
        var ran = false
        assertFailsWith<KdbxException.UnsupportedKdf> {
            KdfCalibration.calibrate(Request(Kind.ARGON2ID, 1000, 1, 1.0), clock = { 0.0 }, derive = { ran = true })
        }
        assertFalse(ran, "nothing measured")
    }

    @Test
    fun aesKdfRoundsTheSameWayTheProbeGrowsUntilARunIsLongEnough() {
        val fake = FakeKdf(cost = 1e-7) // 10 million rounds a second

        val result = calibrate(Request(Kind.AES, 0, 0, 1.0), fake)

        assertEquals(Kdf.AesKdf(10_000_000), result.kdf)
        val probes = fake.runs.map(::units)
        assertTrue(probes.first() < probes.last(), "doubled: $probes")
        assertEquals(10_000, probes.first(), "starts at 10,000 rounds")
    }

    @Test
    fun aFixedCostPerRunDoesNotInflateTheIterationsPastTheTimeAsked() {
        val fake = FakeKdf(cost = 0.02, overhead = 0.05)

        val result = calibrate(Request(Kind.ARGON2D, 64L shl 20, 2, 1.0), fake)

        val real = units(result.kdf) * 0.02 + 0.05
        assertTrue(real in 0.9..1.05, "a run takes $real s")
    }

    @Test
    fun neverUnderTheFloorOnAVerySlowDevice() {
        val slow = FakeKdf(cost = 10.0)
        assertEquals(argon2d(2, 1L shl 30, 1), calibrate(Request(Kind.ARGON2D, 1L shl 30, 1, 0.1), slow).kdf)
        val slowAes = FakeKdf(cost = 1e-3)
        assertEquals(Kdf.AesKdf(100_000), calibrate(Request(Kind.AES, 0, 0, 0.1), slowAes).kdf)
    }

    @Test
    fun aClockThatDoesNotMoveIsAnErrorNotADatabaseThatTakesForever() {
        val stuck = FakeKdf(cost = 0.0)
        assertFailsWith<KdfCalibration.ClockStuck> {
            KdfCalibration.calibrate(Request(Kind.ARGON2D, 1L shl 20, 1, 1.0), clock = { 0.0 }, derive = stuck::derive)
        }
        assertFailsWith<KdfCalibration.ClockStuck> {
            KdfCalibration.calibrate(Request(Kind.AES, 0, 0, 1.0), clock = { 0.0 }, derive = stuck::derive)
        }
    }

    @Test
    fun cancelledBetweenRuns() {
        val fake = FakeKdf(cost = 0.01)
        assertFailsWith<CancellationException> {
            KdfCalibration.calibrate(
                Request(Kind.ARGON2D, 1L shl 20, 1, 1.0),
                clock = { fake.time },
                checkCancelled = { if (fake.runs.size >= 3) throw CancellationException("cancelled") },
                derive = fake::derive,
            )
        }
        assertEquals(3, fake.runs.size, "no run after the cancel")
    }

    @Test
    fun theRecommendedRequestIsKeePassXcs() {
        assertEquals(Request(Kind.ARGON2D, 64L shl 20, 4, 1.0), Request.recommended(cores = 10))
        assertEquals(2, Request.recommended(cores = 2).parallelism)
        assertEquals(1, Request.recommended(cores = 0).parallelism)
        assertEquals(1.0, Request.recommended(cores = 8).seconds)
    }

    @Test
    fun aRealRunLandsNearTheTimeAskedFor() {
        // The bounds stay; a busy CPU while measuring (the other modules' tests run alongside) can make one attempt
        // miss towards a faster file, which is the side the calibration errs on: three attempts, one must land.
        val took = List(3) {
            val result = KdfCalibration.calibrate(Request(Kind.ARGON2D, 1L shl 20, 2, 0.2))
            val file = NewDatabase.file(null, "t.kdbx", Cipher.AES256, result.kdf, DatabaseKey.of(Secret.of("pw")), Instant.now())
            assertIs<kotlin.time.Duration>(KdbxReader.open(file, DatabaseKey.of(Secret.of("pw"))).keyDerivation)
        }

        assertTrue(took.any { it.inWholeMilliseconds in 80..600 }, "asked 0.2 s, the derivations took $took")
        assertTrue(took.all { it.inWholeMilliseconds <= 600 }, "never much longer than asked: $took")
    }
}

private fun units(kdf: Kdf): Long = when (kdf) {
    is Kdf.AesKdf -> kdf.rounds
    is Kdf.Argon2 -> kdf.iterations
}
