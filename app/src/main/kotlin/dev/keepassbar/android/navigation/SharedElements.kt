package dev.keepassbar.android.navigation

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * The list row grows into the card (SPEC §6.1): the entry's icon and title are the same element on both
 * screens. [key] names the entry and the list section it was opened from.
 */
@Composable
internal fun Modifier.sharedIcon(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animation = LocalNavAnimatedContentScope.current
    return with(shared) { this@sharedIcon.sharedElement(rememberSharedContentState("icon/$key"), animation) }
}

/** The title: scaled between the list's and the card's text size. */
@Composable
internal fun Modifier.sharedTitle(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animation = LocalNavAnimatedContentScope.current
    return with(shared) {
        this@sharedTitle.sharedBounds(
            rememberSharedContentState("title/$key"),
            animation,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillHeight, Alignment.CenterStart),
        )
    }
}
