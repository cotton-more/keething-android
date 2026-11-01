package dev.keething.android.entry

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.SecretTextMenu
import dev.keething.card.FieldRow
import dev.keething.card.RevealState

/** An ordinary entry's protected Notes open to this many lines at most and scroll inside past them (SPEC §6.4). */
private const val NOTES_LINES = 7

/**
 * A note's text, or an entry's Notes (SPEC §6.4; keething-spec types.note AC6; Mac `NoteTextView`): the label, the eye
 * of a protected text (the one control that shows it and hides it) and Copy, which copies the whole text. A note's
 * text is larger than a field's value. A protected text sits in a well: hidden, one line saying so, the same whatever
 * the text, and the text itself not composed (neither on screen nor in the accessibility tree); shown, the well opens
 * to the text's height (a note) or to a few lines scrolled inside (an entry's Notes). It is masked again as every
 * concealed row (the reveal state goes with the card). A note's text and protected Notes are selectable with a menu of
 * Copy and Select all only, a selection copied through the app ([CardActions.onCopySelection]); unprotected Notes keep
 * the system's selection.
 */
@Composable
internal fun NoteTextView(row: FieldRow, reveal: RevealState, actions: CardActions) {
    val note = row.kind == FieldRow.Kind.NOTE
    val noun = row.label.lowercase()
    val hidden = row.isConcealed && !reveal.isRevealed(row.id)
    val style = if (note) MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 28.sp) else MaterialTheme.typography.bodyLarge
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.extraColors.row).padding(start = 18.dp, end = 6.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.isConcealed) {
                IconButton(onClick = { actions.onToggle(row.id) }) {
                    Icon(
                        if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = if (hidden) "Show the $noun" else "Hide the $noun",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { actions.onCopy(row) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy the $noun", tint = MaterialTheme.colorScheme.primary)
            }
        }
        val text = @Composable { Text(row.revealedText(), style = style) }
        when {
            // Selectable rather than tap-to-copy: notes are long, a part of them is what is wanted.
            !row.isConcealed && !note -> SelectionContainer(Modifier.padding(end = 12.dp)) { text() }
            !row.isConcealed -> CopiedThroughTheApp(row, actions, Modifier.padding(end = 12.dp), text)
            else -> Well(scrolls = !note && !hidden, lineHeight = style.lineHeight.value) {
                if (hidden) HiddenLine(row.label) else CopiedThroughTheApp(row, actions, content = text)
            }
        }
    }
}

/** The recess a protected text sits in, hidden or shown; [scrolls]: at most [NOTES_LINES] lines, scrolled inside. */
@Composable
private fun Well(scrolls: Boolean, lineHeight: Float, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val colors = MaterialTheme.extraColors
    val maxHeight = with(LocalDensity.current) { (lineHeight * NOTES_LINES).sp.toDp() + 24.dp }
    Box(
        Modifier
            .padding(end = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.well)
            .innerShadow(shape, Shadow(radius = 3.dp, color = colors.wellShade, offset = DpOffset(0.dp, 1.dp)))
            .animateContentSize()
            .then(if (scrolls) Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        content()
    }
}

/** A hidden text: one line, a lock and Hidden, the same for every text (types.note AC6). */
@Composable
private fun HiddenLine(label: String) {
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "$label hidden" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Hidden", style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A selectable text whose selection is copied through the app: its menu without other apps' items ([SecretTextMenu]),
 * and the clipboard the selection writes to handing the text to [CardActions.onCopySelection] instead of the system's,
 * so that it is concealed and cleared (SPEC §4). Compose's selection writes through [LocalClipboard], from its menu
 * and from a keyboard's Ctrl+C alike.
 */
@Composable
private fun CopiedThroughTheApp(row: FieldRow, actions: CardActions, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val platform = LocalClipboard.current
    val copy by rememberUpdatedState { text: String -> actions.onCopySelection(row.id, text) }
    val clipboard = remember(platform) { SelectionClipboard(platform) { copy(it) } }
    CompositionLocalProvider(LocalClipboard provides clipboard) {
        SecretTextMenu(copyable = true, modifier = modifier) {
            SelectionContainer { content() }
        }
    }
}

/** Takes what a selection copies; reading is the system's. */
private class SelectionClipboard(private val platform: Clipboard, private val onCopy: (String) -> Unit) : Clipboard {
    override val nativeClipboard get() = platform.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = platform.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val clip = clipEntry?.clipData ?: return
        if (clip.itemCount == 0) return
        clip.getItemAt(0).text?.toString()?.let(onCopy)
    }
}
