package dev.keepassbar.android.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The list and the card side by side (SPEC §6.1): a window at least 840 dp wide, a phone in landscape too. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun inTwoPanes(): Boolean = LocalListDetailSceneScope.current != null

/** The list's pane: the window's edge on its left only, the card's pane is on its right. One pane: the list as it is. */
@Composable
internal fun ListPane(content: @Composable () -> Unit) {
    if (!inTwoPanes()) return content()
    Box(Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End))) { content() }
}

/** The card beside the list, in its pane; one pane: the card as it is. */
@Composable
internal fun CardPane(content: @Composable () -> Unit) {
    if (inTwoPanes()) DetailPane(content) else content()
}

/**
 * The card's pane: a rounded surface a shade darker than the list, clear of the window's edges; what is inside
 * finds the system bars already taken care of.
 */
@Composable
private fun DetailPane(content: @Composable () -> Unit) {
    val margin = if (LocalConfiguration.current.screenHeightDp < 480) 8.dp else 16.dp
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End))
            .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
            .padding(top = margin, bottom = margin, end = margin)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) { content() }
}

/** The card's pane before an entry is chosen: no card opens by itself, so no password shows without a tap. */
@Composable
internal fun NoEntryChosen() {
    DetailPane {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Key, contentDescription = null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.outline)
            }
            Text(
                "Choose an entry: its card opens here.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
