package dev.keepassbar.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The gap between the rows of a segmented group. */
internal val SegmentGap = 2.dp

/**
 * The shape of row [index] of [count] in an Expressive segmented group: large outer corners, small inner
 * ones, so the rows read as one group with small gaps.
 */
internal fun segmentShape(index: Int, count: Int, outer: Dp = 20.dp, inner: Dp = 4.dp): RoundedCornerShape = when {
    count == 1 -> RoundedCornerShape(outer)
    index == 0 -> RoundedCornerShape(outer, outer, inner, inner)
    index == count - 1 -> RoundedCornerShape(inner, inner, outer, outer)
    else -> RoundedCornerShape(inner)
}
