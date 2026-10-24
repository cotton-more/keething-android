package dev.keething.totp

import dev.keething.domain.totp.TotpConfig

/**
 * Which of an entry's fields give its one-time code (Mac `TOTPResolver`): the `otp` link if present, else
 * the legacy `TOTP Seed` and `TOTP Settings`. An unusable one is null, never an error.
 */
public object TotpResolver {
    public fun resolve(otp: String?, legacySeed: String?, legacySettings: String?): TotpConfig? = try {
        when {
            otp != null && otp.isNotBlank() -> TotpParser.parseOtpAuthUri(otp) // present but broken: no fallback
            legacySeed != null && legacySeed.isNotBlank() -> TotpParser.parseLegacy(legacySeed, legacySettings)
            else -> null
        }
    } catch (_: TotpParseException) {
        null
    }
}
