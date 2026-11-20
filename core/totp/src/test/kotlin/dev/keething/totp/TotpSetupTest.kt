package dev.keething.totp

import dev.keething.domain.text.english
import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.Base32
import dev.keething.domain.totp.TotpConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Mac `OneTimeCodeTests` "one-time code link" expectations (Mac SPEC §3.23). */
class TotpSetupTest {
    private val key = "12345678901234567890".encodeToByteArray() // RFC 6238's test key
    private val base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    // spec: edit.one-time-code/AC1
    @Test
    fun aSecretIsTakenWithSpacesDashesAndCaseIgnoredAndRefusedWhenTooShort() {
        assertEquals(TotpConfig(Secret.adopt(key.copyOf())), TotpSetup.parse("gezd gnbv-gy3t qojq gezd gnbv gy3t qojq\n"))
        assertFailsWith<TotpSetupException.TooShort> { TotpSetup.parse("GEZDGNBV") }
        assertEquals(TotpSetup.MINIMUM_SECRET_BYTES, TotpSetup.parse("GEZDGNBVGY3TQOJQ").secret.useBytes { it.size })
    }

    // spec: edit.one-time-code/AC2
    @Test
    fun aLinkKeepsItsOwnDigitsPeriodAndAlgorithmAndASecretAloneGetsTheUsual() {
        val link = TotpSetup.parse("otpauth://totp/Site:me?secret=$base32&digits=8&period=60&algorithm=SHA256")
        assertEquals(TotpConfig(Secret.adopt(key.copyOf()), digits = 8, period = 60, algorithm = TotpConfig.Algorithm.SHA256), link)
        val plain = TotpSetup.parse(base32)
        assertEquals(6, plain.digits)
        assertEquals(30, plain.period)
        assertEquals(TotpConfig.Algorithm.SHA1, plain.algorithm)
    }

    // spec: edit.one-time-code/AC3
    @Test
    fun onlyTimeBasedCodesEachRefusalWithItsReason() {
        assertFailsWith<TotpSetupException.Empty> { TotpSetup.parse("  ") }
        assertFailsWith<TotpSetupException.Unreadable> { TotpSetup.parse("not a secret!") }
        assertFailsWith<TotpSetupException.Unreadable> { TotpSetup.parse("otpauth://totp/S?issuer=x") }
        assertEquals("Steam codes", assertFailsWith<TotpSetupException.Unsupported> { TotpSetup.parse("otpauth://totp/S?secret=$base32&encoder=steam") }.reason.english(), "as the card says it")
        assertEquals(
            "only time-based (TOTP) links are supported",
            assertFailsWith<TotpSetupException.Unsupported> { TotpSetup.parse("otpauth://hotp/S?secret=$base32&counter=1") }.reason.english(),
        )
        val export = assertFailsWith<TotpSetupException.Unsupported> { TotpSetup.parse("otpauth-migration://offline?data=abc") }
        assertTrue(export.reason.english().contains("Google Authenticator"))
        assertEquals(null, TotpSetup.parseOrNull("https://example.org"))
    }

    @Test
    fun base32EncodesWithoutPadding() {
        assertEquals(base32, Base32.encode(key))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7), Base32.decode(Base32.encode(byteArrayOf(1, 2, 3, 4, 5, 6, 7))))
        assertEquals("", Base32.encode(ByteArray(0)))
    }

    @Test
    fun theLinkIsWrittenAsKeePassXcWritesItAndReadsBackTheSame() {
        val plain = TotpSetup.otpauthUri(TotpConfig(Secret.adopt(key.copyOf())), title = "Mail & Co", username = "me@x.org")
        assertEquals("otpauth://totp/Mail%20%26%20Co:me%40x.org?secret=$base32&period=30&digits=6&issuer=Mail%20%26%20Co", plain)

        val strong = TotpConfig(Secret.adopt(key.copyOf()), digits = 8, period = 60, algorithm = TotpConfig.Algorithm.SHA512)
        val uri = TotpSetup.otpauthUri(strong, title = "", username = "")
        assertTrue(uri.startsWith("otpauth://totp/KeePassXC:none?"))
        assertTrue(uri.endsWith("&algorithm=SHA512"))
        assertEquals(strong, TotpParser.parseOtpAuthUri(uri))
        assertEquals(TotpConfig(Secret.adopt(key.copyOf())), TotpParser.parseOtpAuthUri(plain))
        assertEquals("otpauth://totp/B%C3%BCro:none?", TotpSetup.otpauthUri(strong, "Büro", "").substringBefore("secret"))
    }
}
