package dev.keepassbar.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.keepassbar.android.theme.extraColors

/** A shown secret with its digits and symbols coloured, so `0`/`O` and `l`/`1` are told apart (SPEC §6.1). */
@Composable
internal fun colouredSecret(text: String): AnnotatedString {
    val digit = MaterialTheme.extraColors.digit
    val symbol = MaterialTheme.extraColors.symbol
    return colouredSecret(text, digit, symbol)
}

internal fun colouredSecret(text: String, digit: Color, symbol: Color): AnnotatedString = buildAnnotatedString {
    for (char in text) {
        when {
            char.isDigit() -> withStyle(SpanStyle(color = digit)) { append(char) }
            char.isLetter() || char.isWhitespace() -> append(char)
            else -> withStyle(SpanStyle(color = symbol)) { append(char) }
        }
    }
}
