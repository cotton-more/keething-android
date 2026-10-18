package dev.keepassbar.domain.vault

import dev.keepassbar.domain.secret.Secret

/** A non-standard field of an entry. The order of an entry's fields is the order in the file. */
public data class CustomField(val name: String, val value: FieldValue) {
    /** Marked protected in the file (`Protected="True"`): hidden by default, never searched. */
    val isProtected: Boolean get() = value is FieldValue.Protected

    /** The value when it is not protected; null for a protected one, so that it cannot be read by mistake. */
    val plainText: String? get() = (value as? FieldValue.Plain)?.text

    /**
     * KeePassXC's additional URLs: `KP2A_URL`, `KP2A_URL_1`, … (also what its 1Password import writes for a
     * login's other websites).
     */
    val isAdditionalUrl: Boolean get() = name.uppercase().startsWith("KP2A_URL")

    public companion object {
        public fun plain(name: String, text: String): CustomField = CustomField(name, FieldValue.Plain(text))

        public fun protected(name: String, text: String): CustomField =
            CustomField(name, FieldValue.Protected(Secret.of(text)))
    }
}

/** A custom field's value: plain text, or a secret that only the card and the clipboard reveal. */
public sealed interface FieldValue {
    public data class Plain(val text: String) : FieldValue

    public data class Protected(val secret: Secret) : FieldValue
}
