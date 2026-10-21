package dev.keepassbar.card

import dev.keepassbar.search.SearchNormalizer

/**
 * What a custom field's name says about its value (Mac `FieldNameHint`), used only when the vault has no
 * KeePassBar entry with field types. Words are compared folded (case and diacritics ignored).
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

    /** A titled section with at least this many fields, all numbered or id-named, is a list of codes. */
    private const val MINIMUM_CODES = 3

    /** The labels (names after the section's) of a section's fields make it a list of codes (recovery codes). */
    internal fun isCodeList(labels: List<String>): Boolean =
        labels.size >= MINIMUM_CODES && labels.all { label -> label.all(Char::isDigit) || isGeneratedId(label) }

    /** `Section_Field` → ("Section", "Field"), split at the first `_`; null when either side is empty. */
    internal fun split(name: String): Pair<String, String>? {
        val underscore = name.indexOf('_')
        if (underscore < 0) return null
        val section = name.substring(0, underscore).trim()
        val label = name.substring(underscore + 1).trim()
        return if (section.isEmpty() || label.isEmpty()) null else section to label
    }

    /** A field's name without its section heading: `VERTRAG_Ende` → "VERTRAG › Ende"; an id stands for its section. */
    public fun displayName(name: String): String {
        if (isGeneratedId(name)) return "(no name)"
        val (section, label) = split(name) ?: return name
        return if (isGeneratedId(label)) section else "$section › $label"
    }
}
