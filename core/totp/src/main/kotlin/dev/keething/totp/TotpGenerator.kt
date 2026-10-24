package dev.keething.totp

import dev.keething.domain.totp.TotpConfig
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

public object TotpGenerator {
    /** The RFC 6238 code, zero-padded to the config's digits. */
    public fun code(config: TotpConfig, at: Instant): String {
        val period = maxOf(config.period, 1)
        val counter = maxOf(0L, at.epochSecond) / period
        val message = ByteArray(8) { i -> (counter ushr (8 * (7 - i))).toByte() }
        val mac = hmac(config, message)

        // RFC 4226 dynamic truncation.
        val offset = mac.last().toInt() and 0x0F
        val binary = ((mac[offset].toInt() and 0x7F) shl 24) or
            ((mac[offset + 1].toInt() and 0xFF) shl 16) or
            ((mac[offset + 2].toInt() and 0xFF) shl 8) or
            (mac[offset + 3].toInt() and 0xFF)
        mac.fill(0)

        val digits = config.digits.coerceIn(1, 18)
        var modulus = 1L
        repeat(digits) { modulus *= 10 }
        return (binary.toLong() % modulus).toString().padStart(digits, '0')
    }

    /** Seconds until the current code changes (1…period). */
    public fun secondsRemaining(config: TotpConfig, at: Instant): Int {
        val period = maxOf(config.period, 1)
        return period - Math.floorMod(at.epochSecond, period.toLong()).toInt()
    }

    private fun hmac(config: TotpConfig, message: ByteArray): ByteArray {
        val name = when (config.algorithm) {
            TotpConfig.Algorithm.SHA1 -> "HmacSHA1"
            TotpConfig.Algorithm.SHA256 -> "HmacSHA256"
            TotpConfig.Algorithm.SHA512 -> "HmacSHA512"
        }
        val mac = Mac.getInstance(name)
        config.secret.useBytes { mac.init(SecretKeySpec(it, name)) }
        return mac.doFinal(message)
    }
}
