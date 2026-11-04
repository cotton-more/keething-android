package dev.keething.generator

import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A fixed, repeatable sequence (SplitMix64, as in the Mac tests), so the tests do not depend on luck. */
private class SeededRandom(private var state: Long) : RandomSource {
    override fun nextBelow(bound: Int): Int {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return java.lang.Long.remainderUnsigned(z xor (z ushr 31), bound.toLong()).toInt()
    }
}

/** The Mac `GeneratorTests` expectations (Mac SPEC §3.20). */
class PasswordGeneratorTest {
    private val words = PasswordGenerator.words.toSet()

    @Test
    fun theEffListHas7772DistinctWordsOfLowercaseLetters() {
        assertEquals(7772, PasswordGenerator.words.size)
        assertEquals(7772, words.size)
        assertTrue(PasswordGenerator.words.all { it.length >= 3 && it.all { c -> c in 'a'..'z' } })
        assertEquals("abacus", PasswordGenerator.words.first())
        assertEquals("zoom", PasswordGenerator.words.last())
    }

    @Test
    fun theSystemSourceStaysBelowTheBoundAndReachesEveryValue() {
        val counts = IntArray(7)
        repeat(7000) { counts[SystemRandom.nextBelow(7)]++ }
        assertTrue(counts.all { it in 801..1199 }, "roughly uniform: ${counts.toList()}")
        assertEquals(0, SystemRandom.nextBelow(1))
    }

    @Test
    fun aPinIsDigitsOfTheLength() {
        val random = SeededRandom(1)
        for (length in listOf(4, 6, 16)) {
            val pin = PasswordGenerator.generate(GeneratorOptions(kind = PasswordKind.PIN, pinLength = length), random)
            assertEquals(length, pin.length)
            assertTrue(pin.all { it in '0'..'9' })
        }
        assertEquals(6 * log2(10.0), PasswordGenerator.entropy(GeneratorOptions(kind = PasswordKind.PIN, pinLength = 6)))
    }

    @Test
    fun aStrongPasswordHasEveryChosenClassNothingElseAndNoLookAlikesWhenAsked() {
        val random = SeededRandom(2)
        repeat(200) {
            val password = PasswordGenerator.generate(GeneratorOptions(kind = PasswordKind.STRONG, strongLength = 8), random)
            assertEquals(8, password.length)
            for (cls in CharacterClass.entries) assertTrue(password.any { it in cls.characters }, "$cls in $password")
        }
        val noSymbols = GeneratorOptions(
            kind = PasswordKind.STRONG, strongLength = 64, classes = setOf(CharacterClass.LOWER, CharacterClass.DIGITS), excludeLookAlikes = true,
        )
        repeat(50) {
            val password = PasswordGenerator.generate(noSymbols, random)
            assertTrue(password.all { it in 'a'..'z' || it in '0'..'9' }, password)
            assertTrue(password.none { it in PasswordGenerator.LOOK_ALIKES }, password)
        }
        // At least one of each class: a little less than length × log2(pool).
        val bits = PasswordGenerator.entropy(GeneratorOptions(kind = PasswordKind.STRONG, strongLength = 20))
        assertTrue(bits < 20 * log2(94.0) && bits > 20 * log2(94.0) - 1, "$bits")
        assertEquals(
            10 * log2(10.0),
            PasswordGenerator.entropy(GeneratorOptions(kind = PasswordKind.STRONG, strongLength = 10, classes = setOf(CharacterClass.DIGITS))),
            1e-9,
        )
    }

    /** A memorable password's words and the runs of digits and symbols between them: "7", "Lemon", "GRAPE", "!3". */
    private fun parts(password: String): List<String> = Regex("[A-Za-z]+|[^A-Za-z]+").findAll(password).map { it.value }.toList()

    /** The words of a run of letters: a capital starts each, a word in CAPITALS ends where a Capitalised one begins. */
    private fun words(letters: String): List<String> = Regex("[A-Z][a-z]+|[A-Z]+(?![a-z])").findAll(letters).map { it.value }.toList()

    @Test
    fun aMemorablePasswordIsWordsInCapitalsOrCapitalLettersWithExactlyTheDigitsAndSymbols() {
        val random = SeededRandom(3)
        for ((count, digits, symbols) in listOf(Triple(4, 1, 1), Triple(2, 2, 3), Triple(3, 0, 0), Triple(3, 1, 0), Triple(8, 5, 5), Triple(8, 0, 1))) {
            repeat(100) {
                val options = GeneratorOptions(kind = PasswordKind.MEMORABLE, memorableWords = count, memorableDigits = digits, memorableSymbols = symbols)
                val password = PasswordGenerator.generate(options, random)
                val glue = password.filterNot { it.isLetter() }
                assertEquals(digits, glue.count { it.isDigit() }, password)
                assertEquals(symbols, glue.count { it in PasswordGenerator.MEMORABLE_SYMBOLS }, password)
                assertEquals(digits + symbols, glue.length, password)
                val found = parts(password).filter { it.first().isLetter() }.flatMap(::words)
                assertEquals(count, found.size, password)
                for ((i, word) in found.withIndex()) {
                    assertTrue(word.lowercase() in words, "$word is a list word: $password")
                    assertTrue(word == word.uppercase() || word == word.lowercase().replaceFirstChar(Char::uppercase), "$word: one case")
                    if (i > 0) assertTrue(!(word == word.uppercase() && found[i - 1] == found[i - 1].uppercase() && "${found[i - 1]}$word" in password), "two in CAPITALS touch: $password")
                }
            }
        }
        // No digits and no symbols: the words alone, told apart by their case.
        val bare = PasswordGenerator.generate(GeneratorOptions(kind = PasswordKind.MEMORABLE, memorableWords = 5, memorableDigits = 0, memorableSymbols = 0), random)
        assertTrue(bare.all { it.isLetter() }, bare)
        assertEquals(5, words(bare).size, bare)
    }

    /** Every way of spreading the characters over the gaps with every case, counted one by one. */
    private fun counted(words: Int, glue: Int): Int {
        fun spreads(left: Int, slots: Int): List<List<Int>> =
            if (slots == 1) listOf(listOf(left)) else (0..left).flatMap { here -> spreads(left - here, slots - 1).map { listOf(here) + it } }
        return spreads(glue, words + 1).sumOf { gaps ->
            (0 until (1 shl words)).count { capitals ->
                (1 until words).none { gaps[it] == 0 && capitals and (1 shl it) != 0 && capitals and (1 shl (it - 1)) != 0 }
            }
        }
    }

    @Test
    fun aMemorablePasswordsStrengthCountsEveryWayTheCharactersAndCasesCanGo() {
        for (words in GeneratorOptions.MEMORABLE_WORDS) {
            for (glue in 0..10) assertEquals(counted(words, glue).toDouble(), PasswordGenerator.arrangements(words, glue), "$words words, $glue characters")
        }
        // The defaults: 4 words, 1 digit and 1 symbol in either order (2 × 10 × 11), spread in 147 ways with the cases.
        assertEquals(147.0, PasswordGenerator.arrangements(4, 2))
        assertEquals(4 * log2(7772.0) + log2(220.0) + log2(147.0), PasswordGenerator.entropy(GeneratorOptions(kind = PasswordKind.MEMORABLE)), 1e-9)
        assertEquals(3 * log2(7772.0) + log2(5.0), PasswordGenerator.entropy(GeneratorOptions(kind = PasswordKind.MEMORABLE, memorableWords = 3, memorableDigits = 0, memorableSymbols = 0)), 1e-9)
    }

    @Test
    fun everyWayAMemorablePasswordCanGoIsEquallyLikely() {
        // 2 words and 1 digit: before, between or after them, with the cases; between, all 4 cases, else 3 (not both
        // in CAPITALS): 10 ways, each about a tenth of the draws.
        val random = SeededRandom(5)
        val options = GeneratorOptions(kind = PasswordKind.MEMORABLE, memorableWords = 2, memorableDigits = 1, memorableSymbols = 0)
        val seen = mutableMapOf<String, Int>()
        repeat(20_000) {
            val password = PasswordGenerator.generate(options, random)
            val shape = parts(password).joinToString(" ") { part ->
                if (!part.first().isLetter()) "#" else words(part).joinToString(" ") { if (it == it.uppercase()) "W" else "w" }
            }
            seen.merge(shape, 1, Int::plus)
        }
        assertEquals(setOf("# w w", "# w W", "# W w", "w # w", "w # W", "W # w", "W # W", "w w #", "w W #", "W w #"), seen.keys)
        assertTrue(seen.values.all { it in 1800..2200 }, "$seen")
    }

    @Test
    fun aPassphraseIsLowercaseListWordsJoinedByTheSeparator() {
        val options = GeneratorOptions(kind = PasswordKind.PASSPHRASE, passphraseWords = 6, separator = ".")
        val parts = PasswordGenerator.generate(options, SeededRandom(4)).split(".")
        assertEquals(6, parts.size)
        assertTrue(parts.all { it in words })
        assertEquals(6 * log2(7772.0), PasswordGenerator.entropy(options), 1e-9)
    }

    @Test
    fun theDefaultsHaveTheirStrength() {
        fun bits(kind: PasswordKind) = PasswordGenerator.entropy(GeneratorOptions(kind = kind)).roundToInt()
        assertEquals(20, bits(PasswordKind.PIN))
        assertEquals(131, bits(PasswordKind.STRONG))
        assertEquals(67, bits(PasswordKind.MEMORABLE), "the digit and symbol go anywhere now, not one after every word")
        assertEquals(65, bits(PasswordKind.PASSPHRASE))
        assertEquals(Strength.WEAK, Strength.of(39.9))
        assertEquals(Strength.FAIR, Strength.of(40.0))
        assertEquals(Strength.STRONG, Strength.of(60.0))
    }

    @Test
    fun optionsAreBroughtIntoRangeAndMissingOrUnknownStoredValuesTakeTheDefaults() {
        val wild = GeneratorOptions(pinLength = 1, strongLength = 1000, classes = emptySet(), memorableDigits = -3, separator = "x").clamped()
        assertEquals(4, wild.pinLength)
        assertEquals(64, wild.strongLength)
        assertEquals(setOf(CharacterClass.LOWER), wild.classes)
        assertEquals(0, wild.memorableDigits)
        assertEquals("-", wild.separator)

        val stored = GeneratorOptions(kind = PasswordKind.PASSPHRASE, passphraseWords = 7, separator = " ", classes = setOf(CharacterClass.UPPER, CharacterClass.DIGITS))
        assertEquals(stored, GeneratorOptions.decode(stored.encode()), "a space separator and a set of classes survive")
        val old = GeneratorOptions.decode("kind=pin\npinLength=8\nfuture=1")
        assertEquals(PasswordKind.PIN, old.kind)
        assertEquals(8, old.pinLength)
        assertEquals(20, old.strongLength)
        assertEquals(GeneratorOptions(), GeneratorOptions.decode("kind=emoji\npinLength=lots"))
        assertEquals(GeneratorOptions(), GeneratorOptions.decode(null))
    }

    @Test
    fun theMasterPassphraseIsListWordsOneRaisedAndADigitAndASymbolAfterWords() {
        for (seed in 1..300) {
            val count = PasswordGenerator.MASTER_PASSPHRASE_WORDS.first + seed % 8
            val password = PasswordGenerator.masterPassphrase(count, SeededRandom(seed.toLong()))
            val parts = password.split("-")
            assertEquals(count, parts.size, password)
            assertEquals(1, password.count { it.isDigit() }, password)
            assertEquals(1, password.count { it in PasswordGenerator.MASTER_PASSPHRASE_SYMBOLS }, password)
            assertTrue(parts.all { it.first().isLetter() && it.dropWhile(Char::isLetter).none(Char::isLetter) }, "after a word: $password")
            val bare = parts.map { part -> part.filter(Char::isLetter) }
            assertTrue(bare.all { it.lowercase() in words }, password)
            val raised = bare.filter { it != it.lowercase() }
            assertEquals(1, raised.size, password)
            assertTrue(raised.all { it == it.uppercase() || it == it.lowercase().replaceFirstChar(Char::uppercaseChar) }, password)
        }
        assertEquals(10, PasswordGenerator.masterPassphrase(99, SeededRandom(1)).split("-").size, "brought into range")
        // The words, which one is raised and how, the digit and its word, the symbol and its word.
        assertEquals(6 * log2(7772.0) + log2(12.0) + log2(60.0) + log2(60.0), PasswordGenerator.masterPassphraseEntropy(6))
        assertEquals(65, PasswordGenerator.masterPassphraseEntropy(4).toInt(), "about 65 bits for four (databases.new-database AC4)")
    }
}
