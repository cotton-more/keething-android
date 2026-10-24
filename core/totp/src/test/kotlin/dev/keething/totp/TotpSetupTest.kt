package dev.keething.totp

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Mac `OneTimeCodeTests` "one-time code link" expectations (Mac SPEC §3.23). */
class TotpSetupTest {
    private val key = "12345678901234567890".encodeToByteArray() // RFC 6238's test key
    private val base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

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
