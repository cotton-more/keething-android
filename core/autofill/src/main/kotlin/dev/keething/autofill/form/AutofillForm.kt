package dev.keething.autofill.form

import dev.keething.autofill.target.Browsers
import dev.keething.autofill.target.FillTarget
import dev.keething.domain.vault.Entry
import dev.keething.search.site.SiteAddress

/**
 * The fields of a screen to fill and where they belong (SPEC §7), found by [parse]; the fields are their
 * [FormField.index].
 */
public data class AutofillForm(
    val target: FillTarget,
    val usernames: List<Int>,
    val passwords: List<Int>,
    val codes: List<Int>,
    /** A sign-up's or a password change's new password (and its repetition): generated and saved, never filled from an entry. */
    val newPasswords: List<Int> = emptyList(),
) {
    /** Every field an entry's value goes to, in screen order. */
    public val fields: List<Int> get() = (usernames + passwords + codes).sorted()

    /** Every field of the form, the new passwords too. */
    public val allFields: List<Int> get() = (fields + newPasswords).sorted()

    /** A sign-up: only new passwords, no current one. Nothing of an entry is offered; a password can be generated. */
    public val isSignUp: Boolean get() = passwords.isEmpty() && codes.isEmpty() && newPasswords.isNotEmpty()

    /** The fields whose password is saved (SPEC §7, Save): the new one when there is one, else the one typed. */
    public val savedPasswords: List<Int> get() = newPasswords.ifEmpty { passwords }

    /** Only a one-time code is asked for: only entries with one have something to fill. */
    public val asksOnlyForCode: Boolean get() = usernames.isEmpty() && passwords.isEmpty()

    /**
     * What [entry] puts into each field: its username, its password, [code] (the current one-time code, or
     * null when there is none or it is not known yet). Null for a field the entry has nothing for: that field
     * still shows the entry in its list, and choosing it leaves the field as it is. Never a custom field's
     * value: a protected one is never offered (SPEC §7).
     */
    public fun valuesFor(entry: Entry, code: String?): Map<Int, String?> =
        usernames.associateWith { entry.username.ifEmpty { null } } +
            passwords.associateWith { if (entry.password.isEmpty()) null else entry.password.reveal() } +
            codes.associateWith { code }

    public companion object {
        /**
         * The form on a screen of [packageName], or null when there is nothing to fill or save: no login, code
         * or new-password field, Keething itself ([ownPackage]), or a browser page that is not an http(s)
         * site. A sign-up ([isSignUp]) is saved, never filled. [manual]: the user asked for autofill on a field
         * (long press › Autofill), so a focused field that says nothing about itself is taken as the username.
         */
        public fun parse(packageName: String, fields: List<FormField>, ownPackage: String, manual: Boolean = false): AutofillForm? {
            if (packageName == ownPackage) return null
            val kinds = fields.associateWith(FieldClassifier::kind).filterValues { it != FieldKind.IGNORED }
            val browser = Browsers.isBrowser(packageName)
            val target: FillTarget
            val inTarget: Map<FormField, FieldKind>
            if (browser) {
                // The page of the field being filled; fields of another domain (a frame of another site) are left out.
                val recognised = kinds.filterValues { it != FieldKind.PLAIN }.keys
                val anchor = fields.firstOrNull { it.isFocused && it in kinds } ?: recognised.firstOrNull { kinds[it] == FieldKind.PASSWORD }
                    ?: recognised.firstOrNull() ?: return null
                val domain = anchor.webDomain ?: return null
                val site = SiteAddress.parse("${anchor.webScheme ?: "https"}://$domain") ?: return null
                target = FillTarget.WebPage(site, packageName, portKnown = ':' in domain.substringAfterLast(']'))
                inTarget = kinds.filterKeys { it.webDomain == domain && (it.webScheme ?: "https") == (anchor.webScheme ?: "https") }
            } else {
                target = FillTarget.App(packageName)
                inTarget = kinds
            }
            return build(target, inTarget, manual)
        }

        private fun build(target: FillTarget, kinds: Map<FormField, FieldKind>, manual: Boolean): AutofillForm? {
            fun of(kind: FieldKind) = kinds.filterValues { it == kind }.keys.sortedBy { it.index }
            val passwords = of(FieldKind.PASSWORD)
            val codes = of(FieldKind.CODE)
            val newPasswords = of(FieldKind.NEW_PASSWORD)
            var usernames = of(FieldKind.USERNAME).ifEmpty { of(FieldKind.PHONE) }
            val firstPassword = (passwords + newPasswords).minOfOrNull { it.index }
            if (usernames.isEmpty() && firstPassword != null) {
                // The text field right before the first password field.
                usernames = listOfNotNull(of(FieldKind.PLAIN).lastOrNull { it.index < firstPassword })
            }
            // A sign-up: nothing of an entry belongs there; its new password can be generated, and is saved.
            if (passwords.isEmpty() && codes.isEmpty() && newPasswords.isNotEmpty() && !manual) {
                return AutofillForm(target, usernames.map { it.index }, emptyList(), emptyList(), newPasswords.map { it.index })
            }
            if (usernames.isEmpty() && passwords.isEmpty() && codes.isEmpty() && manual) {
                usernames = listOfNotNull(kinds.keys.firstOrNull { it.isFocused && kinds[it] == FieldKind.PLAIN })
            }
            if (usernames.isEmpty() && passwords.isEmpty() && codes.isEmpty()) return null
            // A password change: the entry fills the current password; the new one is saved.
            val changed = if (passwords.isNotEmpty() && !manual) newPasswords.map { it.index } else emptyList()
            return AutofillForm(target, usernames.map { it.index }, passwords.map { it.index }, codes.map { it.index }, changed)
        }
    }
}
