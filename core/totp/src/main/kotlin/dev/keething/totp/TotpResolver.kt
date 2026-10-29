package dev.keething.totp

import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.totp.TotpFormat

/**
 * Which of an entry's fields give its one-time code (Mac `TOTPResolver`): the format [TotpFormat.of] finds (the `otp`
 * link, else the legacy `TOTP Seed` and `TOTP Settings`, else KeePass's `TimeOtp-*`), and only that one: a source
 * present but broken gives no code, with no fallback. An unusable one is null, never an error.
 */
public object TotpResolver {
    /** [fields]: the entry's code fields ([TotpFormat.FIELD_NAMES]) by name. */
    public fun resolve(fields: Map<String, String>): TotpConfig? = try {
        when (TotpFormat.of { fields[it]?.isNotBlank() == true }) {
            TotpFormat.LINK -> TotpParser.parseOtpAuthUri(fields.getValue(TotpFormat.LINK_FIELD))
            TotpFormat.SEED -> TotpParser.parseLegacy(fields.getValue(TotpFormat.SEED_FIELD), fields[TotpFormat.SEED_SETTINGS_FIELD])
            TotpFormat.KEEPASS -> TotpParser.parseKeePass(fields::get)
            null -> null
        }
    } catch (_: TotpParseException) {
        null
    }
}
