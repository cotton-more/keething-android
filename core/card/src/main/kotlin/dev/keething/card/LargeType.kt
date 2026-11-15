package dev.keething.card

import java.text.BreakIterator

/**
 * One character of Large Type (the Mac's `LargeTypeCell`): big, monospaced, numbered from 1, coloured by what it is so
 * that `0` and `O`, `l` and `1` are told apart. A character is what the reader sees as one: an emoji with its
 * modifiers, a letter with its accents, `\r\n`.
 */
public data class LargeTypeCell(val text: String, val number: Int, val kind: Kind) {
    public enum class Kind { LETTER, DIGIT, SYMBOL, SPACE }

    public companion object {
        /** The cells of [value]: a space drawn as `␣`, a line break `↵`, a tab `⇥`. */
        public fun of(value: String): List<LargeTypeCell> {
            val characters = BreakIterator.getCharacterInstance()
            characters.setText(value)
            val cells = mutableListOf<LargeTypeCell>()
            var start = characters.first()
            var end = characters.next()
            while (end != BreakIterator.DONE) {
                cells += cell(value.substring(start, end), cells.size + 1)
                start = end
                end = characters.next()
            }
            return cells
        }

        private fun cell(character: String, number: Int): LargeTypeCell {
            val first = character.codePointAt(0)
            return when {
                character.all { it in NEWLINES } -> LargeTypeCell("↵", number, Kind.SPACE)
                character == "\t" -> LargeTypeCell("⇥", number, Kind.SPACE)
                Character.isWhitespace(first) || Character.isSpaceChar(first) -> LargeTypeCell("␣", number, Kind.SPACE)
                Character.isDigit(first) -> LargeTypeCell(character, number, Kind.DIGIT)
                Character.isLetter(first) -> LargeTypeCell(character, number, Kind.LETTER)
                else -> LargeTypeCell(character, number, Kind.SYMBOL)
            }
        }

        /** Swift's `Character.isNewline`. */
        private const val NEWLINES = "\n\r\u000B\u000C\u0085  "
    }
}
