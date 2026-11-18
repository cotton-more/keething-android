package dev.keething.android.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.keething.android.R
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.NoLearningKeyboard
import dev.keething.android.ui.PathText
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.UiText
import dev.keething.android.ui.resolve
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.ui.segmentShape
import dev.keething.card.editing.GroupChoice
import dev.keething.card.editing.GroupPicker
import dev.keething.card.editing.GroupTree
import java.text.NumberFormat

/**
 * The group picker (plans/android-groups.md G2, G8; the agreed mock-up of AG3, one level at a time as the owner changed
 * it on 2026-10-31; keething-spec groups.new-groups AC1, AC2 on the phone): a full-height sheet, "Find or make a group"
 * over the top level. A row's tap chooses it (by its id) and closes; its › goes into it (the group first, then its
 * groups; ‹ above them goes back up). Typing finds groups below the level at any depth by a part of their name, each by
 * its whole path, the part matched bold; a path can be typed with `/`. "+ New group …" takes the levels typed that are
 * not groups yet, made with the save ([made] says when); the keyboard's Done takes what is typed as it is.
 *
 * With [refusal] (Groups & tags' Move to…, plans/android-groups.md G5): existing groups only, no "+ New group"; a group
 * [refusal] names a reason for is greyed with it and cannot be chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupPickerSheet(
    tree: GroupTree,
    chosen: GroupChoice?,
    made: String,
    onChoose: (GroupChoice) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.groups_picker_title),
    subtitle: String? = null,
    refusal: ((groupId: String) -> UiText?)? = null,
) {
    val canMake = refusal == null
    val picker = remember(tree) { GroupPicker(tree) }
    var field by remember { mutableStateOf(TextFieldValue()) }
    var entered by remember { mutableStateOf<GroupPicker.Entered?>(null) }
    val view = picker.view(GroupPicker.Query(field.text, entered))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        NoLearningKeyboard {
            Column(Modifier.fillMaxHeight().imePadding().navigationBarsPadding()) {
                Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.groups_picker_close)) }
                }
                TextField(
                    value = field,
                    onValueChange = { field = it },
                    modifier = Modifier.keepOutOfAutofill().fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    placeholder = { Text(stringResource(if (canMake) R.string.groups_picker_find_or_make else R.string.groups_picker_find)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (field.text.isNotEmpty()) {
                        { IconButton(onClick = { field = TextFieldValue(); entered = null }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.groups_picker_clear)) } }
                    } else {
                        null
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { view.typed?.takeIf { canMake || (it.newNames.isEmpty() && refusal?.invoke(it.groupId) == null) }?.let(onChoose) },
                    ),
                )
                val level = view.level
                if (level != null && !level.isRoot) {
                    val parent = tree.parentOf(level.id)
                    Row(Modifier.padding(start = 8.dp, end = 24.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val up = picker.up(view)
                                field = TextFieldValue(up.text, TextRange(up.text.length))
                                entered = up.entered
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.groups_picker_up_to, (parent?.let { GroupTexts.path(tree, it.id) } ?: GroupTexts.TOP_LEVEL).resolve()),
                            )
                        }
                        PathText(level.path, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                GroupTexts.hint(view)?.let {
                    Text(it.resolve(), Modifier.padding(start = 28.dp, end = 28.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)) {
                    itemsIndexed(view.rows, key = { _, row -> row.node.group.id }) { index, row ->
                        val group = row.node.group
                        val inside = view.isLevel && group.id != view.level?.id
                        // The entry's group below this row (or new groups in it): where to go to find it.
                        val holdsChosen = chosen != null && group.id != view.level?.id &&
                            (tree.isBelow(chosen.groupId, group.id) || (chosen.groupId == group.id && chosen.newNames.isNotEmpty()))
                        PickerRow(
                            row = row,
                            isLevel = view.isLevel,
                            canEnter = !group.isRoot && group.id != view.level?.id,
                            subgroups = if (inside) GroupTexts.subgroups(row.node.subgroups)?.resolve() else null,
                            // From this row's group down: the levels above it are those of the row.
                            nowIn = if (holdsChosen) tree.levels(chosen!!)?.map { it.name }?.drop(maxOf(group.path.size - 1, 0)) else null,
                            chosen = chosen?.newNames?.isEmpty() == true && chosen.groupId == group.id,
                            refused = refusal?.invoke(group.id)?.resolve(),
                            shape = segmentShape(index, view.rows.size, outer = 18.dp),
                            enterLabel = stringResource(R.string.groups_picker_go_into, GroupTexts.path(tree, row.node.group.id).resolve()),
                            onChoose = { onChoose(GroupChoice(row.node.group.id)) },
                            onEnter = {
                                val query = picker.enter(row.node.group.id)
                                field = TextFieldValue(query.text, TextRange(query.text.length))
                                entered = query.entered
                            },
                        )
                    }
                    GroupTexts.empty(tree, view, canMake)?.let { text ->
                        item(key = "empty") {
                            Text(text.resolve(), Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    view.offer?.takeIf { canMake }?.let { offer ->
                        item(key = "new") { NewGroupsRow(GroupTexts.newGroups(offer.newNames).resolve(), GroupTexts.newGroupsPlace(tree, offer, made).resolve()) { onChoose(offer) } }
                    }
                }
            }
        }
    }
}

/**
 * A group: by its name in a level ([subgroups] how many groups it holds, [nowIn] where the entry's group is below it),
 * by its whole path cut to fit in a search (the part matched bold); its › goes into it. [refused]: greyed, why under it.
 */
@Composable
private fun PickerRow(
    row: GroupPicker.Row,
    isLevel: Boolean,
    canEnter: Boolean,
    subgroups: String?,
    nowIn: List<String>?,
    chosen: Boolean,
    refused: String?,
    shape: RoundedCornerShape,
    enterLabel: String,
    onChoose: () -> Unit,
    onEnter: () -> Unit,
) {
    val group = row.node.group
    val total = row.node.total
    val entries = pluralStringResource(R.plurals.groups_entries, total, total)
    Row(
        Modifier
            .padding(bottom = SegmentGap)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.extraColors.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(enabled = refused == null, onClick = onChoose, onClickLabel = stringResource(R.string.groups_picker_choose))
                .alpha(if (refused == null) 1f else 0.5f)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (group.isRoot) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
                contentDescription = null,
                Modifier.size(22.dp),
                tint = if (refused == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (isLevel || group.isRoot) {
                    Text(
                        if (group.isRoot) GroupTexts.TOP_LEVEL.resolve() else group.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    PathText(
                        group.path,
                        style = MaterialTheme.typography.bodyLarge,
                        prefixStyle = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        last = matchedName(row),
                    )
                }
                val supporting = MaterialTheme.typography.bodySmall
                refused?.let { Text(it, style = supporting, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                subgroups?.let { Text(it, style = supporting, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                nowIn?.let {
                    PathText(it, style = supporting, color = MaterialTheme.colorScheme.primary, lead = AnnotatedString(stringResource(R.string.groups_picker_now_in)))
                }
                if (row.node.hasNamesake) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.groups_picker_namesake), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (chosen) Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.groups_picker_chosen), Modifier.padding(start = 8.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                NumberFormat.getIntegerInstance().format(total),
                Modifier.padding(start = 8.dp).semantics { contentDescription = entries },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canEnter) {
            Box(Modifier.width(1.dp).heightIn(min = 56.dp).background(MaterialTheme.colorScheme.surfaceContainerLow))
            IconButton(onClick = onEnter, modifier = Modifier.width(52.dp)) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = enterLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A found group's name, the part typed bold in the primary colour. */
@Composable
private fun matchedName(row: GroupPicker.Row): AnnotatedString {
    val name = row.node.group.name
    val primary = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
            val match = row.match
            if (match == null) {
                append(name)
            } else {
                append(name.substring(0, match.first))
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = primary)) { append(name.substring(match)) }
                append(name.substring(match.last + 1))
            }
        }
    }
}

/** The last row: the levels typed that are not groups yet, in a dashed box. */
@Composable
private fun NewGroupsRow(label: String, place: String, onChoose: () -> Unit) {
    val border = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .drawBehind {
                drawRoundRect(
                    border,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .clickable(onClick = onChoose)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
