package dev.keething.android.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading

/**
 * A short label that keeps to one line: with a large font it gets smaller, down to [smallest], before it ends in
 * "…". For a segment, a chip or a figure beside a control, where a second line would break the layout.
 */
@Composable
internal fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
    smallest: TextUnit = 11.sp,
) {
    val largest = style.fontSize.takeIf { it.isSp } ?: 14.sp
    Text(
        text,
        modifier,
        color = color,
        fontFamily = fontFamily,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = if (smallest.value < largest.value) smallest else largest, maxFontSize = largest, stepSize = 0.5.sp),
    )
}

/**
 * A button's icon and label on one line. On a narrow screen or with a large font the label first gets smaller, down to
 * 12 sp; if it still does not fit beside the icon, the icon goes and the label may shrink to 11 sp; then the [short]
 * label is shown, and only then does it end in "…". Never a second line ("Ask for an / entry").
 */
@Composable
internal fun ButtonLabel(text: String, icon: ImageVector? = null, short: String = text, iconSize: Dp = 24.dp, gap: Dp = 10.dp) {
    val style = LocalTextStyle.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
        val width = constraints.maxWidth
        val besideIcon = if (icon == null) width else width - with(density) { (iconSize + gap).roundToPx() }
        val (label, size, withIcon) = remember(text, short, style, width, besideIcon) {
            fun fits(label: String, size: Float, room: Int) =
                measurer.measure(label, style.copy(fontSize = size.sp), maxLines = 1, softWrap = false).size.width <= room
            fun largest(label: String, room: Int, smallest: Float): Float {
                var size = style.fontSize.value
                while (size > smallest && !fits(label, size, room)) size -= 0.5f
                return size
            }
            val beside = largest(text, besideIcon, 12f)
            val alone = largest(text, width, 11f)
            when {
                icon != null && fits(text, beside, besideIcon) -> Triple(text, beside, true)
                fits(text, alone, width) || short == text -> Triple(text, alone, false)
                else -> Triple(short, largest(short, width, 11f), false)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (withIcon && icon != null) {
                Icon(icon, contentDescription = null, Modifier.size(iconSize))
                Spacer(Modifier.width(gap))
            }
            Text(label, fontSize = size.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A small top bar's title with a line under it (what for, how long): each kept to one line, smaller before cut. */
@Composable
internal fun BarTitle(title: String, subtitle: String?) {
    Column {
        FitText(title, Modifier.semantics { heading() }, smallest = 16.sp)
        if (subtitle != null) {
            FitText(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, smallest = 12.sp)
        }
    }
}

/**
 * A text of parts joined by " · " that wraps with each dot at the end of a line, never at the start of the next
 * ("10:59 AM / · expires in 3 days").
 */
internal fun String.dotsAtLineEnd(): String = replace(" · ", " · ")
