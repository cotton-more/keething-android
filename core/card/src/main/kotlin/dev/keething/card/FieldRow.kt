package dev.keething.card

import dev.keething.domain.text.UserText
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.FieldValue
import java.time.LocalDate

/** How a row's value is presented. Only unprotected values are ever anything but text. */
public sealed interface ValueStyle {
    public data object Text : ValueStyle

    /** An http(s) address the browser can open. */
    public data class Link(val url: String) : ValueStyle

    /**
     * An app's address, `androidapp://<package>` (keething-spec card.app-address): shown as the app when the phone has
     * it. [fieldName] is the field's name when it is the user's own, null for `URL`, `URL <n>` and `KP2A_URL…`.
     */
    public data class App(val packageName: String, val fieldName: String?) : ValueStyle

    public data class Date(val date: FieldDate) : ValueStyle
}

/** The ids of card rows: the standard rows by name, a custom field by its index, an attachment by its index. */
public object RowIds {
    public const val USERNAME: String = "username"
    public const val PASSWORD: String = "password"
    public const val URL: String = "url"
    public const val TOTP: String = "totp"

    /** A passkey kept in the entry (keething-spec card.passkey). */
    public const val PASSKEY: String = "passkey"
    public const val NOTES: String = "notes"

    /** A note's text (SPEC §6.4), first on its card. */
    public const val NOTE: String = "note"

    public fun custom(index: Int): String = "custom-$index"

    /** The index of the custom field a row id names; null for another row. */
    public fun customIndex(id: String): Int? = id.removePrefix("custom-").takeIf { it != id }?.toIntOrNull()

    public fun attachment(index: Int): String = "attachment-$index"
}

/** One labelled row on the entry card (Mac `FieldRow`). */
public data class FieldRow(
    val id: String,
    val kind: Kind,
    /** A field's name as the user's words; may be empty (a field 1Password named only by its id). */
    val label: UserText,
    /** The value as stored: what copying puts on the clipboard. Empty for a code row and an attachment. */
    val value: FieldValue,
    val style: ValueStyle = ValueStyle.Text,
    /** What the card shows in clear instead of a plain value (a date formatted, a card number grouped). */
    val shownText: String? = null,
    /** The name used in "… copied" (never empty). */
    val noticeLabel: UserText = if (label.isEmptyLabel) UserText.of(CardText.ROW_VALUE) else label,
    /** For dates something ends on. */
    val expiry: ExpiryHint? = null,
    /** The custom field's declared type. */
    val type: FieldType? = null,
    /**
     * Masked although the database does not protect it: a card number, CVC and PIN, and values that are
     * plainly secrets (a private key).
     */
    private val concealed: Boolean = false,
    /** What a concealed row shows while masked (`•••• 1111`); null for the plain mask. */
    val maskedText: String? = null,
    /** A one-time code row's configuration: its code is live. */
    val totp: TotpConfig? = null,
    /** A one-time code row whose fields give no code: why, shown in place of the code (keething-spec card.totp AC6). */
    val codeProblem: UserText? = null,
    /** An attachment row's size in bytes, when known. */
    val size: Int? = null,
    /** A code of a list marked used ([UsedCode]): the day; the row's text and copy are the code alone. */
    val usedOn: LocalDate? = null,
    /** An account number (an IBAN): copied without its spaces, which bank forms often refuse or cut off. */
    val copiesCompact: Boolean = false,
    /** A wallet's recovery phrase, its passphrase, a private key: shown, never copied (SPEC §6.2). */
    val neverCopied: Boolean = false,
) {
    public enum class Kind { USERNAME, PASSWORD, URL, TOTP, PASSKEY, NOTES, NOTE, CUSTOM, ATTACHMENT }

    val isProtected: Boolean get() = value is FieldValue.Protected

    /** The row's name when it is the user's own words (a custom field's, an attachment's); null for the app's own ("Password"). */
    val ownName: String? get() = (label as? UserText.Plain)?.text

    /** Masked until revealed: protected values and the concealed ones. */
    val isConcealed: Boolean get() = isProtected || concealed

    val isEmpty: Boolean
        get() = when (value) {
            is FieldValue.Plain -> value.text.isEmpty()
            is FieldValue.Protected -> value.secret.isEmpty()
        }

    /** An attachment is opened or saved, not copied; a code is there unless it cannot be used; a wallet's secrets never are. */
    val isCopyable: Boolean
        get() = !neverCopied && when (kind) {
            Kind.ATTACHMENT -> false
            Kind.TOTP -> totp != null
            else -> !isEmpty
        }

    /** The address a link row opens. */
    val link: String? get() = (style as? ValueStyle.Link)?.url

    /** The app an app address names. */
    val app: ValueStyle.App? get() = style as? ValueStyle.App

    /** The text shown in clear: [shownText], else the value (revealing a protected one). */
    public fun revealedText(): String = shownText ?: storedText()

    /** The value as stored (revealing a protected one): what copying and sharing give. A used code: the code alone. */
    public fun storedText(): String {
        val text = when (value) {
            is FieldValue.Plain -> value.text
            is FieldValue.Protected -> value.secret.reveal()
        }
        return if (usedOn != null) UsedCode.unmark(text) else text
    }

    /** What copying puts on the clipboard: the value as stored, an account number without its spaces. */
    public fun clipboardText(): String = storedText().let { text -> if (copiesCompact) text.filterNot(Char::isWhitespace) else text }

    /** The same row, masked until revealed although the database does not protect it. */
    internal fun concealed(shownText: String? = null, maskedText: String? = null): FieldRow =
        copy(concealed = true, shownText = shownText ?: this.shownText, maskedText = maskedText ?: this.maskedText)
}

/** An empty name: a field 1Password named only by its id. */
internal val UserText.isEmptyLabel: Boolean get() = this is UserText.Plain && text.isEmpty()

/**
 * Which concealed rows of the open card are shown in clear (Mac `RevealState`). Immutable: each change gives
 * a new state.
 */
public data class RevealState(val revealed: Set<String> = emptySet()) {
    public fun displayValue(row: FieldRow): String {
        if (!row.isConcealed || row.isEmpty) return if (row.isEmpty) "" else row.revealedText()
        return if (row.id in revealed) row.revealedText() else row.maskedText ?: MASK
    }

    public fun isRevealed(id: String): Boolean = id in revealed

    public fun toggle(id: String): RevealState = RevealState(if (id in revealed) revealed - id else revealed + id)

    /**
     * What stays revealed when the entry is read again ([before] → [after], a sync or an edit): a custom field's row
     * only while it is the same field, by name, as a field added or removed before it moves the others' rows.
     */
    public fun keptOn(before: EntryCard, after: EntryCard): RevealState = RevealState(
        revealed.filterTo(mutableSetOf()) { id ->
            val index = RowIds.customIndex(id) ?: return@filterTo true
            val name = before.customFieldNames.getOrNull(index)
            name != null && name == after.customFieldNames.getOrNull(index)
        },
    )

    /** If any concealed row is still masked, reveal them all; otherwise mask them all. */
    public fun toggleAll(rows: List<FieldRow>): RevealState {
        val ids = rows.filter { it.isConcealed && !it.isEmpty }.map { it.id }
        return RevealState(if (ids.any { it !in revealed }) revealed + ids else revealed - ids.toSet())
    }

    public companion object {
        /** Fixed length on purpose: the mask must not tell how long the secret is. */
        public const val MASK: String = "••••••••"

        /**
         * A text with a masked part as a screen reader says it, never the dots (keething-spec app.accessibility AC2):
         * the mask is "hidden", the part shown in clear stays, the end said as the end. "••••••••" is "Hidden",
         * "•••• •••• •••• 1111" "Hidden, ending 1111", "Visa •• 1111" "Visa, hidden, ending 1111". A text without a
         * dot is itself.
         */
        public fun spoken(text: String): UserText {
            val first = text.indexOf('•')
            if (first < 0) return UserText.plain(text)
            val before = text.substring(0, first).trim().replaceFirstChar(Char::uppercase)
            val after = text.substring(text.lastIndexOf('•') + 1).trim()
            return when {
                before.isEmpty() && after.isEmpty() -> UserText.of(CardText.SPOKEN_HIDDEN)
                before.isEmpty() -> UserText.of(CardText.SPOKEN_HIDDEN_ENDING, after)
                after.isEmpty() -> UserText.of(CardText.SPOKEN_SHOWN_HIDDEN, before)
                else -> UserText.of(CardText.SPOKEN_SHOWN_HIDDEN_ENDING, before, after)
            }
        }
    }
}
