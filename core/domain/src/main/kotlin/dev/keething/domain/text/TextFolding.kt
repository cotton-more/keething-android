package dev.keething.domain.text

import java.text.Normalizer
import java.util.Locale

/**
 * The one folding of the app (Mac `SearchNormalizer`): case-insensitive (`ß` is `ss`), diacritic-insensitive, `ё`
 * folded to `е`. Searching, the card's field names, a group path's levels and a group's name taken compare with it.
 */
public object TextFolding {
    private val marks = Regex("\\p{M}+")

    public fun fold(text: String): String {
        // Upper then lower case: a full case folding (ß → SS → ss), as Foundation's folding does.
        val folded = text.uppercase(Locale.ROOT).lowercase(Locale.ROOT)
        return marks.replace(Normalizer.normalize(folded, Normalizer.Form.NFD), "").replace('ё', 'е')
    }
}
