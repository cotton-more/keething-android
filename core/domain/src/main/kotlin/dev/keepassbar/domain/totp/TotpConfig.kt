package dev.keepassbar.domain.totp

import dev.keepassbar.domain.secret.Secret

/** Everything needed to generate one account's one-time codes (RFC 6238). */
public data class TotpConfig(
    /** The key's bytes (already Base32-decoded). */
    val secret: Secret,
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: Algorithm = Algorithm.SHA1,
) {
    public enum class Algorithm { SHA1, SHA256, SHA512 }
}
