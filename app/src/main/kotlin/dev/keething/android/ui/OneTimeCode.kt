package dev.keething.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

/**
 * A live code (keething-spec card.code-timer; the canvas "Code timer", variant D): the digits on a fill that drains over
 * the period and dim in its last five seconds; under them "Next in 12 s" and the next code, which takes the code's colour
 * then. Always two lines (the owner, 2026-11-14): one line fit beside some codes and not others on the same card.
 * [onCopyNext]: a tap on the next code copies it; null where nothing is copied (the editor).
 */
@Composable
internal fun CodeValue(display: TotpDisplay, onCopyNext: (() -> Unit)? = null, fontSize: TextUnit = 26.sp) {
    val ending = display.secondsRemaining <= 5
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            display.formatted,
            Modifier.codeFill(display.period, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 2.dp),
            fontFamily = MonospaceFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (ending) DIMMED else 1f),
        )
        NextCode(display, ending, onCopyNext)
    }
}

/** "Next in 12 s 731 004"; its seconds read when the reader reaches them, never announced as they count (app.accessibility AC4). */
@Composable
private fun NextCode(display: TotpDisplay, ending: Boolean, onCopyNext: (() -> Unit)?) {
    val colour = if (ending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val said = "Next code in ${secondsIn(display.secondsRemaining)}: ${display.nextFormatted}"
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (onCopyNext != null) Modifier.clickable(onClickLabel = "Copy next code", onClick = onCopyNext) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // As wide at 9 s as at 29 s (figure spaces are a digit wide): the next code does not shift as the seconds count.
        val pad = " ".repeat(display.period.toString().length - display.secondsRemaining.toString().length)
        Text("Next in ${display.secondsRemaining} s$pad",style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = colour)
        Text(display.nextFormatted, fontFamily = MonospaceFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.5.sp, color = colour)
    }
}

/** A code's seconds as a screen reader says them. */
internal fun secondsLeft(seconds: Int): String = "${secondsIn(seconds)} left"

private fun secondsIn(seconds: Int): String = if (seconds == 1) "1 second" else "$seconds seconds"

/** How much a code's digits dim in its last five seconds. */
internal const val DIMMED = 0.5f

/**
 * The fill behind a code (keething-spec card.code-timer): drains from right to left over its [period] and fills again in
 * [REFILL_MILLIS] when the next code starts, over a faint track. Both are [color] (the code's) see-through, so they stand
 * out from whatever lies under them: a container colour would be close to the row's in a muted palette (a grey
 * wallpaper's, the owner's phone 2026-11-14). Redrawn on every frame without recomposing anything around it (SPEC §6.1).
 */
@Composable
internal fun Modifier.codeFill(period: Int, color: Color, shape: Shape): Modifier {
    val left by produceState(fillFraction(period), period) {
        while (true) withFrameMillis { value = fillFraction(period) }
    }
    return clip(shape).drawBehind {
        drawRect(color.copy(alpha = TRACK_ALPHA))
        drawRect(color.copy(alpha = FILL_ALPHA), size = size.copy(width = size.width * left))
    }
}

private const val TRACK_ALPHA = 0.06f
private const val FILL_ALPHA = 0.2f
private const val REFILL_MILLIS = 400L

/** The fill's width: refilling over the period's first [REFILL_MILLIS], then the fraction still to run. */
private fun fillFraction(period: Int): Float {
    val periodMillis = period.coerceAtLeast(1) * 1_000L
    val into = System.currentTimeMillis() % periodMillis
    return if (into < REFILL_MILLIS) into.toFloat() / REFILL_MILLIS else 1f - into.toFloat() / periodMillis
}
