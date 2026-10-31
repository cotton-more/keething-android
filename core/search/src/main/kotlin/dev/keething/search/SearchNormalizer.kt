package dev.keething.search

import dev.keething.domain.text.TextFolding

/**
 * Search-time text folding (Mac `SearchNormalizer`): case-insensitive (`ß` is `ss`), diacritic-insensitive,
 * `ё` folded to `е`. The app's one folding ([TextFolding]): the card reads field names with it too.
 */
public object SearchNormalizer {
    public fun normalize(text: String): String = TextFolding.fold(text)
}
