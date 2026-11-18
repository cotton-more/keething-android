package dev.keething.android.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.keething.android.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * A short message at the bottom of a screen: something copied, or a problem; [undo] offers to take a change back,
 * or to do what [actionLabel] says. [lasting]: long enough to be read, as one with an action is.
 */
internal data class Notice(
    val text: UiText,
    val isProblem: Boolean = false,
    val undo: (() -> Unit)? = null,
    val actionLabel: UiText = uiText(R.string.notice_undo),
    val lasting: Boolean = false,
) {
    companion object {
        /** "Password copied · clears in 30 s" (SPEC §6); [what] is the field's name, as the card shows it. */
        fun copied(what: String) = Notice(uiText(R.string.notice_copied, what))

        fun copied(what: UiText) = Notice(uiText(R.string.notice_copied, what))

        /** The next one-time code, copied before its time (keething-spec card.code-timer). */
        fun nextCodeCopied(seconds: Int) = Notice(uiPlural(R.plurals.notice_next_code_copied, seconds))
    }
}

private class NoticeVisuals(val notice: Notice, resources: Resources) : SnackbarVisuals {
    override val message: String = notice.text.resolve(resources)
    override val actionLabel: String? = if (notice.undo != null) notice.actionLabel.resolve(resources) else null
    override val withDismissAction: Boolean get() = false
    override val duration: SnackbarDuration get() = if (notice.undo != null || notice.lasting) SnackbarDuration.Long else SnackbarDuration.Short
}

/** Shows each notice of [notices], replacing the one shown; a copy also gives a light haptic tick. */
@Composable
internal fun NoticeEffect(notices: Flow<Notice>, host: SnackbarHostState) {
    val haptics = LocalHapticFeedback.current
    val resources = LocalResources.current
    LaunchedEffect(notices, host) {
        notices.collect { notice ->
            haptics.performHapticFeedback(if (notice.isProblem) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
            host.currentSnackbarData?.dismiss()
            launch {
                if (host.showSnackbar(NoticeVisuals(notice, resources)) == SnackbarResult.ActionPerformed) notice.undo?.invoke()
            }
        }
    }
}

@Composable
internal fun NoticeHost(host: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(host, modifier) { data ->
        val problem = (data.visuals as? NoticeVisuals)?.notice?.isProblem == true
        val action = data.visuals.actionLabel
        Snackbar(
            Modifier.padding(horizontal = 12.dp),
            shape = MaterialTheme.shapes.large,
            action = action?.let {
                {
                    TextButton(onClick = data::performAction) {
                        Text(it, color = MaterialTheme.colorScheme.inversePrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (problem) Icons.Rounded.ErrorOutline else Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inversePrimary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(data.visuals.message.dotsAtLineEnd())
            }
        }
    }
}
