package dev.keething.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.keething.android.theme.MonospaceFamily
import dev.keething.card.TotpDisplay
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

/** A live code: the digits, the ring and the seconds left; red in its last five seconds. */
@Composable
internal fun CodeValue(display: TotpDisplay) {
    val colour = if (display.secondsRemaining <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            display.formatted,
            fontFamily = MonospaceFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            letterSpacing = 1.sp,
            color = colour,
        )
        CodeRing(display.period, colour, MaterialTheme.colorScheme.outlineVariant, size = 22.dp)
        Text(
            "${display.secondsRemaining} s",
            // Read when the reader reaches it, never announced as it counts (keething-spec app.accessibility AC4).
            Modifier.semantics { contentDescription = secondsLeft(display.secondsRemaining) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A code's seconds as a screen reader says them. */
internal fun secondsLeft(seconds: Int): String = if (seconds == 1) "1 second left" else "$seconds seconds left"

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
