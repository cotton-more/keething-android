package dev.keething.domain.vault

/**
 * Where an entry keeps the types of its custom fields (Mac SPEC §3.12, Mac `EntryFieldTypes`): one item of the
 * entry's `CustomData`, which KeePassXC keeps through its own edits and which goes into the history with the
 * entry. The value is a JSON object, field name → type name; the fields' values stay plain text, so other apps
 * show them as they are.
 */
public object EntryFieldTypes {
    public const val CUSTOM_DATA_KEY: String = "Keething.FieldTypes"

    /** Null when [text] is not a JSON object of strings. */
    public fun decode(text: String): Map<String, String>? = JsonStrings(text).objectOfStrings()

    /** Keys sorted, so the same types always make the same text (as the Mac's `JSONSerialization` writes it). */
    public fun encode(types: Map<String, String>): String =
        types.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (name, type) -> quote(name) + ":" + quote(type) }

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** Just enough JSON for an object whose values are all strings: core/domain depends on no library. */
    private class JsonStrings(private val text: String) {
        private var at = 0

        fun objectOfStrings(): Map<String, String>? {
            val result = LinkedHashMap<String, String>()
            space()
            if (!take('{')) return null
            space()
            if (!take('}')) {
                do {
                    space()
                    val name = string() ?: return null
                    space()
                    if (!take(':')) return null
                    space()
                    result[name] = string() ?: return null
                    space()
                } while (take(','))
                if (!take('}')) return null
            }
            space()
            return if (at == text.length) result else null
        }

        private fun take(c: Char): Boolean = (at < text.length && text[at] == c).also { if (it) at++ }

        private fun space() {
            while (at < text.length && text[at] in " \t\n\r") at++
        }

        private fun string(): String? {
            if (!take('"')) return null
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c < ' ' -> return null
                    c != '\\' -> out.append(c)
                    at >= text.length -> return null
                    else -> when (val e = text[at++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = text.substring(at, minOf(at + 4, text.length))
                            if (hex.length < 4 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                            out.append(hex.toInt(16).toChar())
                            at += 4
                        }
                        else -> return null
                    }
                }
            }
            return null
        }
    }
}
