package dev.keething.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Codes view's icon, "the ring" (icon A, keething-spec app.codes-view): a code's countdown ring partly run down,
 * three dots inside. Drawn a little heavier than the launcher shortcut's, for 24 dp. Tinted as any icon; the ring's
 * track keeps a quarter of the tint.
 */
internal val CodesIcon: ImageVector by lazy {
    val tint = SolidColor(Color.Black)
    ImageVector.Builder("Codes", 24.dp, 24.dp, 48f, 48f)
        .addPath(addPathNodes("M24,9 A15,15 0 1,1 24,39 A15,15 0 1,1 24,9 Z"), stroke = tint, strokeAlpha = 0.3f, strokeLineWidth = 4f)
        .addPath(addPathNodes("M24,9 A15,15 0 1,1 9,24"), stroke = tint, strokeLineWidth = 4f, strokeLineCap = StrokeCap.Round)
        .addPath(addPathNodes(DOTS), fill = tint)
        .build()
}

private const val DOTS = "M17.5,21.4 a2.6,2.6 0 1,0 0.01,0 Z M24,21.4 a2.6,2.6 0 1,0 0.01,0 Z M30.5,21.4 a2.6,2.6 0 1,0 0.01,0 Z"
