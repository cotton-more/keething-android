package dev.keething.android.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/**
 * A slider that stops at [steps] values between the ends of [range], as Material's, without a dot drawn on the track for
 * each stop nor at its end (the owner, 2026-11-21: the generator's length had one per character). A screen reader still
 * moves it one stop at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StepSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    val change = rememberUpdatedState(onChange)
    val state = remember(range, steps) { SliderState(value, steps, range) }
    // The value is the caller's: a change from elsewhere (a recipe chosen) moves the thumb.
    SideEffect { if (state.value != value) state.value = value }
    Slider(
        state = state,
        onValueChange = { change.value(it) },
        modifier = modifier,
        track = { SliderDefaults.Track(sliderState = it, drawStopIndicator = null, drawTick = { _, _ -> }) },
    )
}
