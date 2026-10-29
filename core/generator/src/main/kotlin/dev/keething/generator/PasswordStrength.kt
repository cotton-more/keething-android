package dev.keething.generator

import kotlin.math.log2

/**
 * A rough, offline estimate of how hard a typed password is to guess (the Mac's `PasswordStrength`, Mac SPEC §3.14).
 * Not zxcvbn: it counts the character pool and the length, and counts each recognisable pattern — a run of one
 * character, a sequence (`abcd`, `4321`), a keyboard row (`qwerty`, `asdf`), a year, a very common password or word,
 * the context's words (a database's name) — as a single character.
 */
public data class PasswordStrength(
    /** Estimated entropy in bits. */
    val bits: Double,
    val verdict: Strength,
    /** Why it is weak or fair ("shorter than 8 characters", "a keyboard pattern"); empty for a strong one. */
    val reasons: List<String>,
) {
    public companion object {
        internal const val MINIMUM_LENGTH: Int = 8
        private const val WEAK_BITS = 40.0
        private const val FAIR_BITS = 60.0

        /** [context]: words a guesser would try first (the username, the title, the site's or the database's name). */
        public fun estimate(password: String, context: List<String> = emptyList()): PasswordStrength {
            val chars = password.codePoints().toArray()
            if (chars.isEmpty()) return PasswordStrength(0.0, Strength.WEAK, listOf("empty"))
            val lower = password.lowercase()
            if (lower in CommonPasswords.all) return PasswordStrength(10.0, Strength.WEAK, listOf("one of the most common passwords"))

            val reasons = mutableListOf<String>()
            val covered = BooleanArray(chars.size)
            var patternCount = 0
            fun cover(range: IntRange, reason: String) {
                if (range.isEmpty() || range.any { covered[it] }) return
                range.forEach { covered[it] = true }
                patternCount++
                if (reason !in reasons) reasons += reason
            }

            val folded = chars.map(::lowerCodePoint).toIntArray()
            val leet = folded.map { LEET[it] ?: it }.toIntArray()
            // Longest patterns first, so that "password2024" is one word and one year.
            for (token in contextTokens(context)) for (r in ranges(token, folded)) cover(r, "contains the username or the site's name")
            for (word in CommonPasswords.wordCodes) for (r in ranges(word, leet)) cover(r, "a common word")
            // Keyboard rows: from each position, the longest piece (4+) of a row, either direction.
            if (folded.size >= 4) {
                for (i in 0..folded.size - 4) {
                    for (j in minOf(folded.size, i + LONGEST_ROW) downTo i + 4) {
                        if (String(folded, i, j - i) in KEYBOARD_PIECES) {
                            cover(i until j, "a keyboard pattern")
                            break
                        }
                    }
                }
            }
            for (r in years(chars)) cover(r, "a year")
            for (r in runs(folded, 0)) cover(r, "repeated characters")
            for (r in runs(folded, 1) + runs(folded, -1)) cover(r, "a sequence")

            val effectiveLength = (covered.count { !it } + patternCount).toDouble()
            val bits = effectiveLength * log2(poolSize(chars).toDouble())
            // Not the exact length: the text must not tell more about a password than its mask does.
            if (chars.size < MINIMUM_LENGTH) reasons.add(0, "shorter than $MINIMUM_LENGTH characters")
            if (chars.all { Character.isDigit(it) }) reasons += "digits only"
            val verdict = when {
                chars.size < MINIMUM_LENGTH || bits < WEAK_BITS -> Strength.WEAK
                bits < FAIR_BITS -> Strength.FAIR
                else -> Strength.STRONG
            }
            if (verdict != Strength.STRONG && reasons.isEmpty()) reasons += if (verdict == Strength.WEAK) "too short for its characters" else "could be longer"
            return PasswordStrength(bits, verdict, if (verdict == Strength.STRONG) emptyList() else reasons)
        }

        /** Keyboard rows, in both directions. */
        private val KEYBOARD_ROWS: List<IntArray> = listOf(
            "qwertyuiop", "asdfghjkl", "zxcvbnm", "qwertzuiop", "yxcvbnm", "azertyuiop", "1234567890",
            "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю",
        ).flatMap { listOf(it.codePoints().toArray(), it.reversed().codePoints().toArray()) }

        private val LONGEST_ROW = KEYBOARD_ROWS.maxOf { it.size }

        /** Every piece of 4+ characters of a row: one lookup per candidate instead of a search. */
        private val KEYBOARD_PIECES: Set<String> = KEYBOARD_ROWS.flatMap { row ->
            (0 until row.size).flatMap { i -> (i + 4..row.size).map { j -> String(row, i, j - i) } }
        }.toSet()

        private val LEET: Map<Int, Int> = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i')
            .entries.associate { (from, to) -> from.code to to.code }

        private fun lowerCodePoint(code: Int): Int = Character.toLowerCase(code)

        private fun isAscii(code: Int): Boolean = code < 0x80

        private fun poolSize(chars: IntArray): Int {
            var pool = 0
            if (chars.any { isAscii(it) && Character.isLowerCase(it) }) pool += 26
            if (chars.any { isAscii(it) && Character.isUpperCase(it) }) pool += 26
            if (chars.any { isAscii(it) && Character.isDigit(it) }) pool += 10
            if (chars.any { isAscii(it) && !Character.isLetter(it) && !Character.isDigit(it) }) pool += 33
            if (chars.any { !isAscii(it) }) pool += 100
            return maxOf(pool, 2)
        }

        /** Words of 4+ letters from the context, lowercased: "jane.doe@example.com" → jane, example. */
        private fun contextTokens(context: List<String>): List<IntArray> {
            val common = setOf("www", "http", "https", "com", "org", "net", "mail", "login", "account")
            return context.flatMap { it.lowercase().split(Regex("[^\\p{L}\\p{N}]+")) }
                .filter { it.codePointCount(0, it.length) >= 4 && it !in common }
                .distinct()
                .sortedByDescending { it.codePointCount(0, it.length) }
                .map { it.codePoints().toArray() }
        }

        private fun ranges(needle: IntArray, hay: IntArray): List<IntRange> {
            if (needle.size < 3 || needle.size > hay.size) return emptyList()
            return (0..hay.size - needle.size).filter { start -> needle.indices.all { hay[start + it] == needle[it] } }
                .map { it until it + needle.size }
        }

        /** Runs of 3+ characters whose code points step by [step] (0: the same character). */
        private fun runs(chars: IntArray, step: Int): List<IntRange> {
            val result = mutableListOf<IntRange>()
            var start = 0
            for (i in 1..maxOf(chars.size, 1)) {
                if (i < chars.size && chars[i] - chars[i - 1] == step) continue
                if (i - start >= 3) result += start until i
                start = i
            }
            return result
        }

        private fun years(chars: IntArray): List<IntRange> {
            if (chars.size < 4) return emptyList()
            return (0..chars.size - 4).mapNotNull { i ->
                val year = String(chars, i, 4).toIntOrNull()
                if (year != null && year in 1900..2099 && (i until i + 4).all { Character.isDigit(chars[it]) }) i until i + 4 else null
            }
        }
    }
}

/** The most common passwords (from public breach statistics) and words that appear in them. Lowercase. */
internal object CommonPasswords {
    val all: Set<String> = setOf(
        "123456", "password", "123456789", "12345678", "12345", "qwerty", "1234567", "111111", "1234567890", "123123",
        "abc123", "1234", "password1", "iloveyou", "1q2w3e4r", "000000", "qwerty123", "zaq12wsx", "dragon", "sunshine",
        "princess", "letmein", "654321", "monkey", "27653", "1qaz2wsx", "123321", "qwertyuiop", "superman", "asdfghjkl",
        "football", "baseball", "welcome", "admin", "master", "shadow", "michael", "jennifer", "trustno1", "hello",
        "freedom", "whatever", "qazwsx", "ninja", "mustang", "access", "starwars", "passw0rd", "password123", "1q2w3e",
        "121212", "7777777", "555555", "666666", "888888", "987654321", "112233", "123qwe", "qwe123", "a123456",
        "123456a", "aa123456", "p@ssw0rd", "secret", "changeme", "default", "guest", "test", "test123", "root",
        "toor", "administrator", "login", "pass", "pass123", "computer", "internet", "samsung", "google", "apple",
        "batman", "charlie", "donald", "flower", "hottie", "loveme", "lovely", "solo", "summer", "winter",
        "hallo", "hallo123", "passwort", "passwort1", "schatz", "geheim", "ficken", "fussball", "schalke04", "bayern",
        "privet", "parol", "parol123", "qwerty1", "1qaz2wsx3edc", "zxcvbnm", "asdfgh", "q1w2e3r4", "q1w2e3r4t5", "11111111",
        "00000000", "12341234", "123654", "159753", "147258369", "696969", "131313", "999999", "777777", "222222",
        "abcdef", "abcd1234", "iloveyou1", "princess1", "sunshine1", "welcome1", "letmein1", "admin123", "root123", "qwertz",
    )

    /** Words of 5+ letters a guesser tries inside longer passwords (after undoing 0→o, 3→e, @→a, …). */
    private val words: List<String> = listOf(
        "password", "passwort", "welcome", "letmein", "iloveyou", "dragon", "monkey", "sunshine", "princess",
        "football", "baseball", "superman", "batman", "master", "shadow", "secret", "admin", "hello", "hallo",
        "summer", "winter", "spring", "autumn", "freedom", "starwars", "geheim", "schatz", "parol", "privet",
    ).sortedByDescending { it.length }

    val wordCodes: List<IntArray> = words.map { it.codePoints().toArray() }
}
