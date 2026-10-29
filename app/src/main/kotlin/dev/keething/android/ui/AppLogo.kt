package dev.keething.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import dev.keething.android.R

/**
 * The launcher icon's two layers, cut as the launcher cuts them: the middle 72 of their 108 dp, in a rounded square.
 * The lock screen's and the welcome's large icon.
 */
@Composable
internal fun AppLogo(modifier: Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(30)), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(maxWidth * 108 / 72)
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, layer)
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, layer)
    }
}
