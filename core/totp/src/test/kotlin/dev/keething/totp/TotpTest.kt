package dev.keething.totp

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.totp.TotpConfig.Algorithm
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The Mac `TOTPTests` and `TOTPComponentTests` expectations. */
class TotpTest {
    private val sha1 = "12345678901234567890"
    private val sha256 = "12345678901234567890123456789012"
    private val sha512 = "1234567890123456789012345678901234567890123456789012345678901234"
    private val seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // "12345678901234567890"

    private fun config(secret: String, digits: Int = 6, period: Int = 30, algorithm: Algorithm = Algorithm.SHA1) =
        TotpConfig(Secret.of(secret), digits, period, algorithm)

    private fun at(seconds: Long) = Instant.ofEpochSecond(seconds)

    // Generator

    @Test
    fun rfc6238Vectors() {
        val vectors = listOf(
            listOf(59L, "94287082", "46119246", "90693936"),
            listOf(1111111109L, "07081804", "68084774", "25091201"),
            listOf(1111111111L, "14050471", "67062674", "99943326"),
            listOf(1234567890L, "89005924", "91819424", "93441116"),
            listOf(2000000000L, "69279037", "90698825", "38618901"),
            listOf(20000000000L, "65353130", "77737706", "47863826"),
        )
        for ((time, one, two, five) in vectors) {
            val t = at(time as Long)
            assertEquals(one, TotpGenerator.code(config(sha1, 8), t), "sha1 @$time")
            assertEquals(two, TotpGenerator.code(config(sha256, 8, algorithm = Algorithm.SHA256), t), "sha256 @$time")
            assertEquals(five, TotpGenerator.code(config(sha512, 8, algorithm = Algorithm.SHA512), t), "sha512 @$time")
        }
    }

    @Test
    fun sixDigitsAreTheTruncationAndLeadingZerosStay() {
        assertEquals("287082", TotpGenerator.code(config(sha1), at(59)))
        assertEquals("07081804", TotpGenerator.code(config(sha1, 8), at(1111111109)))
    }

    @Test
    fun secondsRemaining() {
        val c = config(sha1)
        assertEquals(listOf(30, 29, 15, 1, 30), listOf(0L, 1, 45, 59, 60).map { TotpGenerator.secondsRemaining(c, at(it)) })
        val minute = config(sha1, period = 60)
        assertEquals(1, TotpGenerator.secondsRemaining(minute, at(59)))
        assertEquals(59, TotpGenerator.secondsRemaining(minute, at(61)))
    }

    @Test
    fun degenerateConfigsDoNotCrash() {
        assertEquals(TotpGenerator.code(config(sha1, period = 1), at(59)), TotpGenerator.code(config(sha1, period = 0), at(59)))
        assertEquals(1, TotpGenerator.secondsRemaining(config(sha1, period = 0), at(59)))
        assertEquals(TotpGenerator.code(config(sha1), at(0)), TotpGenerator.code(config(sha1), at(-5)))
        assertEquals(1, TotpGenerator.secondsRemaining(config(sha1), at(-1)))
        assertEquals(1, TotpGenerator.code(config(sha1, digits = 0), at(59)).length)
    }

    // Base32

    @Test
    fun base32Decodes() {
        assertContentEquals(sha1.encodeToByteArray(), Base32.decode(seed))
        assertContentEquals("foo".encodeToByteArray(), Base32.decode("MZXW6==="))
        assertContentEquals("foo".encodeToByteArray(), Base32.decode("MZXW6"), "padding optional")
        assertContentEquals(sha1.encodeToByteArray(), Base32.decode("gezd gnbv gy3t qojq gezd gnbv gy3t qojq"))
        assertNull(Base32.decode("MZXW1"), "'1' is not in the alphabet")
        assertNull(Base32.decode("MZXW!"))
    }

    // otpauth links

    @Test
    fun otpauthDefaultsAndExplicitParameters() {
        assertEquals(config(sha1), TotpParser.parseOtpAuthUri("otpauth://totp/Site:me?secret=$seed"))
        val c = TotpParser.parseOtpAuthUri(
            "otpauth://totp/Site:me?secret=gezdgnbvgy3tqojq&issuer=Site&period=60&digits=8&algorithm=SHA256",
        )
        assertEquals(TotpConfig(Secret.adopt(Base32.decode("GEZDGNBVGY3TQOJQ")!!), 8, 60, Algorithm.SHA256), c)
    }

    @Test
    fun otpauthErrors() {
        assertFailsWith<TotpParseException.InvalidUri> { TotpParser.parseOtpAuthUri("https://example.com") }
        assertFailsWith<TotpParseException.MissingSecret> { TotpParser.parseOtpAuthUri("otpauth://totp/x?issuer=a") }
        assertFailsWith<TotpParseException.InvalidBase32> { TotpParser.parseOtpAuthUri("otpauth://totp/x?secret=!!!") }
        assertEquals(
            "steam",
            assertFailsWith<TotpParseException.Unsupported> {
                TotpParser.parseOtpAuthUri("otpauth://totp/x?secret=GEZDGNBV&encoder=steam")
            }.what,
        )
    }

    @Test
    fun otpauthToleratesUnescapedSpacesCaseAndFragments() {
        assertEquals(config(sha1), TotpParser.parseOtpAuthUri("otpauth://totp/My Site:me@example.com?secret=$seed"))
        assertEquals(config(sha1), TotpParser.parseOtpAuthUri("OTPAUTH://TOTP/x?secret=$seed#frag"))
        assertEquals(config(sha1), TotpParser.parseOtpAuthUri("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQ%47EZDGNBVGY3TQOJQ"))
    }

    @Test
    fun otpauthAcceptsOnlyTotp() {
        assertFailsWith<TotpParseException.InvalidUri> { TotpParser.parseOtpAuthUri("otpauth://hotp/x?secret=$seed&counter=1") }
        assertFailsWith<TotpParseException.InvalidUri> { TotpParser.parseOtpAuthUri("") }
    }

    @Test
    fun otpauthEmptySecretIsMissingNotInvalid() {
        assertFailsWith<TotpParseException.MissingSecret> { TotpParser.parseOtpAuthUri("otpauth://totp/x?secret=") }
        assertFailsWith<TotpParseException.InvalidBase32> { TotpParser.parseOtpAuthUri("otpauth://totp/x?secret=====") }
    }

    @Test
    fun otpauthBadNumbersAreUnsupportedNeverDefaulted() {
        for ((param, name) in listOf(
            "digits=0" to "digits", "digits=11" to "digits", "digits=abc" to "digits",
            "period=0" to "period", "period=-5" to "period",
        )) {
            val failure = assertFailsWith<TotpParseException.Unsupported> {
                TotpParser.parseOtpAuthUri("otpauth://totp/x?secret=$seed&$param")
            }
            assertEquals(name, failure.what, param)
        }
    }

    @Test
    fun otpauthAlgorithmIsCaseInsensitiveUnknownOnesUnsupported() {
        assertEquals(Algorithm.SHA512, TotpParser.parseOtpAuthUri("otpauth://totp/x?secret=$seed&algorithm=sha512").algorithm)
        assertEquals(
            "MD5",
            assertFailsWith<TotpParseException.Unsupported> {
                TotpParser.parseOtpAuthUri("otpauth://totp/x?secret=$seed&algorithm=MD5")
            }.what,
        )
    }

    @Test
    fun otpauthSteamIsReportedEvenWithoutASecret() {
        assertEquals(
            "steam",
            assertFailsWith<TotpParseException.Unsupported> { TotpParser.parseOtpAuthUri("otpauth://totp/x?encoder=steam") }.what,
        )
    }

    // KeePass's legacy fields

    @Test
    fun legacyFields() {
        assertEquals(config(sha1, digits = 8), TotpParser.parseLegacy(seed, "30;8"))
        assertEquals(config(sha1), TotpParser.parseLegacy(seed, null))
        assertEquals(config(sha1), TotpParser.parseLegacy(seed, "  "), "blank settings mean defaults")
        assertEquals("steam", assertFailsWith<TotpParseException.Unsupported> { TotpParser.parseLegacy(seed, "30;S") }.what)
        assertEquals("steam", assertFailsWith<TotpParseException.Unsupported> { TotpParser.parseLegacy(seed, "30;s") }.what)
    }

    @Test
    fun legacyMalformedSettingsAreUnsupported() {
        for ((settings, reason) in listOf(
            "30" to "settings", "30;6;1" to "settings", "0;6" to "period", "x;6" to "period",
            "30;0" to "digits", "30;11" to "digits",
        )) {
            assertEquals(reason, assertFailsWith<TotpParseException.Unsupported> { TotpParser.parseLegacy(seed, settings) }.what)
        }
    }

    @Test
    fun legacyBlankOrInvalidSeed() {
        assertFailsWith<TotpParseException.MissingSecret> { TotpParser.parseLegacy("  ", null) }
        assertFailsWith<TotpParseException.InvalidBase32> { TotpParser.parseLegacy("!!!", null) }
    }

    // Which field is the source

    @Test
    fun resolverPrefersTheOtpLinkAndNeverFallsBackFromABrokenOne() {
        val uri = "otpauth://totp/x?secret=$seed&digits=8"
        assertEquals(8, TotpResolver.resolve(uri, seed, "30;6")?.digits)
        assertEquals(7, TotpResolver.resolve("  ", seed, "30;7")?.digits, "a blank otp falls back")
        assertEquals(6, TotpResolver.resolve(null, seed, null)?.digits)
        assertNull(TotpResolver.resolve("garbage", seed, null), "a broken otp does not fall back")
        assertNull(TotpResolver.resolve(null, null, null))
        assertNull(TotpResolver.resolve(null, "  ", null))
        assertNull(TotpResolver.resolve(null, "!!!", null))
    }
}
