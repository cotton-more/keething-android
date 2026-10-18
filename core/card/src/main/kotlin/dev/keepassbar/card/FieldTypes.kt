package dev.keepassbar.card

import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.domain.vault.Entry

/** What a custom field holds, declared in the KeePassBar entry (Mac `FieldType`). */
public enum class FieldType(public val typeName: String) {
    /** A date (`2021-05-03`): shown formatted. */
    DATE("date"),

    /** A date something ends on (`gültig bis`, `Kündigungsfrist`): shown with how it stands. */
    UNTIL("until"),

    /** Parts of a payment card: an entry with a `card.number` is drawn as a card. */
    CARD_NUMBER("card.number"),
    CARD_HOLDER("card.holder"),
    CARD_EXPIRY("card.expiry"),
    CARD_CVC("card.cvc"),
    CARD_PIN("card.pin"),
    CARD_TYPE("card.type"),

    /** Parts of a Wi-Fi network: an entry with a `wifi.ssid` offers a QR code to join it. */
    WIFI_SSID("wifi.ssid"),
    WIFI_PASSWORD("wifi.password"),
    WIFI_SECURITY("wifi.security"),
    ;

    /** Read as a date. */
    public val isDate: Boolean get() = this == DATE || this == UNTIL || this == CARD_EXPIRY

    /** A date something ends on: gets the expiry hint and counts as a deadline. */
    public val endsSomething: Boolean get() = this == UNTIL || this == CARD_EXPIRY
    internal val isCardPart: Boolean get() = typeName.startsWith("card.")
    internal val isWiFiPart: Boolean get() = typeName.startsWith("wifi.")

    public companion object {
        public fun of(typeName: String): FieldType? = entries.firstOrNull { it.typeName == typeName.lowercase() }
    }
}

/**
 * The field types of a vault, read from its KeePassBar entry (Mac `FieldTypes`): one rule per line of its
 * notes, `<field name> = <type>` (split at the last `=`; `#` starts a comment line), and its custom fields,
 * name = the field name, value = the type. Field names match whole and ignoring case. With no KeePassBar entry
 * the card recognises a few forms on its own; with one, nothing is guessed.
 */
public class FieldTypes private constructor(
    /** True when the vault has a KeePassBar entry (even an empty one): then only declared types count. */
    public val isConfigured: Boolean,
    /** In the order written (notes first, then custom fields); a later rule for the same name wins. */
    public val rules: List<Rule>,
    /** Lines that could not be read and unknown types. */
    public val problems: List<String>,
) {
    public data class Rule(
        val fieldName: String,
        /** As written. */
        val typeName: String,
        /** Null when [typeName] is no [FieldType]. */
        val type: FieldType?,
        /** "notes, line 3" or "field 'gültig bis'". */
        val source: String,
    )

    private val byName: Map<String, FieldType?> = rules.associate { key(it.fieldName) to it.type }

    public fun type(fieldName: String): FieldType? = byName[key(fieldName)]

    public companion object {
        /** No KeePassBar entry. */
        public val NONE: FieldTypes = FieldTypes(false, emptyList(), emptyList())

        /** The Mac app's e-mail alias providers' keys live in the same entry; they are not rules. */
        private val ALIAS_KEY_FIELDS = setOf("simplelogin api key", "addy.io api key", "duckduckgo token")

        private fun key(name: String) = name.trim().lowercase()

        public fun from(settings: Entry?): FieldTypes {
            if (settings == null) return NONE
            val rules = mutableListOf<Rule>()
            val problems = mutableListOf<String>()
            fun add(name: String, typeName: String, source: String) {
                val trimmed = typeName.trim()
                val type = FieldType.of(trimmed)
                if (type == null) problems += "$source: '$trimmed' is not a type"
                rules += Rule(name.trim(), trimmed, type, source)
            }
            for ((n, raw) in settings.notes.split(Regex("[\n\r\u0085  ]")).withIndex()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.lastIndexOf('=')
                if (eq < 0 || line.substring(0, eq).isBlank()) {
                    problems += "notes, line ${n + 1}: expected '<field name> = <type>'"
                    continue
                }
                add(line.substring(0, eq), line.substring(eq + 1), "notes, line ${n + 1}")
            }
            for (field in settings.customFields) {
                if (key(field.name) in ALIAS_KEY_FIELDS) continue
                // Fail closed: a protected value is never read, not even as a rule (and never repeated here).
                val value = field.plainText
                if (value == null) {
                    problems += "field '${field.name}': protected, so it is not read (a rule needs no protection)"
                    continue
                }
                add(field.name, value, "field '${field.name}'")
            }
            return FieldTypes(true, rules, problems)
        }
    }
}

/** How custom fields are read, shared by the card, the list and the deadlines. A protected value is never read. */
internal class FieldInterpreter(val types: FieldTypes) {
    fun type(field: CustomField): FieldType? = types.type(field.name)

    /**
     * The date in an unprotected field and whether it ends something. With field types: only date-like types
     * (any accepted form); without: the unmistakable forms, ending something when the name says so.
     */
    fun date(field: CustomField): Pair<FieldDate, Boolean>? {
        val value = field.plainText?.takeIf { it.isNotEmpty() } ?: return null
        if (types.isConfigured) {
            val type = types.type(field.name)?.takeIf { it.isDate } ?: return null
            return FieldDate.parse(value, typed = true)?.let { it to type.endsSomething }
        }
        return FieldDate.parse(value, typed = false)?.let { it to FieldNameHint(field.name).isExpiry }
    }
}
