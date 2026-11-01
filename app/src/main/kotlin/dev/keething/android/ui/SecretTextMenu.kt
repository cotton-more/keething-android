package dev.keething.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar

/**
 * The text menu of secret fields in [content] (a password, a protected value, a recovery phrase): without what other
 * apps add to it ("Ask Gemini", "Translate", "Read aloud"), which hands them the text. [copyable] false also drops Copy
 * and Cut: a wallet's recovery phrase is never copied (SPEC §6.2), only Paste and Select all stay. Both of Compose's
 * text menus: the context menu's components, and the toolbar it used before.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SecretTextMenu(copyable: Boolean, modifier: Modifier = Modifier, active: Boolean = true, content: @Composable () -> Unit) {
    val platform = LocalTextToolbar.current
    val toolbar = remember(platform, copyable) { if (copyable) platform else NoCopyToolbar(platform) }
    val kept = if (copyable) COPYABLE else NOT_COPYABLE
    // [active] false: the menu as everywhere, in the same place in the tree (a field becoming secret keeps its focus).
    CompositionLocalProvider(LocalTextToolbar provides if (active) toolbar else platform) {
        Box(modifier.filterTextContextMenuComponents { !active || it.key in kept }) {
            content()
        }
    }
}

private val NOT_COPYABLE = setOf(TextContextMenuKeys.PasteKey, TextContextMenuKeys.SelectAllKey)
private val COPYABLE = NOT_COPYABLE + setOf(TextContextMenuKeys.CopyKey, TextContextMenuKeys.CutKey, TextContextMenuKeys.AutofillKey)

private class NoCopyToolbar(private val platform: TextToolbar) : TextToolbar by platform {
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) =
        platform.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested)

    override fun showMenu(
        rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?, onAutofillRequested: (() -> Unit)?,
    ) = platform.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested, onAutofillRequested)
}
