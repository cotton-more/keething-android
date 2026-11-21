package dev.keething.domain.vault

/**
 * A passkey kept in an entry as KeePassXC keeps it (keething-spec card.passkey; KeePassXC's `EntryAttributes` and
 * `BrowserService::addPasskeyToEntry`): custom fields whose names start with `KPEX_PASSKEY`, the private key, the
 * credential ID and the user handle protected. What the app shows of it: the [site] (the relying party's id) and the
 * [user]. Signing in with it is not built (the idea autofill.passkeys).
 */
public data class Passkey(val site: String, val user: String) {
    public companion object {
        /** What every one of a passkey's field names starts with, in exact case (KeePassXC's `isPasskeyAttribute`). */
        public const val FIELD_PREFIX: String = "KPEX_PASSKEY"
        public const val RELYING_PARTY: String = "KPEX_PASSKEY_RELYING_PARTY"
        public const val USERNAME: String = "KPEX_PASSKEY_USERNAME"

        /** Strongbox's name for the user, which KeePassXC reads first when an entry has it. */
        public const val STRONGBOX_USERNAME: String = "KPXC_PASSKEY_USERNAME"

        /** A passkey's field: kept apart from the custom fields, whether or not the entry has a passkey ([of]). */
        public fun isField(name: String): Boolean = name.startsWith(FIELD_PREFIX) || name == STRONGBOX_USERNAME

        /**
         * The passkey [fields] make, null when none of them starts with [FIELD_PREFIX]. A protected site or user is not
         * read (fail closed): shown as missing.
         */
        public fun of(fields: List<CustomField>): Passkey? {
            if (fields.none { it.name.startsWith(FIELD_PREFIX) }) return null
            fun text(name: String) = fields.firstOrNull { it.name == name }?.plainText
            return Passkey(text(RELYING_PARTY).orEmpty(), (text(STRONGBOX_USERNAME) ?: text(USERNAME)).orEmpty())
        }
    }
}
