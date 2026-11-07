package dev.keething.totp

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.Base32
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.totp.TotpFormat
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Why a one-time code's fields cannot be used (Mac `TOTPConfig.ParseError`). */
public sealed class TotpParseException(message: String) : Exception(message) {
    public class InvalidUri : TotpParseException("not an otpauth://totp link")

    public class MissingSecret : TotpParseException("no secret")

    public class InvalidBase32 : TotpParseException("the secret is not Base32")

    /** Something the app does not do, such as Steam codes: [what]. */
    public class Unsupported(public val what: String) : TotpParseException("unsupported: $what")
}

/** A link's issuer ("GitHub") and account ("alex@example.com"); empty when the link names none. */
public data class TotpLabel(val issuer: String, val account: String)

/** The formats a one-time code is kept in (Mac `OtpAuthURIParser`, `LegacyTOTPParser` and `KeePassTOTPParser`). */
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

    /**
     * Whom an `otpauth://totp/` link's code is for (Key Uri Format): its issuer (the `issuer` parameter, else the label
     * before `:`) and its account (the label after `:`, or all of it), decoded and trimmed, either empty when the link
     * has none; null for anything but such a link.
     */
    public fun label(uri: String): TotpLabel? {
        val trimmed = uri.trim()
        if (!trimmed.lowercase().startsWith(SCHEME)) return null
        val rest = trimmed.substring(SCHEME.length).substringBefore('#')
        val head = rest.substringBefore('?')
        if (!head.substringBefore('/').equals("totp", ignoreCase = true)) return null
        val raw = head.substringAfter('/', missingDelimiterValue = "")
        val label = percentDecoded(raw) ?: raw
        val issuer = queryParameters(rest.substringAfter('?', missingDelimiterValue = ""))["issuer"]?.trim().orEmpty()
        val prefix = if (':' in label) label.substringBefore(':').trim() else ""
        val account = (if (':' in label) label.substringAfter(':') else label).trim()
        return TotpLabel(issuer.ifEmpty { prefix }, account)
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

    /**
     * KeePass 2.x's own fields (2.47+, its `{TIMEOTP}`), read as KeePass reads them (`EntryUtil.GetOtpSecret`,
     * `HmacOtp.GenerateTimeOtp`): the first secret field there (`TimeOtp-Secret` as UTF-8, `-Hex`, `-Base32`,
     * `-Base64`); `TimeOtp-Length` 6 and `TimeOtp-Period` 30 when missing, not a number or 0; `TimeOtp-Algorithm`
     * SHA-1 when missing, else one of KeePass's three names exactly. A secret that does not decode, or what KeePass
     * makes no code for (more than 8 digits, another algorithm), is no code. [value]: a field's value by its name.
     */
    public fun parseKeePass(value: (String) -> String?): TotpConfig {
        fun present(key: String) = value(key)?.takeIf { it.isNotBlank() }
        val key = TotpFormat.KEEPASS_SECRET_FIELDS.firstOrNull { present(it) != null } ?: throw TotpParseException.MissingSecret()
        val secret = keePassSecret(present(key)!!, key)?.takeIf { it.isNotEmpty() } ?: throw TotpParseException.InvalidBase32()

        val digits = keePassNumber(value(TotpFormat.KEEPASS_LENGTH_FIELD)).takeIf { it != 0 } ?: 6
        if (digits > TotpFormat.KEEPASS_MAXIMUM_DIGITS) throw TotpParseException.Unsupported("digits")
        val period = keePassNumber(value(TotpFormat.KEEPASS_PERIOD_FIELD)).takeIf { it != 0 } ?: 30
        val algorithm = value(TotpFormat.KEEPASS_ALGORITHM_FIELD)?.takeIf { it.isNotEmpty() }?.let { name ->
            TotpConfig.Algorithm.entries.firstOrNull { TotpFormat.keePassAlgorithm(it) == name }
                ?: throw TotpParseException.Unsupported(name)
        } ?: TotpConfig.Algorithm.SHA1
        return TotpConfig(Secret.adopt(secret), digits, period, algorithm)
    }

    private fun keePassSecret(text: String, key: String): ByteArray? {
        val compact = text.filterNot { it.isWhitespace() }
        return when (key) {
            "TimeOtp-Secret" -> text.encodeToByteArray()
            "TimeOtp-Secret-Hex" -> hex(compact)
            "TimeOtp-Secret-Base32" -> Base32.decode(compact)
            // As .NET's Convert.FromBase64String: the padding is not optional.
            else -> if (compact.length % 4 != 0) null else runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
        }
    }

    private fun hex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        return ByteArray(text.length / 2) { i ->
            val high = Character.digit(text[2 * i], 16)
            val low = Character.digit(text[2 * i + 1], 16)
            if (high < 0 || low < 0) return null
            (high * 16 + low).toByte()
        }
    }

    /** As .NET's `uint.TryParse`: 0 when it is not a number (nor one this app can count to). */
    private fun keePassNumber(text: String?): Int =
        text?.trim()?.toUIntOrNull()?.takeIf { it <= Int.MAX_VALUE.toUInt() }?.toInt() ?: 0

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
