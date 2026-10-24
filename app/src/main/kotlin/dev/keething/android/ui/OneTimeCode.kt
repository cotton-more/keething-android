package dev.keething.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import kotlinx.coroutines.delay

/** The time, updated at every full second while the screen is visible: live one-time codes. */
@Composable
internal fun rememberSecondsNow(): State<Instant> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(Instant.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = Instant.now()
                delay(1_000 - System.currentTimeMillis() % 1_000)
            }
        }
    }
}

/**
 * The ring that runs down a code's period, smoothly (SPEC §6.1): redrawn on every frame, without
 * recomposing anything around it.
 */
@Composable
internal fun CodeRing(period: Int, color: Color, track: Color, modifier: Modifier = Modifier, size: Dp = 20.dp, stroke: Dp = 3.dp) {
    val left by produceState(fractionLeft(period), period) {
        while (true) withFrameMillis { value = fractionLeft(period) }
    }
    Canvas(modifier.size(size)) {
        val width = stroke.toPx()
        val inset = width / 2
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - width, this.size.height - width)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width))
        drawArc(color, -90f, 360f * left, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** Fraction of the current period still to run: 1 right after the code changed, towards 0 at its end. */
private fun fractionLeft(period: Int): Float {
    val periodMillis = period.coerceAtLeast(1) * 1_000L
    return 1f - (System.currentTimeMillis() % periodMillis).toFloat() / periodMillis
}
