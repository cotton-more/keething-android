package dev.keepassbar.totp

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.totp.TotpConfig
import java.io.ByteArrayOutputStream

/** Why a one-time code's fields cannot be used (Mac `TOTPConfig.ParseError`). */
public sealed class TotpParseException(message: String) : Exception(message) {
    public class InvalidUri : TotpParseException("not an otpauth://totp link")

    public class MissingSecret : TotpParseException("no secret")

    public class InvalidBase32 : TotpParseException("the secret is not Base32")

    /** Something the app does not do, such as Steam codes: [what]. */
    public class Unsupported(public val what: String) : TotpParseException("unsupported: $what")
}

/** The two formats a one-time code is kept in (Mac `OtpAuthURIParser` and `LegacyTOTPParser`). */
public object TotpParser {
    private const val SCHEME = "otpauth://"

    /**
     * `otpauth://totp/Label?secret=BASE32&period=30&digits=6&algorithm=SHA1&issuer=X`. By hand rather than
     * with a URI class: real values often have unescaped spaces in the label.
     */
    public fun parseOtpAuthUri(uri: String): TotpConfig {
        val trimmed = uri.trim()
        if (!trimmed.lowercase().startsWith(SCHEME)) throw TotpParseException.InvalidUri()
        val rest = trimmed.substring(SCHEME.length).substringBefore('#')
        val head = rest.substringBefore('?')
        val query = rest.substringAfter('?', missingDelimiterValue = "")
        if (!head.substringBefore('/').equals("totp", ignoreCase = true)) throw TotpParseException.InvalidUri()

        val params = queryParameters(query)
        params["encoder"]?.takeIf { it.isNotEmpty() }?.let { throw TotpParseException.Unsupported(it.lowercase()) }
        val secretText = params["secret"]?.takeIf { it.isNotEmpty() } ?: throw TotpParseException.MissingSecret()
        val secret = Base32.decode(secretText)?.takeIf { it.isNotEmpty() } ?: throw TotpParseException.InvalidBase32()

        val period = params["period"]?.let(::period) ?: 30
        val digits = params["digits"]?.let(::digits) ?: 6
        val algorithm = params["algorithm"]?.let { name ->
            TotpConfig.Algorithm.entries.firstOrNull { it.name == name.uppercase() }
                ?: throw TotpParseException.Unsupported(name)
        } ?: TotpConfig.Algorithm.SHA1
        return TotpConfig(Secret.adopt(secret), digits, period, algorithm)
    }

    /** KeePass's legacy fields: `TOTP Seed` (Base32) and `TOTP Settings` (`<period>;<digits>`, `<period>;S` for Steam). */
    public fun parseLegacy(seed: String, settings: String?): TotpConfig {
        var period = 30
        var digits = 6
        if (settings != null && settings.isNotBlank()) {
            val parts = settings.split(';').map { it.trim() }
            if (parts.size != 2) throw TotpParseException.Unsupported("settings")
            if (parts[1].uppercase() == "S") throw TotpParseException.Unsupported("steam")
            period = period(parts[0])
            digits = digits(parts[1])
        }
        if (seed.isBlank()) throw TotpParseException.MissingSecret()
        val secret = Base32.decode(seed)?.takeIf { it.isNotEmpty() } ?: throw TotpParseException.InvalidBase32()
        return TotpConfig(Secret.adopt(secret), digits, period)
    }

    private fun period(text: String): Int =
        text.toIntOrNull()?.takeIf { it > 0 } ?: throw TotpParseException.Unsupported("period")

    /** A 31-bit truncated value has at most 10 decimal digits. */
    private fun digits(text: String): Int =
        text.toIntOrNull()?.takeIf { it in 1..10 } ?: throw TotpParseException.Unsupported("digits")

    private fun queryParameters(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val raw = pair.substringAfter('=', missingDelimiterValue = "")
            pair.substringBefore('=').lowercase() to (percentDecoded(raw) ?: raw)
        }

    /** `%XX` sequences as UTF-8; `+` stays a plus. Null when a sequence is broken or not UTF-8. */
    private fun percentDecoded(text: String): String? {
        if ('%' !in text) return text
        val out = StringBuilder(text.length)
        val pending = ByteArrayOutputStream()
        fun flush(): Boolean {
            if (pending.size() == 0) return true
            val decoded = runCatching { pending.toByteArray().decodeToString(throwOnInvalidSequence = true) }
            pending.reset()
            return decoded.getOrNull()?.let { out.append(it) } != null
        }
        var i = 0
        while (i < text.length) {
            if (text[i] == '%') {
                if (i + 2 > text.lastIndex) return null
                val high = Character.digit(text[i + 1], 16)
                val low = Character.digit(text[i + 2], 16)
                if (high < 0 || low < 0) return null
                pending.write(high * 16 + low)
                i += 3
            } else {
                if (!flush()) return null
                out.append(text[i])
                i++
            }
        }
        return if (flush()) out.toString() else null
    }
}
