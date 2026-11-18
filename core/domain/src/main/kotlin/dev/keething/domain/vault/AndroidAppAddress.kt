package dev.keething.domain.vault

/**
 * `androidapp://com.example.app`: Keepass2Android's way to name an app in an entry's URL fields, which autofill
 * matches (SPEC §7) and the card shows as the app (keething-spec card.app-address).
 */
public object AndroidAppAddress {
    public const val SCHEME: String = "androidapp://"
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") // not user text

    /** The package an address names (the scheme in any case, anything from `/`, `?` or `#` on ignored); else null. */
    public fun packageOf(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
        val name = trimmed.substring(SCHEME.length).takeWhile { it !in "/?#" }
        return name.takeIf(PACKAGE::matches)
    }
}
