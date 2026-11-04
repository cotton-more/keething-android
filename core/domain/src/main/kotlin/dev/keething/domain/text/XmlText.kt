package dev.keething.domain.text

/**
 * Text as a KeePass file can hold it in the clear (keething-spec kdbx.hardening AC7): XML 1.0 has no place for
 * U+0000–U+001F but tab, LF and CR, nor for U+FFFE and U+FFFF. KeePass 2.x and KeePassXC leave them out of a plain
 * value when they write it, and so does the app's writer; the save's check expects a plain value so. A protected value
 * is stored as base64 and keeps every character.
 */
public object XmlText {
    public fun holds(char: Char): Boolean =
        (char >= ' ' || char == '\t' || char == '\n' || char == '\r') && char != '￾' && char != '￿'

    /** [text] without the characters XML 1.0 cannot hold; the same string when it has none. */
    public fun writable(text: String): String = if (text.all(::holds)) text else text.filter(::holds)
}
