package dev.keepassbar.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * [main] with [trailing] (a chip, a badge) beside it at the end, or under it at the start when beside it would leave
 * [main] less than [minRoom] (a large font, a narrow screen): a title cut to "Office pr…" for a chip is no use.
 */
@Composable
internal fun BesideOrUnder(
    minRoom: Dp,
    modifier: Modifier = Modifier,
    gap: Dp = 12.dp,
    main: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(contents = listOf(main, trailing), modifier) { (mainPart, trailingPart), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val end = trailingPart.first().measure(loose)
        val gapPx = gap.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        if (width - end.width - gapPx >= minRoom.roundToPx()) {
            val start = mainPart.first().measure(loose.copy(maxWidth = width - end.width - gapPx))
            val layoutWidth = if (constraints.hasBoundedWidth) width else start.width + gapPx + end.width
            val height = max(max(start.height, end.height), constraints.minHeight)
            layout(layoutWidth, height) {
                start.place(0, (height - start.height) / 2)
                end.place(layoutWidth - end.width, (height - end.height) / 2)
            }
        } else {
            val start = mainPart.first().measure(loose)
            val under = gapPx / 2
            val layoutWidth = if (constraints.hasBoundedWidth) width else max(start.width, end.width)
            val height = max(start.height + under + end.height, constraints.minHeight)
            layout(layoutWidth, height) {
                start.place(0, 0)
                end.place(0, start.height + under)
            }
        }
    }
}
