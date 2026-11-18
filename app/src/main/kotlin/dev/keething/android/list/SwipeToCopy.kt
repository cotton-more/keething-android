package dev.keething.android.list

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.keething.android.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A row swiped right copies the password, swiped left the username (SPEC §6.2). Past the threshold what is under
 * the row turns solid, with a tick; let go, the row springs back and the copy is done. A side with nothing to copy
 * says so and does nothing. Both are custom accessibility actions too.
 */
@Composable
internal fun SwipeToCopy(
    shape: Shape,
    hasPassword: Boolean,
    hasUsername: Boolean,
    onCopyPassword: () -> Unit,
    onCopyUsername: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 88.dp.toPx() }
    // Beyond this the row follows the finger less and less.
    val free = with(density) { 140.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val canPassword by rememberUpdatedState(hasPassword)
    val canUsername by rememberUpdatedState(hasUsername)
    val copyPassword by rememberUpdatedState(onCopyPassword)
    val copyUsername by rememberUpdatedState(onCopyUsername)
    val toRight by remember { derivedStateOf { offset.value > 0f } }
    val shown by remember { derivedStateOf { offset.value != 0f } }
    val armed by remember { derivedStateOf { abs(offset.value) >= threshold } }

    val copyPasswordLabel = stringResource(R.string.list_copy_password)
    val copyUsernameLabel = stringResource(R.string.list_copy_username)
    Box(
        modifier
            .clip(shape)
            .semantics {
                customActions = listOfNotNull(
                    CustomAccessibilityAction(copyPasswordLabel) { copyPassword(); true }.takeIf { hasPassword },
                    CustomAccessibilityAction(copyUsernameLabel) { copyUsername(); true }.takeIf { hasUsername },
                )
            },
    ) {
        if (shown) {
            val possible = if (toRight) hasPassword else hasUsername
            val colours = MaterialTheme.colorScheme
            val (background, foreground) = when {
                !possible -> colours.surfaceContainerHighest to colours.onSurfaceVariant
                toRight && armed -> colours.primary to colours.onPrimary
                toRight -> colours.primaryContainer to colours.onPrimaryContainer
                armed -> colours.tertiary to colours.onTertiary
                else -> colours.tertiaryContainer to colours.onTertiaryContainer
            }
            val label = when {
                toRight -> if (possible) copyPasswordLabel else stringResource(R.string.list_no_password)
                else -> if (possible) copyUsernameLabel else stringResource(R.string.list_no_username)
            }
            Row(
                Modifier.matchParentSize().background(background).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, if (toRight) Alignment.Start else Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (toRight) Icon(Icons.Rounded.Key, contentDescription = null, tint = foreground)
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (armed && possible) FontWeight.Bold else FontWeight.Medium,
                    color = foreground,
                )
                if (!toRight) Icon(Icons.Rounded.Person, contentDescription = null, tint = foreground)
            }
        }
        Box(
            Modifier
                .absoluteOffset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    var dragged = 0f
                    val springBack = {
                        scope.launch { offset.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)) }
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            when {
                                dragged >= threshold && canPassword -> copyPassword()
                                dragged <= -threshold && canUsername -> copyUsername()
                            }
                            springBack()
                        },
                        onDragCancel = { springBack() },
                    ) { change, amount ->
                        change.consume()
                        val before = abs(dragged) >= threshold
                        dragged += amount
                        val after = abs(dragged) >= threshold
                        val possible = if (dragged > 0) canPassword else canUsername
                        if (before != after && possible) {
                            haptics.performHapticFeedback(
                                if (after) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick,
                            )
                        }
                        val distance = abs(dragged)
                        val resisted = min(distance, free) + (distance - free).coerceAtLeast(0f) * 0.3f
                        scope.launch { offset.snapTo(if (dragged < 0) -resisted else resisted) }
                    }
                },
        ) { content() }
    }
}
