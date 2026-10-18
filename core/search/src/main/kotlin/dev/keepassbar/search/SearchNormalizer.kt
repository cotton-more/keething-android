package dev.keepassbar.search

import java.text.Normalizer
import java.util.Locale

/**
 * Search-time text folding (Mac `SearchNormalizer`): case-insensitive (`ß` is `ss`), diacritic-insensitive,
 * `ё` folded to `е`. The one folding of the app: the card reads field names with it too.
 */
public object SearchNormalizer {
    private val marks = Regex("\\p{M}+")

    public fun normalize(text: String): String {
        // Upper then lower case: a full case folding (ß → SS → ss), as Foundation's folding does.
        val folded = text.uppercase(Locale.ROOT).lowercase(Locale.ROOT)
        return marks.replace(Normalizer.normalize(folded, Normalizer.Form.NFD), "").replace('ё', 'е')
    }
}
