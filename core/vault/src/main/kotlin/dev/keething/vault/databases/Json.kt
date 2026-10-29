package dev.keething.vault.databases

/** Text that is not the JSON it should be. */
internal class JsonException(message: String) : Exception(message)

/**
 * Just enough JSON for the list of databases (core/vault depends on no library): objects as maps, arrays as lists,
 * strings, numbers as [Double], true, false and null; written with sorted keys and without blanks, as the Mac's
 * `JSONEncoder` with `.sortedKeys` writes its list.
 */
internal object Json {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value(depth = 0)
        reader.blanks()
        if (!reader.atEnd) throw JsonException("text after the value")
        return value
    }

    fun write(value: Any?): String = StringBuilder().also { it.value(value) }.toString()

    private fun StringBuilder.value(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean -> append(value)
            is String -> string(value)
            is Number -> number(value.toDouble())
            is Map<*, *> -> {
                append('{')
                value.entries.map { (key, item) -> (key as String) to item }.sortedBy { it.first }.forEachIndexed { index, (key, item) ->
                    if (index > 0) append(',')
                    string(key)
                    append(':')
                    value(item)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    value(item)
                }
                append(']')
            }
            else -> throw IllegalArgumentException("not a JSON value: ${value.javaClass.simpleName}")
        }
    }

    /** A whole number as one (`1`, not `1.0`); the others as Kotlin writes a [Double]. */
    private fun StringBuilder.number(number: Double) {
        require(number.isFinite()) { "JSON has no $number" }
        if (number == Math.rint(number) && Math.abs(number) < 1e15) append(number.toLong()) else append(number)
    }

    private fun StringBuilder.string(text: String) {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private class Reader(private val text: String) {
        private var at = 0

        val atEnd: Boolean get() = at == text.length

        fun blanks() {
            while (at < text.length && text[at] in " \t\r\n") at++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("nested too deep")
            blanks()
            if (atEnd) throw JsonException("a value missing")
            return when (text[at]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> number()
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            at++
            val map = LinkedHashMap<String, Any?>()
            blanks()
            if (take('}')) return map
            do {
                blanks()
                if (atEnd || text[at] != '"') throw JsonException("a key missing")
                val key = string()
                blanks()
                if (!take(':')) throw JsonException("':' missing")
                map[key] = value(depth + 1)
                blanks()
            } while (take(','))
            if (!take('}')) throw JsonException("'}' missing")
            return map
        }

        private fun array(depth: Int): List<Any?> {
            at++
            val list = mutableListOf<Any?>()
            blanks()
            if (take(']')) return list
            do {
                list += value(depth + 1)
                blanks()
            } while (take(','))
            if (!take(']')) throw JsonException("']' missing")
            return list
        }

        private fun string(): String {
            at++
            val out = StringBuilder()
            while (true) {
                if (atEnd) throw JsonException("a string not ended")
                val char = text[at++]
                when {
                    char == '"' -> return out.toString()
                    char < ' ' -> throw JsonException("a control character in a string")
                    char != '\\' -> out.append(char)
                    atEnd -> throw JsonException("a string not ended")
                    else -> when (val escaped = text[at++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (at + 4 > text.length) throw JsonException("a short \\u")
                            out.append(text.substring(at, at + 4).toIntOrNull(16)?.toChar() ?: throw JsonException("a bad \\u"))
                            at += 4
                        }
                        else -> throw JsonException("a bad escape")
                    }
                }
            }
        }

        private fun number(): Double {
            val match = NUMBER.matchAt(text, at) ?: throw JsonException("not a value")
            at += match.value.length
            return match.value.toDouble()
        }

        private fun word(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) throw JsonException("not a value")
            at += word.length
            return value
        }

        private fun take(char: Char): Boolean {
            if (atEnd || text[at] != char) return false
            at++
            return true
        }
    }

    private const val MAX_DEPTH = 32
    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
