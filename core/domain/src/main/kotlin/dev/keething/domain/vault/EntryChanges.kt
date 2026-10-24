package dev.keething.domain.vault

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
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
    /** The field types as they should be afterwards (Mac SPEC §3.12); empty removes them. */
    val fieldTypes: Map<String, String>? = null,
    val tags: List<String>? = null,
    val expiry: Expiry? = null,
    /** The one-time code (Mac SPEC §3.23): set (the `otp` link written, the older fields removed) or removed. */
    val totp: TotpChange? = null,
) {
    public data class Expiry(val expires: Boolean, val time: Instant?)

    val isEmpty: Boolean get() = this == EntryChanges()

    /** Overwrites the secrets it carries (once the save is done with them). */
    public fun wipe() {
        password?.wipe()
        customFields?.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() }
        (totp as? TotpChange.Set)?.config?.secret?.wipe()
    }

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

/** A one-time code set or removed (Mac `TOTPChange`). */
public sealed interface TotpChange {
    /** [uri] is what goes into `otp`; [config] what it means (the save's check compares it). */
    public data class Set(val config: TotpConfig, val uri: String) : TotpChange {
        // The link holds the key: never in a log.
        override fun toString(): String = "Set(•••)"
    }

    public data object Remove : TotpChange
}
