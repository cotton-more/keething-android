package dev.keething.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.keething.android.theme.extraColors
import dev.keething.card.RevealState

/**
 * A shown secret with its digits and symbols coloured, so `0`/`O` and `l`/`1` are told apart (SPEC §6.1). A long
 * one wraps at the line's end, not after a `/` or a `-` as if it were two words (for display only: it holds
 * invisible word joiners).
 */
@Composable
internal fun colouredSecret(text: String): AnnotatedString {
    val digit = MaterialTheme.extraColors.digit
    val symbol = MaterialTheme.extraColors.symbol
    return colouredSecret(text, digit, symbol, joined = true)
}

internal fun colouredSecret(text: String, digit: Color, symbol: Color, joined: Boolean = false): AnnotatedString = buildAnnotatedString {
    for ((i, char) in text.withIndex()) {
        if (joined && i > 0 && !char.isLowSurrogate() && !char.isWhitespace() && !text[i - 1].isWhitespace()) append(WORD_JOINER)
        when {
            char.isDigit() -> withStyle(SpanStyle(color = digit)) { append(char) }
            char.isLetter() || char.isWhitespace() -> append(char)
            else -> withStyle(SpanStyle(color = symbol)) { append(char) }
        }
    }
}

private const val WORD_JOINER = '\u2060'

/**
 * A masked value as a screen reader says it: "Hidden", with only the part in clear (a card's last digits), never the
 * dots (keething-spec app.accessibility AC2).
 */
internal fun Modifier.spokenMasked(text: String): Modifier = semantics { contentDescription = RevealState.spoken(text) }
