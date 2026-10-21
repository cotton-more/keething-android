package dev.keepassbar.domain.vault

import dev.keepassbar.domain.secret.Secret
import java.time.Instant

/**
 * What an edit of an entry changes (SPEC §3.3; Mac `EntryChanges`); null leaves a part as it is. Applied to the
 * database file itself, so everything an edit does not mention (other fields, attachments, icons, history,
 * other apps' data) stays untouched.
 */
public data class EntryChanges(
    val title: String? = null,
    val username: String? = null,
    val password: Secret? = null,
    val url: String? = null,
    val notes: String? = null,
    /**
     * The custom fields as they should be afterwards, in order: those missing from it are removed, new ones
     * added. The one-time code's fields ([RESERVED_FIELD_NAMES]) are never touched through it.
     */
    val customFields: List<CustomField>? = null,
    val tags: List<String>? = null,
    val expiry: Expiry? = null,
) {
    public data class Expiry(val expires: Boolean, val time: Instant?)

    val isEmpty: Boolean get() = this == EntryChanges()

    public companion object {
        /** The standard fields' keys in the file. */
        public val STANDARD_KEYS: List<String> = listOf("Title", "UserName", "Password", "URL", "Notes")

        /**
         * The fields a one-time code is kept in: KeePassXC's `otp` link, its older `TOTP Seed` / `TOTP Settings`,
         * and KeeTrayTOTP's `TimeOtp-*`.
         */
        public val TOTP_FIELD_NAMES: Set<String> = setOf(
            "otp", "TOTP Seed", "TOTP Settings",
            "TimeOtp-Secret-Base32", "TimeOtp-Length", "TimeOtp-Period", "TimeOtp-Algorithm",
        )

        /** Keys that are not custom fields: the standard ones and the one-time code's. */
        public val RESERVED_FIELD_NAMES: Set<String> = STANDARD_KEYS.toSet() + TOTP_FIELD_NAMES
    }
}
