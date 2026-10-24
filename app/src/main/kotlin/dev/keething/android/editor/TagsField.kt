package dev.keething.android.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.keething.card.editing.EntryDraft

/**
 * The tags as chips with ✕ (Mac SPEC §3.24), then a field to type one: Enter, a comma or a semicolon adds it
 * (not when the entry has it already, in any case), Backspace in the empty field takes the last chip off. While
 * typing, the vault's tags that match (most used first, then fuzzy ones) are offered under it, a tap adds one.
 * What is still typed when saving is saved too. A tap anywhere in the box types a tag.
 */
@Composable
internal fun TagsField(draft: EntryDraft, update: (EntryDraft) -> Unit, knownTags: List<String>) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val suggestions = draft.tagSuggestions(knownTags)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Tags",
            style = MaterialTheme.typography.bodySmall,
            color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            for (tag in draft.tags) {
                InputChip(
                    selected = false,
                    onClick = { update(draft.removeTag(tag)) },
                    label = { Text(tag) },
                    trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, Modifier.size(InputChipDefaults.IconSize)) },
                    modifier = Modifier.semantics { contentDescription = "Remove the tag $tag" },
                )
            }
            Box(Modifier.widthIn(min = 120.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (draft.tagInput.isEmpty()) {
                    Text(
                        if (draft.tags.isEmpty()) "Add a tag" else "Add another",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = draft.tagInput,
                    onValueChange = { update(draft.typingTag(it)) },
                    modifier = Modifier
                        .widthIn(min = 120.dp)
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            val takesLastOff = event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                                draft.tagInput.isEmpty() && draft.tags.isNotEmpty()
                            if (takesLastOff) update(draft.removeLastTag())
                            takesLastOff
                        }
                        .semantics { contentDescription = "Add a tag" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    // Enter adds the tag and keeps the keyboard for the next one.
                    keyboardActions = KeyboardActions(onDone = { if (draft.tagInput.isNotBlank()) update(draft.commitTag()) }),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
        }
        if (suggestions.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tag in suggestions) {
                    SuggestionChip(onClick = { update(draft.commitTag(tag)) }, label = { Text(tag) })
                }
            }
        }
    }
}
