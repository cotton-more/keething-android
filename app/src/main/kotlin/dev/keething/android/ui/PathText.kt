package dev.keething.android.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import dev.keething.card.GroupPathFormatter

/**
 * A group's path on one line (the owner, 2026-10-31): the first of [GroupPathFormatter.shortenings] that fits, so the
 * group's own name stays whole and the levels above it are cut to their first letter from the left, then to "…".
 * TalkBack reads the path whole. [lead] goes before it on the line (a database's label), [prefixStyle] is the levels
 * before the last, [last] the last level as shown (a part of it bold); null: plain.
 */
@Composable
internal fun PathText(
    path: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    lead: AnnotatedString? = null,
    prefixStyle: SpanStyle? = null,
    last: AnnotatedString? = null,
) {
    if (path.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val shortenings = remember(path) { GroupPathFormatter.shortenings(path) }
    val spoken = (lead?.text.orEmpty()) + GroupPathFormatter.text(path)
    BoxWithConstraints(modifier) {
        val texts = shortenings.map { line ->
            buildAnnotatedString {
                lead?.let(::append)
                val before = line.removeSuffix(path.last())
                if (prefixStyle != null) withStyle(prefixStyle) { append(before) } else append(before)
                append(last ?: AnnotatedString(path.last()))
            }
        }
        val width = Constraints(maxWidth = constraints.maxWidth)
        val fitting = texts.firstOrNull { !measurer.measure(it, style, maxLines = 1, constraints = width).hasVisualOverflow } ?: texts.last()
        Text(
            fitting,
            Modifier.semantics { contentDescription = spoken },
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
