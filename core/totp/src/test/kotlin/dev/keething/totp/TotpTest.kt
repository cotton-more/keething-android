package dev.keething.totp

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.Base32
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.totp.TotpConfig.Algorithm
import dev.keething.domain.totp.TotpFormat
import dev.keething.domain.vault.FieldValue
import java.time.Instant
import java.util.Base64
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

    private fun resolve(otp: String?, seed: String?, settings: String?, keePass: Map<String, String> = emptyMap()) =
        TotpResolver.resolve(
            keePass + listOfNotNull(otp?.let { "otp" to it }, seed?.let { "TOTP Seed" to it }, settings?.let { "TOTP Settings" to it }),
        )

    @Test
    fun resolverPrefersTheOtpLinkAndNeverFallsBackFromABrokenOne() {
        val uri = "otpauth://totp/x?secret=$seed&digits=8"
        assertEquals(8, resolve(uri, seed, "30;6")?.digits)
        assertEquals(7, resolve("  ", seed, "30;7")?.digits, "a blank otp falls back")
        assertEquals(6, resolve(null, seed, null)?.digits)
        assertNull(resolve("garbage", seed, null), "a broken otp does not fall back")
        assertNull(resolve(null, null, null))
        assertNull(resolve(null, "  ", null))
        assertNull(resolve(null, "!!!", null))
    }

    @Test
    fun resolverReadsKeePasssFieldsWhenThereIsNoOtpAndNoTotpSeed() {
        val uri = "otpauth://totp/x?secret=$seed&digits=8"
        val fields = mapOf("TimeOtp-Secret-Base32" to seed, "TimeOtp-Length" to "7")
        assertEquals(7, resolve(null, null, null, fields)?.digits)
        assertEquals(7, resolve(" ", " ", null, fields)?.digits)
        assertEquals(8, resolve(uri, null, null, fields)?.digits)
        assertEquals(6, resolve(null, seed, "30;6", fields)?.digits)
        assertNull(resolve("garbage", null, null, fields), "no fallback")
        assertNull(resolve(null, null, null, mapOf("TimeOtp-Secret-Base32" to "!!!!")))
    }

    @Test
    fun aKeePassCodeIsTheCodeKeePassMakes() {
        // RFC 6238's SHA-256 vector at 59 s.
        val config = resolve(null, null, null, mapOf("TimeOtp-Secret" to sha256, "TimeOtp-Length" to "8", "TimeOtp-Algorithm" to "HMAC-SHA-256"))
        assertEquals("46119246", config?.let { TotpGenerator.code(it, at(59)) })
    }

    // KeePass 2.x's TimeOtp-* fields, as KeePass reads them

    private fun keePass(fields: Map<String, String>) = TotpParser.parseKeePass(fields::get)

    @Test
    fun keePassSecretInEachOfItsEncodingsWithKeePasssDefaults() {
        val bytes = sha1.encodeToByteArray()
        val hex = bytes.joinToString("") { "%02X".format(it) }
        for (fields in listOf(
            mapOf("TimeOtp-Secret" to sha1),
            mapOf("TimeOtp-Secret-Hex" to hex),
            mapOf("TimeOtp-Secret-Hex" to hex.lowercase()),
            mapOf("TimeOtp-Secret-Base32" to seed),
            mapOf("TimeOtp-Secret-Base32" to seed.lowercase()),
            mapOf("TimeOtp-Secret-Base64" to Base64.getEncoder().encodeToString(bytes)),
        )) {
            assertEquals(config(sha1), keePass(fields), "$fields")
        }
        assertEquals(
            config(sha1, 8, 60, Algorithm.SHA512),
            keePass(mapOf("TimeOtp-Secret-Base32" to seed, "TimeOtp-Length" to "8", "TimeOtp-Period" to "60", "TimeOtp-Algorithm" to "HMAC-SHA-512")),
        )
        assertEquals(Algorithm.SHA256, keePass(mapOf("TimeOtp-Secret-Base32" to seed, "TimeOtp-Algorithm" to "HMAC-SHA-256")).algorithm)
    }

    @Test
    fun theFirstSecretKeePassReadsWinsAndOneThatDoesNotDecodeIsNoCode() {
        assertEquals(config("abcdefghij"), keePass(mapOf("TimeOtp-Secret" to "abcdefghij", "TimeOtp-Secret-Base32" to seed)))
        assertEquals(config(sha1), keePass(mapOf("TimeOtp-Secret" to "  ", "TimeOtp-Secret-Base32" to seed)), "a blank one is none")
        assertFailsWith<TotpParseException.InvalidBase32> { keePass(mapOf("TimeOtp-Secret-Hex" to "12345", "TimeOtp-Secret-Base32" to seed)) }
        assertFailsWith<TotpParseException.InvalidBase32> { keePass(mapOf("TimeOtp-Secret-Hex" to "zz")) }
        assertFailsWith<TotpParseException.InvalidBase32> { keePass(mapOf("TimeOtp-Secret-Base32" to "!!!!")) }
        assertFailsWith<TotpParseException.InvalidBase32> { keePass(mapOf("TimeOtp-Secret-Base64" to "%%%%")) }
        assertFailsWith<TotpParseException.InvalidBase32>("unpadded, as .NET reads it") { keePass(mapOf("TimeOtp-Secret-Base64" to "MTIzNDU2Nzg")) }
        assertFailsWith<TotpParseException.MissingSecret> { keePass(mapOf("TimeOtp-Length" to "8")) }
    }

    @Test
    fun lengthAndPeriodAsKeePassTakesThemMoreThanEightDigitsAndOtherAlgorithmsNoCode() {
        fun with(length: String, period: String) =
            keePass(mapOf("TimeOtp-Secret-Base32" to seed, "TimeOtp-Length" to length, "TimeOtp-Period" to period))
        assertEquals(config(sha1), with("", ""))
        assertEquals(config(sha1), with("abc", "x"), "not a number: KeePass's default")
        assertEquals(config(sha1), with("0", "0"))
        assertEquals(config(sha1), with("-7", "99999999999"), "not a uint")
        assertEquals(config(sha1, 7, 45), with(" 7 ", " 45 "))
        assertEquals(config(sha1, 8), with("8", "30"))
        assertEquals("digits", assertFailsWith<TotpParseException.Unsupported> { with("9", "30") }.what)
        for (name in listOf("SHA256", "hmac-sha-1", "HMAC-SHA1")) {
            assertEquals(name, assertFailsWith<TotpParseException.Unsupported> { keePass(mapOf("TimeOtp-Secret-Base32" to seed, "TimeOtp-Algorithm" to name)) }.what)
        }
    }

    // Which format an entry has, which a new code takes

    @Test
    fun anEntrysFormatIsTheOneItsCodeIsReadFrom() {
        fun format(fields: Map<String, String>) = TotpFormat.of { fields[it]?.isNotBlank() == true }
        val uri = "otpauth://totp/x?secret=$seed"
        assertEquals(TotpFormat.LINK, format(mapOf("otp" to uri, "TOTP Seed" to seed, "TimeOtp-Secret-Base32" to seed)))
        assertEquals(TotpFormat.SEED, format(mapOf("otp" to " ", "TOTP Seed" to seed, "TimeOtp-Secret-Base32" to seed)))
        assertEquals(TotpFormat.KEEPASS, format(mapOf("TOTP Settings" to "30;6", "TimeOtp-Secret-Hex" to "00")))
        assertEquals(TotpFormat.LINK, format(mapOf("otp" to "garbage")), "a broken link is still the entry's format")
        assertNull(format(mapOf("TimeOtp-Length" to "8", "TOTP Settings" to "30;6")))
        assertNull(format(emptyMap()))
    }

    @Test
    fun aNewCodeTakesTheEntrysFormatElseTheDatabasesMostUsedElseOtpATieIsOtp() {
        assertEquals(TotpFormat.SEED, TotpFormat.forNewCode(TotpFormat.SEED, mapOf(TotpFormat.KEEPASS to 5)))
        assertEquals(TotpFormat.KEEPASS, TotpFormat.forNewCode(null, mapOf(TotpFormat.KEEPASS to 2, TotpFormat.LINK to 1)))
        assertEquals(TotpFormat.SEED, TotpFormat.forNewCode(null, mapOf(TotpFormat.SEED to 3, TotpFormat.KEEPASS to 2, TotpFormat.LINK to 1)))
        assertEquals(TotpFormat.LINK, TotpFormat.forNewCode(null, mapOf(TotpFormat.KEEPASS to 2, TotpFormat.SEED to 2)))
        assertEquals(TotpFormat.LINK, TotpFormat.forNewCode(null, mapOf(TotpFormat.KEEPASS to 1, TotpFormat.LINK to 1)))
        assertEquals(TotpFormat.LINK, TotpFormat.forNewCode(null, emptyMap()))
    }

    @Test
    fun theFieldsEachFormatWritesWhatItCannotHoldIsNullAndEachReadsBack() {
        fun pairs(fields: List<Pair<String, FieldValue>>?) = fields.orEmpty().map { (key, value) ->
            when (value) {
                is FieldValue.Protected -> "$key=${value.secret.reveal()}*"
                is FieldValue.Plain -> "$key=${value.text}"
            }
        }
        val config = config(sha1, 8, 60)
        assertEquals(listOf("otp=L*"), pairs(TotpFormat.LINK.fields(config, "L")))
        assertEquals(listOf("TOTP Seed=$seed*", "TOTP Settings=60;8"), pairs(TotpFormat.SEED.fields(config, "L")))
        assertEquals(
            listOf("TimeOtp-Secret-Base32=$seed*", "TimeOtp-Length=6", "TimeOtp-Period=30", "TimeOtp-Algorithm=HMAC-SHA-512"),
            pairs(TotpFormat.KEEPASS.fields(config(sha1, algorithm = Algorithm.SHA512), "L")),
        )
        assertNull(TotpFormat.SEED.fields(config(sha1, algorithm = Algorithm.SHA256), "L"))
        assertNull(TotpFormat.KEEPASS.fields(config(sha1, digits = 9), "L"))
        for (format in TotpFormat.entries) {
            val written = format.fields(config, TotpSetup.otpauthUri(config, "T", "u")).orEmpty()
                .associate { (key, value) -> key to ((value as? FieldValue.Protected)?.secret?.reveal() ?: (value as FieldValue.Plain).text) }
            assertEquals(config, TotpResolver.resolve(written), "$format reads back")
        }
    }
}
