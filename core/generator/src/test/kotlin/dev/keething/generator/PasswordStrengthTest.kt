package dev.keething.generator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Mac's `PasswordHealthTests` strength cases (Mac SPEC §3.14). */
class PasswordStrengthTest {
    private fun strength(password: String, context: List<String> = emptyList()) = PasswordStrength.estimate(password, context)

    @Test
    fun commonPasswordsShortOnesAndPatternsAreWeakWithTheReasons() {
        assertEquals(listOf("one of the most common passwords"), strength("password").reasons)
        assertEquals(Strength.WEAK, strength("12345678").verdict)
        val short = strength("aB3\$x")
        assertEquals(Strength.WEAK, short.verdict)
        assertEquals("shorter than 8 characters", short.reasons.first(), "never the exact length")
        val p1 = strength("P@ssword2024!")
        assertEquals(Strength.WEAK, p1.verdict)
        assertTrue("a common word" in p1.reasons && "a year" in p1.reasons, "${p1.reasons}")
        assertTrue("a keyboard pattern" in strength("qwerty123456").reasons)
        assertTrue("a keyboard pattern" in strength("ytrewqasdf99").reasons, "rows count backwards too")
        assertTrue("repeated characters" in strength("aaaaaaaaaaaa").reasons)
        val sequence = strength("abcdefgh1").reasons
        assertTrue("a sequence" in sequence || "a keyboard pattern" in sequence)
        val digits = strength("84736251")
        assertEquals(Strength.WEAK, digits.verdict)
        assertTrue("digits only" in digits.reasons)
    }

    @Test
    fun theContextsWordsCountAsOneCharacter() {
        val s = strength("janedoe-example", listOf("jane.doe@example.com", "Example Shop"))
        assertTrue("contains the username or the site's name" in s.reasons, "${s.reasons}")
        assertTrue(s.verdict < Strength.STRONG)
        assertNotEquals(Strength.WEAK, strength("janedoe-example").verdict, "without context it is just letters")
    }

    @Test
    fun generatedAndLongPassphrasesAreStrongAndTheEstimateGrowsWithLength() {
        assertEquals(Strength.STRONG, strength("xK9#mP2\$vL4@qR7!").verdict)
        assertEquals(Strength.STRONG, strength("korrekt pferd batterie heftklammer").verdict)
        assertEquals(Strength.FAIR, strength("hjgkfltmvn").verdict, "10 random lowercase letters")
        assertTrue(strength("hjgkfltmvnqp").bits > strength("hjgkfltmvn").bits)
        assertEquals(emptyList(), strength("xK9#mP2\$vL4@qR7!").reasons)
    }

    @Test
    fun aCyrillicKeyboardRowAndLettersOutsideAsciiCount() {
        assertTrue("a keyboard pattern" in strength("йцукен2026").reasons)
        assertTrue(strength("жёлтыйкот").bits > strength("zholtyjkot").bits, "a bigger pool outside ASCII")
    }
}
