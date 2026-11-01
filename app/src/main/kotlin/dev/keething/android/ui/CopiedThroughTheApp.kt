package dev.keething.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard

/**
 * What the texts in [content] copy goes to [onCopy] instead of the system's clipboard, so that the app conceals and
 * clears it (SPEC §4): a selection's Copy, a text field's Copy and Cut, a keyboard's Ctrl+C. Compose's text writes
 * through [LocalClipboard] for all of them; reading it (Paste) stays the system's. [onCopy] null: the system's, as
 * without it (a text field that is protected or not keeps its place, and its focus, when that changes).
 */
@Composable
internal fun CopiedThroughTheApp(onCopy: ((String) -> Unit)?, content: @Composable () -> Unit) {
    val platform = LocalClipboard.current
    val copy by rememberUpdatedState(onCopy)
    val clipboard = remember(platform) { AppClipboard(platform) { copy } }
    CompositionLocalProvider(LocalClipboard provides clipboard, content = content)
}

/** Takes what a text copies, while [onCopy] gives a taker; reading is the system's. */
private class AppClipboard(private val platform: Clipboard, private val onCopy: () -> ((String) -> Unit)?) : Clipboard {
    override val nativeClipboard get() = platform.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = platform.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val onCopy = onCopy() ?: return platform.setClipEntry(clipEntry)
        val clip = clipEntry?.clipData ?: return
        if (clip.itemCount == 0) return
        clip.getItemAt(0).text?.toString()?.let(onCopy)
    }
}
