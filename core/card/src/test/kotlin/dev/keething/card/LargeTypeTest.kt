package dev.keething.card

import dev.keething.card.LargeTypeCell.Kind.DIGIT
import dev.keething.card.LargeTypeCell.Kind.LETTER
import dev.keething.card.LargeTypeCell.Kind.SPACE
import dev.keething.card.LargeTypeCell.Kind.SYMBOL
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Mac `PresenterTests` expectations for Large Type. */
class LargeTypeTest {
    // spec: card.presenter/AC3
    @Test
    fun eachCharacterIsNumberedAndToldApartAnEmojiWithItsModifiersOne() {
        val cells = LargeTypeCell.of("aÜ1٣ $\n👍🏽")
        assertEquals(listOf("a", "Ü", "1", "٣", "␣", "$", "↵", "👍🏽"), cells.map { it.text }, "an emoji with its skin tone is one cell")
        assertEquals(listOf(LETTER, LETTER, DIGIT, DIGIT, SPACE, SYMBOL, SPACE, SYMBOL), cells.map { it.kind })
        assertEquals((1..8).toList(), cells.map { it.number })
    }

    @Test
    fun aTabAWindowsLineBreakAndJoinedOrAccentedCharactersAreOneCellEach() {
        val cells = LargeTypeCell.of("x\ty\r\nÜ👩‍💻🇩🇪")
        assertEquals(listOf("x", "⇥", "y", "↵", "Ü", "👩‍💻", "🇩🇪"), cells.map { it.text })
        assertEquals(listOf(LETTER, SPACE, LETTER, SPACE, LETTER, SYMBOL, SYMBOL), cells.map { it.kind })
    }

    @Test
    fun anEmptyValueHasNoCells() {
        assertEquals(emptyList(), LargeTypeCell.of(""))
    }
}
