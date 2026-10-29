package dev.keething.domain.totp

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.FieldValue

/**
 * The fields an entry's one-time code is kept in (keething-spec `kdbx.otp-storage`; Mac `TOTPFormat`). One entry
 * holds one format; a code is read from the first of them that is there, in this order.
 */
public enum class TotpFormat {
    /** KeePassXC's `otp` link (`otpauth://totp/…`). */
    LINK,

    /** `TOTP Seed` (Base32) and `TOTP Settings` (`<period>;<digits>`): KeePassXC's older form and KeeTrayTOTP's. */
    SEED,

    /**
     * KeePass 2.x's own (2.47+, its `{TIMEOTP}`): a `TimeOtp-Secret…` field with `TimeOtp-Length`, `TimeOtp-Period`
     * and `TimeOtp-Algorithm`.
     */
    KEEPASS,
    ;

    /**
     * The fields that hold [config] in this format, in their order ([link] is the `otp` value); null when the format
     * cannot hold it: `TOTP Settings` has no algorithm (SHA-1 only), KeePass no more than 8 digits. A protected value
     * is a new secret, the caller's.
     */
    public fun fields(config: TotpConfig, link: String): List<Pair<String, FieldValue>>? = when (this) {
        LINK -> listOf(LINK_FIELD to FieldValue.Protected(Secret.of(link)))
        SEED -> if (config.algorithm != TotpConfig.Algorithm.SHA1) {
            null
        } else {
            listOf(
                SEED_FIELD to FieldValue.Protected(Secret.of(config.secret.useBytes(Base32::encode))),
                SEED_SETTINGS_FIELD to FieldValue.Plain("${config.period};${config.digits}"),
            )
        }
        KEEPASS -> if (config.digits > KEEPASS_MAXIMUM_DIGITS) {
            null
        } else {
            listOf(
                "TimeOtp-Secret-Base32" to FieldValue.Protected(Secret.of(config.secret.useBytes(Base32::encode))),
                KEEPASS_LENGTH_FIELD to FieldValue.Plain(config.digits.toString()),
                KEEPASS_PERIOD_FIELD to FieldValue.Plain(config.period.toString()),
                KEEPASS_ALGORITHM_FIELD to FieldValue.Plain(keePassAlgorithm(config.algorithm)),
            )
        }
    }

    public companion object {
        public const val LINK_FIELD: String = "otp"
        public const val SEED_FIELD: String = "TOTP Seed"
        public const val SEED_SETTINGS_FIELD: String = "TOTP Settings"

        /**
         * KeePass's secret in its four encodings, in the order KeePass reads them (`EntryUtil.GetOtpSecret`): UTF-8
         * text, hex, Base32, Base64.
         */
        public val KEEPASS_SECRET_FIELDS: List<String> =
            listOf("TimeOtp-Secret", "TimeOtp-Secret-Hex", "TimeOtp-Secret-Base32", "TimeOtp-Secret-Base64")
        public const val KEEPASS_LENGTH_FIELD: String = "TimeOtp-Length"
        public const val KEEPASS_PERIOD_FIELD: String = "TimeOtp-Period"
        public const val KEEPASS_ALGORITHM_FIELD: String = "TimeOtp-Algorithm"

        /** KeePass makes no code of more digits (`HmacOtp.Generate`). */
        public const val KEEPASS_MAXIMUM_DIGITS: Int = 8

        /** Every field of every format: never a custom field, and all removed before a code is set or removed. */
        public val FIELD_NAMES: Set<String> = setOf(LINK_FIELD, SEED_FIELD, SEED_SETTINGS_FIELD) + KEEPASS_SECRET_FIELDS +
            setOf(KEEPASS_LENGTH_FIELD, KEEPASS_PERIOD_FIELD, KEEPASS_ALGORITHM_FIELD)

        /** KeePass's names for the algorithms (`HmacOtp.AlgHmacSha1` …), SHA-1 when there is none. */
        public fun keePassAlgorithm(algorithm: TotpConfig.Algorithm): String = when (algorithm) {
            TotpConfig.Algorithm.SHA1 -> "HMAC-SHA-1"
            TotpConfig.Algorithm.SHA256 -> "HMAC-SHA-256"
            TotpConfig.Algorithm.SHA512 -> "HMAC-SHA-512"
        }

        /**
         * The format an entry's code is read from, by whether its fields are [present] (there and not blank): `otp`,
         * else `TOTP Seed`, else a KeePass secret; null when there is none of them.
         */
        public fun of(present: (String) -> Boolean): TotpFormat? = when {
            present(LINK_FIELD) -> LINK
            present(SEED_FIELD) -> SEED
            KEEPASS_SECRET_FIELDS.any(present) -> KEEPASS
            else -> null
        }

        /**
         * The format a new code is written in: the [entry]'s own; for an entry without one, the one most of the
         * database's codes use ([database]), and the link when there are none or when it is a tie.
         */
        public fun forNewCode(entry: TotpFormat?, database: Map<TotpFormat, Int>): TotpFormat {
            if (entry != null) return entry
            val most = database.values.maxOrNull() ?: 0
            val leaders = entries.filter { (database[it] ?: 0) == most }
            return if (most > 0 && leaders.size == 1) leaders[0] else LINK
        }
    }
}
