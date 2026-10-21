package dev.keepassbar.generator

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

    @Test
    fun aMemorablePasswordIsWordsInCapitalsOrCapitalLettersWithTheDigitsAndSymbols() {
        val random = SeededRandom(3)
        for ((count, digits, symbols) in listOf(Triple(4, 1, 1), Triple(2, 2, 3), Triple(3, 0, 0), Triple(8, 5, 5))) {
            repeat(50) {
                val options = GeneratorOptions(kind = PasswordKind.MEMORABLE, memorableWords = count, memorableDigits = digits, memorableSymbols = symbols)
                val password = PasswordGenerator.generate(options, random)
                val found = password.split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }
                assertEquals(count, found.size, password)
                for (word in found) {
                    assertTrue(word.lowercase() in words, "$word is a list word")
                    assertTrue(word == word.uppercase() || word == word.lowercase().replaceFirstChar(Char::uppercase), "$word: one case")
                }
                val glue = password.filterNot { it.isLetter() }
                assertEquals(maxOf(count - 1, digits + symbols), glue.length, password)
                assertTrue(glue.count { it.isDigit() } >= digits && glue.count { !it.isDigit() } >= symbols, password)
                assertTrue(glue.all { it.isDigit() || it in PasswordGenerator.MEMORABLE_SYMBOLS }, password)
                assertTrue(password.first().isLetter(), "starts with a word: $password")
            }
        }
        // 4 words with their case, and 3 glue characters holding at least one digit and one symbol (21³ − 10³ − 11³ = 6930).
        assertEquals(4 * (log2(7772.0) + 1) + log2(6930.0), PasswordGenerator.entropy(GeneratorOptions(kind = PasswordKind.MEMORABLE)), 1e-9)
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
    fun theDefaultsAreAsStrongAsOnTheMac() {
        fun bits(kind: PasswordKind) = PasswordGenerator.entropy(GeneratorOptions(kind = kind)).roundToInt()
        assertEquals(20, bits(PasswordKind.PIN))
        assertEquals(131, bits(PasswordKind.STRONG))
        assertEquals(68, bits(PasswordKind.MEMORABLE))
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
}
