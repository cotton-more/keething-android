package dev.keething.totp

import dev.keething.domain.secret.Secret
import dev.keething.domain.text.UserText
import dev.keething.domain.totp.Base32
import dev.keething.domain.totp.TotpConfig

/** Why what was given is not a one-time code to set up (Mac `TOTPSetup.Problem`). */
public sealed class TotpSetupException(message: String) : Exception(message) {
    public class Empty : TotpSetupException("empty")

    /** Not a link and not Base32. */
    public class Unreadable : TotpSetupException("unreadable")

    /** Base32, but too short to be a real secret. */
    public class TooShort : TotpSetupException("too short") // not user text

    /** A link the app cannot use (Steam, HOTP, …): [reason], said after "This code cannot be used here:" (a parameter's name, or why). */
    public class Unsupported(public val reason: UserText) : TotpSetupException("unsupported: $reason")
}

/**
 * Setting up a one-time code in the editor (Mac `TOTPSetup`, Mac SPEC §3.23; keething-spec edit.one-time-code): reading
 * what the user gives (an `otpauth://` link, typed, pasted or from a QR code, or the bare secret a site shows) and the
 * link written into the entry's `otp` field, as KeePassXC writes it.
 */
public object TotpSetup {
    /** Fewer bytes are no real site's secret (RFC 4226 asks for 16; old sites give 10). */
    public const val MINIMUM_SECRET_BYTES: Int = 10

    /**
     * The code in [text]: a link with its own digits, period and algorithm, or a secret with the usual 6 digits,
     * 30 seconds, SHA-1. Spaces and dashes in a secret are ignored (sites group it), and its case.
     */
    public fun parse(text: String): TotpConfig {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw TotpSetupException.Empty()
        val lower = trimmed.lowercase()
        if (lower.startsWith("otpauth://")) {
            try {
                return TotpParser.parseOtpAuthUri(trimmed)
            } catch (e: TotpParseException) {
                throw when (e) {
                    is TotpParseException.Unsupported -> TotpSetupException.Unsupported(e.reason())
                    is TotpParseException.InvalidUri -> TotpSetupException.Unsupported(UserText.of(TotpText.UNSUPPORTED_NOT_TOTP))
                    is TotpParseException.MissingSecret, is TotpParseException.InvalidBase32 -> TotpSetupException.Unreadable()
                }
            }
        }
        if (lower.startsWith("otpauth-migration://")) {
            throw TotpSetupException.Unsupported(UserText.of(TotpText.UNSUPPORTED_GOOGLE_EXPORT))
        }
        val secret = trimmed.filterNot { it.isWhitespace() || it == '-' }
        val bytes = Base32.decode(secret)?.takeIf { it.isNotEmpty() } ?: throw TotpSetupException.Unreadable()
        if (bytes.size < MINIMUM_SECRET_BYTES) {
            bytes.fill(0)
            throw TotpSetupException.TooShort()
        }
        return TotpConfig(Secret.adopt(bytes))
    }

    /** [parse], or null when [text] is no code. */
    public fun parseOrNull(text: String): TotpConfig? = try {
        parse(text)
    } catch (_: TotpSetupException) {
        null
    }

    /**
     * The `otp` value, as KeePassXC 2.7 writes it (`Totp::writeSettings`): the title as issuer and label, the
     * username as account; the algorithm only when not SHA-1. Everything but RFC 3986's unreserved ASCII is
     * percent-encoded, as KeePassXC's `QUrl::toPercentEncoding` does (the Mac keeps non-ASCII letters; both read back).
     */
    public fun otpauthUri(config: TotpConfig, title: String, username: String): String {
        val issuer = if (title.isEmpty()) "KeePassXC" else percentEncoded(title) // not user text
        val account = if (username.isEmpty()) "none" else percentEncoded(username)
        val secret = config.secret.useBytes(Base32::encode)
        var uri = "otpauth://totp/$issuer:$account?secret=$secret&period=${config.period}&digits=${config.digits}&issuer=$issuer"
        if (config.algorithm != TotpConfig.Algorithm.SHA1) uri += "&algorithm=${config.algorithm.name}"
        return uri
    }

    private fun percentEncoded(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") append(c) else append("%%%02X".format(byte.toInt() and 0xFF))
        }
    }
}
