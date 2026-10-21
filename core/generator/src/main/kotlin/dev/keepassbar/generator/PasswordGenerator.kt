package dev.keepassbar.generator

import java.security.SecureRandom
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/** Uniformly distributed integers: the system's cryptographic generator in the app, a fixed sequence in tests. */
public fun interface RandomSource {
    /** A uniform integer in `0 until bound` (`bound > 0`). */
    public fun nextBelow(bound: Int): Int
}

/** `SecureRandom`; its `nextInt(bound)` draws again rather than favour small values (no modulo bias). */
public object SystemRandom : RandomSource {
    private val random = SecureRandom()

    override fun nextBelow(bound: Int): Int = random.nextInt(bound)
}

/** What kind of password to make (Mac SPEC §3.20). */
public enum class PasswordKind(internal val stored: String) {
    /** Digits only. */
    PIN("pin"),

    /** Random characters from the chosen classes, at least one of each (the Mac's `random`). */
    STRONG("random"),

    /** Words, each either Capitalised or in CAPITALS, joined by digits and symbols. */
    MEMORABLE("memorable"),

    /** Lowercase words joined by a separator (diceware). */
    PASSPHRASE("passphrase"),
}

public enum class CharacterClass(public val characters: String, internal val stored: String) {
    UPPER("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "upper"),
    LOWER("abcdefghijklmnopqrstuvwxyz", "lower"),
    DIGITS("0123456789", "digits"),
    SYMBOLS("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~", "symbols"),
}

/** How strong a recipe is, by its bits (the Mac's `PasswordStrength` thresholds). */
public enum class Strength {
    WEAK,
    FAIR,
    STRONG,
    ;

    public companion object {
        public fun of(bits: Double): Strength = when {
            bits < 40 -> WEAK
            bits < 60 -> FAIR
            else -> STRONG
        }
    }
}

/**
 * The options of every kind, kept together so switching kinds loses none (remembered as a preference, never a
 * password; Mac `GeneratorOptions`). Out-of-range values are brought into range by [clamped].
 */
public data class GeneratorOptions(
    val kind: PasswordKind = PasswordKind.STRONG,
    val pinLength: Int = 6,
    val strongLength: Int = 20,
    val classes: Set<CharacterClass> = CharacterClass.entries.toSet(),
    /** Leaves out characters that are easy to confuse when read or typed: 0 O o 1 l I |. */
    val excludeLookAlikes: Boolean = false,
    val memorableWords: Int = 4,
    val memorableDigits: Int = 1,
    val memorableSymbols: Int = 1,
    val passphraseWords: Int = 5,
    val separator: String = "-",
) {
    public fun clamped(): GeneratorOptions = copy(
        pinLength = pinLength.coerceIn(PIN_LENGTHS),
        strongLength = strongLength.coerceIn(STRONG_LENGTHS),
        classes = classes.ifEmpty { setOf(CharacterClass.LOWER) },
        memorableWords = memorableWords.coerceIn(MEMORABLE_WORDS),
        memorableDigits = memorableDigits.coerceIn(MEMORABLE_EXTRAS),
        memorableSymbols = memorableSymbols.coerceIn(MEMORABLE_EXTRAS),
        passphraseWords = passphraseWords.coerceIn(PASSPHRASE_WORDS),
        separator = separator.takeIf { it in SEPARATORS } ?: "-",
    )

    /** One `key=value` per line; the separator is quoted, as it may be a space. */
    public fun encode(): String = listOf(
        "kind" to kind.stored,
        "pinLength" to "$pinLength",
        "strongLength" to "$strongLength",
        "classes" to CharacterClass.entries.filter { it in classes }.joinToString(",") { it.stored },
        "excludeLookAlikes" to "$excludeLookAlikes",
        "memorableWords" to "$memorableWords",
        "memorableDigits" to "$memorableDigits",
        "memorableSymbols" to "$memorableSymbols",
        "passphraseWords" to "$passphraseWords",
        "separator" to "\"$separator\"",
    ).joinToString("\n") { (key, value) -> "$key=$value" }

    public companion object {
        public val PIN_LENGTHS: IntRange = 4..16
        public val STRONG_LENGTHS: IntRange = 8..64
        public val MEMORABLE_WORDS: IntRange = 2..8
        public val MEMORABLE_EXTRAS: IntRange = 0..5
        public val PASSPHRASE_WORDS: IntRange = 3..10
        public val SEPARATORS: List<String> = listOf("-", " ", ".", "_")

        /** What [encode] wrote; a missing or unreadable value takes its default (options of an older version still load). */
        public fun decode(text: String?): GeneratorOptions {
            val values = text.orEmpty().lines().mapNotNull { line ->
                line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
            }.toMap()
            val d = GeneratorOptions()
            fun int(key: String, default: Int) = values[key]?.toIntOrNull() ?: default
            return GeneratorOptions(
                kind = PasswordKind.entries.firstOrNull { it.stored == values["kind"] } ?: d.kind,
                pinLength = int("pinLength", d.pinLength),
                strongLength = int("strongLength", d.strongLength),
                classes = values["classes"]?.split(',')?.mapNotNull { name -> CharacterClass.entries.firstOrNull { it.stored == name } }?.toSet() ?: d.classes,
                excludeLookAlikes = values["excludeLookAlikes"]?.toBooleanStrictOrNull() ?: d.excludeLookAlikes,
                memorableWords = int("memorableWords", d.memorableWords),
                memorableDigits = int("memorableDigits", d.memorableDigits),
                memorableSymbols = int("memorableSymbols", d.memorableSymbols),
                passphraseWords = int("passphraseWords", d.passphraseWords),
                separator = values["separator"]?.removeSurrounding("\"") ?: d.separator,
            ).clamped()
        }
    }
}

/**
 * Makes passwords and says how many bits the recipe gives: the number of equally likely passwords it can make,
 * computed exactly, not guessed from the result (Mac `PasswordGenerator`). "At least one of each" is met by
 * drawing again, so every valid password stays equally likely.
 */
public object PasswordGenerator {
    internal const val LOOK_ALIKES: String = "0Oo1lI|"

    /** The memorable kind's symbols: on every keyboard layout, and harmless in URLs, shells and forms. */
    internal const val MEMORABLE_SYMBOLS: String = "!#$%&*+-=?@"
    private const val DIGITS = "0123456789"

    /** The EFF large list without its four hyphenated words (`assets/licenses`). */
    public val words: List<String> by lazy {
        // An absolute path: R8 renames this package in a release build, so a relative one would not be found.
        val stream = checkNotNull(PasswordGenerator::class.java.getResourceAsStream("/dev/keepassbar/generator/eff-large-wordlist.txt")) { "word list missing" }
        stream.bufferedReader().use { reader -> reader.readLines().filter { it.isNotEmpty() } }
    }

    public fun generate(options: GeneratorOptions, random: RandomSource = SystemRandom): String {
        val o = options.clamped()
        return when (o.kind) {
            PasswordKind.PIN -> String(CharArray(o.pinLength) { DIGITS[random.nextBelow(10)] })
            PasswordKind.STRONG -> strong(o, random)
            PasswordKind.MEMORABLE -> {
                val chosen = List(o.memorableWords) {
                    val word = words[random.nextBelow(words.size)]
                    if (random.nextBelow(2) == 0) word.replaceFirstChar(Char::uppercaseChar) else word.uppercase()
                }
                val glue = glue(o, random)
                // Between the words first; what does not fit goes at the end.
                buildString {
                    chosen.forEachIndexed { i, word ->
                        append(word)
                        if (i < glue.length) append(glue[i])
                    }
                    if (glue.length > chosen.size) append(glue, chosen.size, glue.length)
                }
            }
            PasswordKind.PASSPHRASE -> List(o.passphraseWords) { words[random.nextBelow(words.size)] }.joinToString(o.separator)
        }
    }

    public fun entropy(options: GeneratorOptions): Double {
        val o = options.clamped()
        return when (o.kind) {
            PasswordKind.PIN -> o.pinLength * log2(10.0)
            PasswordKind.STRONG -> {
                // Strings of the length over the pool with at least one of each class (inclusion–exclusion).
                val sizes = pools(o).map { it.length }
                var count = 0.0
                for (mask in 0 until (1 shl sizes.size)) {
                    val left = sizes.indices.filter { mask and (1 shl it) == 0 }.sumOf { sizes[it] }
                    val sign = if (Integer.bitCount(mask) % 2 == 0) 1.0 else -1.0
                    count += sign * left.toDouble().pow(o.strongLength)
                }
                log2(count)
            }
            // + 1: the case of each word.
            PasswordKind.MEMORABLE -> o.memorableWords * (log2(words.size.toDouble()) + 1) + log2(glueCount(o))
            PasswordKind.PASSPHRASE -> o.passphraseWords * log2(words.size.toDouble())
        }
    }

    private fun strong(o: GeneratorOptions, random: RandomSource): String {
        val pools = pools(o)
        val all = pools.joinToString("")
        while (true) {
            val candidate = String(CharArray(o.strongLength) { all[random.nextBelow(all.length)] })
            if (pools.all { pool -> candidate.any { it in pool } }) return candidate
        }
    }

    private fun pools(o: GeneratorOptions): List<String> =
        CharacterClass.entries.filter { it in o.classes }.map { cls ->
            if (o.excludeLookAlikes) cls.characters.filterNot { it in LOOK_ALIKES } else cls.characters
        }

    /** One digit or symbol after every word but the last, or more when the minimums need more. */
    private fun glueLength(o: GeneratorOptions): Int = max(o.memorableWords - 1, o.memorableDigits + o.memorableSymbols)

    private fun glue(o: GeneratorOptions, random: RandomSource): String {
        val alphabet = DIGITS + MEMORABLE_SYMBOLS
        while (true) {
            val candidate = String(CharArray(glueLength(o)) { alphabet[random.nextBelow(alphabet.length)] })
            val digits = candidate.count { it in DIGITS }
            if (digits >= o.memorableDigits && candidate.length - digits >= o.memorableSymbols) return candidate
        }
    }

    /** Sequences of the glue's length with at least the minimum of digits and of symbols. */
    private fun glueCount(o: GeneratorOptions): Double {
        val k = glueLength(o)
        if (o.memorableDigits > k - o.memorableSymbols) return 1.0
        return (o.memorableDigits..(k - o.memorableSymbols)).sumOf { d ->
            binomial(k, d) * 10.0.pow(d) * MEMORABLE_SYMBOLS.length.toDouble().pow(k - d)
        }
    }

    private fun binomial(n: Int, k: Int): Double = (0 until k).fold(1.0) { acc, i -> acc * (n - i) / (i + 1) }
}
