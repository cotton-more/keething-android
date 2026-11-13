package dev.keething.android.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** The window upright while shown (either way up, as the user turns it); as it was afterwards. */
@Composable
internal fun HoldPortrait() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val before = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        onDispose { activity.requestedOrientation = before }
    }
}
