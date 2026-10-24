package dev.keething.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A screen's content on a wide window (a tablet, a phone in landscape): rows the eye can follow, centred. */
internal val READABLE_WIDTH = 640.dp

/** At most [READABLE_WIDTH] wide, the whole width below it; its parent centres it. */
internal fun Modifier.readableWidth(): Modifier = widthIn(max = READABLE_WIDTH).fillMaxWidth()

/**
 * The side padding of a screen over the whole window that keeps its content at most [READABLE_WIDTH] wide and
 * centred; [min] where the window is narrower (a phone).
 */
@Composable
internal fun readableInset(min: Dp = 16.dp): Dp = maxOf(min, (LocalConfiguration.current.screenWidthDp.dp - READABLE_WIDTH) / 2)
