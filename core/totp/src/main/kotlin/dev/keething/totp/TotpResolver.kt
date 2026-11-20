package dev.keething.totp

import dev.keething.domain.text.UserText
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
        parse(fields)
    } catch (_: TotpParseException) {
        null
    }

    /**
     * Why the source [resolve] reads gives no code (Steam, a counter-based link, a secret not Base32, …), said after
     * "This code cannot be used here:" (keething-spec card.totp AC6); null when it gives one, or there is none.
     */
    public fun problem(fields: Map<String, String>): UserText? = try {
        parse(fields)?.secret?.wipe()
        null
    } catch (e: TotpParseException) {
        e.reason()
    }

    private fun parse(fields: Map<String, String>): TotpConfig? = when (TotpFormat.of { fields[it]?.isNotBlank() == true }) {
        TotpFormat.LINK -> TotpParser.parseOtpAuthUri(fields.getValue(TotpFormat.LINK_FIELD))
        TotpFormat.SEED -> TotpParser.parseLegacy(fields.getValue(TotpFormat.SEED_FIELD), fields[TotpFormat.SEED_SETTINGS_FIELD])
        TotpFormat.KEEPASS -> TotpParser.parseKeePass(fields::get)
        null -> null
    }
}

/** Why [this] gave no code, in the user's words: the end of "This code cannot be used here: …". */
internal fun TotpParseException.reason(): UserText = when (this) {
    is TotpParseException.InvalidUri -> UserText.of(TotpText.UNSUPPORTED_NOT_TOTP)
    is TotpParseException.MissingSecret -> UserText.of(TotpText.UNSUPPORTED_NO_SECRET)
    is TotpParseException.InvalidBase32 -> UserText.of(TotpText.UNSUPPORTED_NOT_BASE32)
    is TotpParseException.Unsupported -> when (kind) {
        TotpParseException.Unsupported.Kind.STEAM -> UserText.of(TotpText.UNSUPPORTED_STEAM)
        TotpParseException.Unsupported.Kind.ENCODER -> UserText.of(TotpText.UNSUPPORTED_ENCODER, what)
        TotpParseException.Unsupported.Kind.ALGORITHM -> UserText.of(TotpText.UNSUPPORTED_ALGORITHM, what)
        TotpParseException.Unsupported.Kind.DIGITS -> UserText.of(TotpText.UNSUPPORTED_DIGITS)
        TotpParseException.Unsupported.Kind.PERIOD -> UserText.of(TotpText.UNSUPPORTED_PERIOD)
        TotpParseException.Unsupported.Kind.SETTINGS -> UserText.of(TotpText.UNSUPPORTED_SETTINGS)
    }
}
