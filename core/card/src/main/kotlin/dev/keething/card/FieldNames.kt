package dev.keething.card

import dev.keething.domain.text.UserText
import dev.keething.domain.vault.CustomField
import dev.keething.search.SearchNormalizer

/**
 * What a custom field's name says about its value (Mac `FieldNameHint`), used only for fields without a type.
 * Words are compared folded (case and diacritics ignored).
 */
internal class FieldNameHint(name: String) {
    private val words = SearchNormalizer.normalize(name).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * Whole words only, so that Spende or Legende never count: expiry / expires / expiration, ablauf…,
     * …frist (Kündigungsfrist), Vertragsende / Laufzeitende, ende, bis, until, thru, срок, до, истек….
     */
    val isExpiry: Boolean
        get() = words.any { w ->
            w.startsWith("expir") || w.startsWith("ablauf") || w.endsWith("frist") || w.startsWith("истек") ||
                w.startsWith("истеч") || w in setOf("ende", "vertragsende", "laufzeitende", "bis", "until", "thru", "срок", "до")
        }

    /** A web address may be written without its scheme here: url, website, hostname, link, сайт. */
    val isWebAddress: Boolean
        get() = words.any { w ->
            w == "url" || w == "uri" || w.startsWith("website") || w.startsWith("webseite") || w == "homepage" ||
                w == "link" || w == "host" || w == "hostname" || w == "domain" || w == "сайт" || w == "ссылка"
        }
}

/**
 * Names as KeePassXC's 1Password import writes them (Mac `ImportedFieldName`): a field in a 1Password section
 * is `Section_Field`, and a field without a title is named by 1Password's generated id.
 */
public object ImportedFieldName {
    /** 1Password's ids: 26 characters of lowercase base32. */
    public fun isGeneratedId(name: String): Boolean = name.length == 26 && name.all { it in 'a'..'z' || it in '2'..'7' }

    /** A titled section with at least this many fields, all numbered or id-named and none typed, is a list of codes. */
    private const val MINIMUM_CODES = 3

    /**
     * Whether a titled section's fields are a list of codes (recovery codes): all typed `code`, or — none of them
     * typed — at least [MINIMUM_CODES] of them labelled (the name after the section's) by numbers or ids.
     */
    internal fun isCodeList(fields: List<Pair<CustomField, String>>, interpreter: FieldInterpreter): Boolean {
        if (fields.isEmpty()) return false
        val types = fields.map { interpreter.type(it.first) }
        if (types.all { it == FieldType.CODE }) return true
        return types.all { it == null } && fields.size >= MINIMUM_CODES &&
            fields.all { (_, label) -> label.all(Char::isDigit) || isGeneratedId(label) }
    }

    /** `Section_Field` → ("Section", "Field"), split at the first `_`; null when either side is empty. */
    internal fun split(name: String): Pair<String, String>? {
        val underscore = name.indexOf('_')
        if (underscore < 0) return null
        val section = name.substring(0, underscore).trim()
        val label = name.substring(underscore + 1).trim()
        return if (section.isEmpty() || label.isEmpty()) null else section to label
    }

    /** A field's name without its section heading: `VERTRAG_Ende` → "VERTRAG › Ende"; an id stands for its section. */
    public fun displayName(name: String): UserText = shownName(name)?.let(UserText::plain) ?: UserText.of(CardText.NO_NAME)

    /** [displayName] when it is the field's own words; null for a generated id. */
    public fun shownName(name: String): String? {
        if (isGeneratedId(name)) return null
        val (section, label) = split(name) ?: return name
        return if (isGeneratedId(label)) section else "$section › $label"
    }
}
