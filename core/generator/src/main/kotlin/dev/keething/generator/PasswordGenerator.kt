package dev.keething.generator

import java.security.SecureRandom
import kotlin.math.log2
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

    /**
     * Words, each either Capitalised or in CAPITALS, with exactly the chosen digits and symbols before, between and
     * after them; two words in CAPITALS never touch.
     */
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
    internal const val LOOK_ALIKES: String = "0Oo1lI|" // not user text

    /** The memorable kind's symbols: on every keyboard layout, and harmless in URLs, shells and forms. */
    internal const val MEMORABLE_SYMBOLS: String = "!#$%&*+-=?@"
    private const val DIGITS = "0123456789"

    /** The EFF large list without its four hyphenated words (`assets/licenses`). */
    public val words: List<String> by lazy {
        // An absolute path: R8 renames this package in a release build, so a relative one would not be found.
        val stream = checkNotNull(PasswordGenerator::class.java.getResourceAsStream("/dev/keething/generator/eff-large-wordlist.txt")) { "word list missing" }
        stream.bufferedReader().use { reader -> reader.readLines().filter { it.isNotEmpty() } }
    }

    public fun generate(options: GeneratorOptions, random: RandomSource = SystemRandom): String {
        val o = options.clamped()
        return when (o.kind) {
            PasswordKind.PIN -> String(CharArray(o.pinLength) { DIGITS[random.nextBelow(10)] })
            PasswordKind.STRONG -> strong(o, random)
            PasswordKind.MEMORABLE -> memorable(o, random)
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
            // The words, the digits and symbols in their order, and where they go with each word's case.
            PasswordKind.MEMORABLE -> {
                val glue = binomial(o.memorableDigits + o.memorableSymbols, o.memorableDigits) *
                    10.0.pow(o.memorableDigits) * MEMORABLE_SYMBOLS.length.toDouble().pow(o.memorableSymbols)
                o.memorableWords * log2(words.size.toDouble()) + log2(glue) + log2(arrangements(o.memorableWords, o.memorableDigits + o.memorableSymbols))
            }
            PasswordKind.PASSPHRASE -> o.passphraseWords * log2(words.size.toDouble())
        }
    }

    /** New database's word counts (keething-spec databases.new-database AC4). */
    public val MASTER_PASSPHRASE_WORDS: IntRange = 3..10

    /** The master passphrase's symbols: the memorable kind's without `-`, which joins the words. */
    internal val MASTER_PASSPHRASE_SYMBOLS: String = MEMORABLE_SYMBOLS.filter { it != '-' }

    /**
     * New database's Suggest a passphrase (the Mac's `masterPassphrase`): lowercase EFF words joined by `-`, one of them
     * Capitalised or in CAPITALS, one digit after a word and one symbol after a word (the same one or another; a digit
     * first).
     */
    public fun masterPassphrase(words: Int, random: RandomSource = SystemRandom): String {
        val n = words.coerceIn(MASTER_PASSPHRASE_WORDS)
        val chosen = MutableList(n) { this.words[random.nextBelow(this.words.size)] }
        val raised = random.nextBelow(n)
        chosen[raised] = if (random.nextBelow(2) == 0) chosen[raised].replaceFirstChar(Char::uppercaseChar) else chosen[raised].uppercase()
        val digitAfter = random.nextBelow(n)
        chosen[digitAfter] += DIGITS[random.nextBelow(DIGITS.length)]
        val symbolAfter = random.nextBelow(n)
        chosen[symbolAfter] += MASTER_PASSPHRASE_SYMBOLS[random.nextBelow(MASTER_PASSPHRASE_SYMBOLS.length)]
        return chosen.joinToString("-")
    }

    /** [masterPassphrase]'s bits: the words, which one is raised and how, where the digit and the symbol go and which. */
    public fun masterPassphraseEntropy(words: Int): Double {
        val n = words.coerceIn(MASTER_PASSPHRASE_WORDS).toDouble()
        return n * log2(this.words.size.toDouble()) + log2(2 * n) + log2(n * DIGITS.length) + log2(n * MASTER_PASSPHRASE_SYMBOLS.length)
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

    /**
     * The digits and symbols in a uniform order, spread over the gaps before, between and after the words (a gap may
     * hold several or none) and each word's case, both drawn again together while two words in CAPITALS touch, so
     * every password the recipe allows is equally likely.
     */
    private fun memorable(o: GeneratorOptions, random: RandomSource): String {
        val chosen = List(o.memorableWords) { words[random.nextBelow(words.size)] }
        val glue = shuffled(List(o.memorableDigits) { DIGITS[random.nextBelow(DIGITS.length)] } +
            List(o.memorableSymbols) { MEMORABLE_SYMBOLS[random.nextBelow(MEMORABLE_SYMBOLS.length)] }, random)
        while (true) {
            val gaps = gaps(glue.size, chosen.size + 1, random)
            val capitals = BooleanArray(chosen.size) { random.nextBelow(2) == 1 }
            if ((1 until chosen.size).any { gaps[it] == 0 && capitals[it - 1] && capitals[it] }) continue
            var used = 0
            return buildString {
                for (i in 0..chosen.size) {
                    repeat(gaps[i]) { append(glue[used++]) }
                    if (i < chosen.size) append(if (capitals[i]) chosen[i].uppercase() else chosen[i].replaceFirstChar(Char::uppercaseChar))
                }
            }
        }
    }

    /** A uniform order of [items] (Fisher–Yates). */
    private fun <T> shuffled(items: List<T>, random: RandomSource): List<T> {
        val result = items.toMutableList()
        for (i in result.lastIndex downTo 1) {
            val j = random.nextBelow(i + 1)
            result[i] = result[j].also { result[j] = result[i] }
        }
        return result
    }

    /**
     * [count] characters spread over [slots] gaps, every way equally likely: the gaps are the runs between [slots] − 1
     * bars placed at a uniform choice of the count + slots − 1 places (stars and bars).
     */
    private fun gaps(count: Int, slots: Int, random: RandomSource): IntArray {
        val places = shuffled(List(count + slots - 1) { it < slots - 1 }, random)
        val gaps = IntArray(slots)
        var gap = 0
        for (bar in places) if (bar) gap++ else gaps[gap]++
        return gaps
    }

    /**
     * How many ways [glue] characters can be spread over the gaps of [words] words with each word's case, no two words
     * in CAPITALS touching. Summed over which gaps between words hold something: with m of them, the characters go
     * into those m (at least one each) and the two ends in C(glue + 1, m + 1) ways; the words between the full gaps
     * form runs, and a run of n words has F(n + 2) cases without two CAPITALS side by side (F the Fibonacci numbers).
     */
    internal fun arrangements(words: Int, glue: Int): Double {
        val inner = words - 1
        var total = 0.0
        for (full in 0 until (1 shl inner)) {
            val m = Integer.bitCount(full)
            if (m > glue) continue
            var cases = 1.0
            var run = 1
            for (gap in 0 until inner) {
                if (full and (1 shl gap) != 0) {
                    cases *= fibonacci(run + 2)
                    run = 1
                } else {
                    run++
                }
            }
            total += binomial(glue + 1, m + 1) * cases * fibonacci(run + 2)
        }
        return total
    }

    private fun fibonacci(n: Int): Double {
        var a = 0.0
        var b = 1.0
        repeat(n) { b = (a + b).also { a = b } }
        return a
    }

    private fun binomial(n: Int, k: Int): Double = (0 until k).fold(1.0) { acc, i -> acc * (n - i) / (i + 1) }
}
