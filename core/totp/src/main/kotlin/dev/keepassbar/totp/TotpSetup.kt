package dev.keepassbar.totp

import dev.keepassbar.domain.totp.TotpConfig

/** A one-time code written into an entry (Mac `TOTPSetup`, Mac SPEC §3.23); setting one up by hand is not ported yet. */
public object TotpSetup {
    /**
     * The `otp` value, as KeePassXC 2.7 writes it (`Totp::writeSettings`): the title as issuer and label, the
     * username as account; the algorithm only when not SHA-1. Everything but RFC 3986's unreserved ASCII is
     * percent-encoded, as KeePassXC's `QUrl::toPercentEncoding` does (the Mac keeps non-ASCII letters; both read back).
     */
    public fun otpauthUri(config: TotpConfig, title: String, username: String): String {
        val issuer = if (title.isEmpty()) "KeePassXC" else percentEncoded(title)
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
