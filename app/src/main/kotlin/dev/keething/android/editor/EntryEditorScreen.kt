package dev.keething.android.editor

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.keething.android.ui.CodeValue
import dev.keething.android.ui.FitText
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.ui.rememberSecondsNow
import dev.keething.card.TotpDisplay
import dev.keething.card.editing.AddedCode
import dev.keething.card.editing.CodeDraft
import dev.keething.domain.totp.TotpConfig
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import dev.keething.android.ui.CopiedThroughTheApp
import dev.keething.card.CryptoWallet
import dev.keething.card.editing.EditorLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import dev.keething.android.groups.GroupPickerSheet
import dev.keething.android.groups.GroupTexts
import dev.keething.card.editing.GroupChoice
import dev.keething.card.editing.GroupTree
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import dev.keething.android.ui.ButtonLabel
import dev.keething.card.FieldType
import java.time.LocalDate
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.generator.GeneratorUse
import dev.keething.android.generator.PasswordGeneratorSheet
import dev.keething.android.generator.PasswordGeneratorState
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import dev.keething.android.databases.DatabaseDot
import dev.keething.android.databases.databaseColors
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.segmentShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ModalBottomSheet
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.SecretTextMenu
import dev.keething.android.ui.NoLearningKeyboard
import dev.keething.android.ui.colouredSecret
import dev.keething.android.ui.readableInset
import dev.keething.card.RevealState
import dev.keething.card.editing.CodeList
import dev.keething.card.editing.EntryDraft
import dev.keething.card.editing.EntryEditor
import dev.keething.card.editing.FieldDraft
import dev.keething.domain.vault.VaultGroup
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The entry editor (SPEC §6.2, the agreed mock-up; Mac editor's rules): the standard fields, the tags, the
 * expiry and the custom fields (name above value; type, protected and remove under it); problems keep Save off and
 * are said at the field. A list of codes is folded into one row, as the card lists it. Saving shows "Saving and
 * checking…"; saved, [onSaved] gets the entry (a new one's id) at once, and only a failure stays on the sheet.
 */
@Composable
internal fun EntryEditorScreen(
    database: DatabaseSession,
    entryId: String?,
    groupId: String?,
    generator: () -> PasswordGeneratorState,
    onClose: () -> Unit,
    /** The entry saved, in its database (a new one's may have moved: Save into), and the empty groups a move deleted. */
    onSaved: (EntryRef, List<VaultGroup>) -> Unit,
    prefill: ((EntryDraft) -> EntryDraft)? = null,
    /** What the editor is for, above the fields (the save offer of autofill). */
    banner: (@Composable () -> Unit)? = null,
    /** A new entry's other open databases (Save into; keething-spec databases.one-list AC5, AC6). */
    places: NewEntryPlaces? = null,
    /** Another showing of the same screen (after a lock and an unlock): a fresh editor, not the one whose draft went. */
    showing: Int = 0,
    /** A new note's editor and the text it starts with (SPEC §6.4). */
    note: NewNote? = null,
    /** What a secret's text field copies or cuts: through the app, concealed and cleared (SPEC §4). */
    copySecret: (String) -> Unit,
) {
    // A new entry's editor is its own whichever database it started in: it may have moved to another (Save into).
    val model = viewModel(key = if (entryId == null) "editor/new/$showing" else "editor/${database.number}/$entryId") { EntryEditorViewModel(database, entryId, groupId, prefill, places, note) }
    val editor = model.editor
    LaunchedEffect(editor == null) { if (editor == null) onClose() }
    if (editor == null) return
    val draft = model.draft
    val phase = model.phase
    LaunchedEffect(phase) { if (phase is SavePhase.Saved) onSaved(phase.ref, phase.deletedGroups) }
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    val close = { if (editor.isDirty(draft)) confirmingDiscard = true else onClose() }
    BackHandler {
        when (phase) {
            SavePhase.Editing -> close()
            SavePhase.Saving -> {}
            is SavePhase.Saved -> {}
            is SavePhase.Failed -> model.keepEditing()
        }
    }
    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("Discard the changes?") },
            text = { Text(if (editor.isNew) "The new ${editor.noun} is not saved." else "What you changed in this ${editor.noun} is not saved.") },
            confirmButton = { TextButton(onClick = { confirmingDiscard = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text("Keep editing") } },
        )
    }

    // The keyboard is closed first: the save sheet comes up from the bottom, under where the keyboard was.
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val save = {
        focus.clearFocus()
        keyboard?.hide()
        model.save()
    }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(if (note?.quick == true) "Quick note" else if (editor.isNew) "New ${editor.noun}" else "Edit ${editor.noun}") },
                    navigationIcon = {
                        IconButton(onClick = close) {
                            Icon(Icons.Rounded.Close, contentDescription = if (editor.isDirty(draft)) "Discard changes" else "Close")
                        }
                    },
                    actions = {
                        Button(onClick = save, enabled = editor.canSave(draft) && phase == SavePhase.Editing, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Save")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    scrollBehavior = scroll,
                )
            },
        ) { padding ->
            NoLearningKeyboard {
                // The keyboard's inset outside the scroll: a field brought into view is above the keyboard.
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                        .verticalScroll(rememberScrollState())
                        .padding(top = padding.calculateTopPadding())
                        .padding(start = readableInset(), end = readableInset(), top = 8.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    banner?.invoke()
                    val into = model.chosen?.takeIf { model.offered.isNotEmpty() }
                    val saveInto: (@Composable () -> Unit)? = into?.let {
                        { SaveInto(it, model.offered, model.notOpen, enabled = phase == SavePhase.Editing, onChoose = model::saveInto) }
                    }
                    CompositionLocalProvider(LocalSecretCopy provides copySecret) {
                        EditorForm(editor, draft, model::update, model::addCodes, generator, model.knownTags, saveInto, model.groups, model::choose)
                    }
                }
            }
        }
        SaveSheet(phase, onKeepEditing = model::keepEditing)
    }
}

@Composable
private fun EditorForm(
    editor: EntryEditor,
    draft: EntryDraft,
    update: (EntryDraft) -> Unit,
    addCodes: (text: String, prefix: String) -> String?,
    generator: () -> PasswordGeneratorState,
    knownTags: List<String>,
    /** A new entry's database over its group, the two as one block (while two or more are open). */
    saveInto: (@Composable () -> Unit)?,
    groups: GroupTree?,
    onGroup: (GroupChoice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        saveInto?.invoke()
        if (groups != null) GroupRow(groups, editor, draft, joined = saveInto != null, onChoose = onGroup)
    }
    val note = editor.layout == EditorLayout.NOTE
    EditorField(
        "Title", draft.title, { update(draft.copy(title = it)) }, highlighted = !note, capitalization = KeyboardCapitalization.Sentences,
        placeholder = editor.titlePlaceholder(draft).takeIf { note },
    )
    if (note) NoteTextBlock(editor, draft, update)
    // A note shows a username, a password and fields only when another app gave it them (keething-spec types.note AC3).
    if (editor.showsUsername) EditorField("Username", draft.username, { update(draft.copy(username = it)) }, keyboardType = KeyboardType.Email)
    var passwordShown by remember { mutableStateOf(false) }
    var generating by rememberSaveable { mutableStateOf(false) }
    if (editor.showsPassword) {
        PasswordField(
            draft.password,
            onChange = { update(draft.copy(password = it)) },
            revealed = passwordShown,
            onToggle = { passwordShown = !passwordShown },
            onGenerate = { generating = true },
        )
    }
    if (generating) {
        val state = remember { generator() }
        PasswordGeneratorSheet(
            state,
            GeneratorUse.IntoEntry { password ->
                update(draft.copy(password = password))
                passwordShown = true
                generating = false
            },
            onDismiss = { generating = false },
        )
    }
    if (editor.showsWebsite(draft)) EditorField("Website", draft.url, { update(draft.copy(url = it)) }, keyboardType = KeyboardType.Uri)
    OwnCodeRow(editor, draft, update)
    if (!note) {
        // An ordinary entry's protected Notes: their text menu and copies as a secret's.
        EditorField(
            "Notes", draft.notes, { update(draft.copy(notes = it)) }, singleLine = false, capitalization = KeyboardCapitalization.Sentences,
            secret = draft.notesProtected,
        )
    }
    TagsField(draft, update, knownTags)
    if (!editor.showsWebsite(draft)) {
        FilledTonalButton(onClick = { update(draft.copy(websiteAdded = true)) }) {
            ButtonLabel("Website", Icons.Rounded.Add, iconSize = 18.dp, gap = 8.dp)
        }
    }
    ExpiryRow(draft, update)
    if (!editor.showsCustomFields) return

    Text(
        "Fields",
        Modifier.padding(start = 8.dp, top = 16.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    // A field just added: its value takes the focus and the form scrolls to it.
    var focusing by remember { mutableStateOf<Int?>(null) }
    CustomFields(editor, draft, update, generator, focusing, onFocused = { focusing = null })
    var addingCodes by rememberSaveable { mutableStateOf(false) }
    var addingCode by rememberSaveable { mutableStateOf(false) }
    if (editor.offersFields) {
        AddFieldButton(
            onAdd = { type ->
                val added = draft.addField(type)
                update(added)
                focusing = added.fields.last().id
            },
            onCodes = { addingCodes = true },
            anotherCode = draft.code != null,
            onOneTimeCode = { addingCode = true },
        )
    }
    if (addingCodes) AddCodesDialog(onAdd = addCodes, onDone = { addingCodes = false })
    if (addingCode) {
        AddOneTimeCodeDialog(
            another = draft.code != null,
            onAdd = { text ->
                when (val added = editor.addOneTimeCode(draft, text)) {
                    is AddedCode.Added -> null.also { update(added.draft) }
                    is AddedCode.Refused -> added.problem
                }
            },
            onAddAny = { texts ->
                when (val added = editor.addOneTimeCode(draft, texts)) {
                    is AddedCode.Added -> null.also { update(added.draft) }
                    is AddedCode.Refused -> added.problem
                }
            },
            onDone = { addingCode = false },
        )
    }

    val problems = draft.fields.mapNotNull { editor.fieldProblem(draft, it) }.distinct()
    AnimatedVisibility(problems.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "Save is off. " + problems.joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** A filled field with its label above the value, as on the mock-up: no underline, large corners. */
@Composable
private fun EditorField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** Masked: dots for each character while typed in, the card's fixed dots otherwise ([visualTransformation] unused). */
    hidden: Boolean = false,
    /** A secret: its text menu without other apps' items ([SecretTextMenu]); not [copyable]: without Copy and Cut too. */
    secret: Boolean = false,
    copyable: Boolean = true,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val field: @Composable () -> Unit = {
        TextField(
            value = value,
            onValueChange = onChange,
            modifier = modifier.keepOutOfAutofill().fillMaxWidth().onFocusChanged { focused = it.isFocused }
                // Never copied: a keyboard's Cut (Ctrl+X, Gboard's Cut key) would take the text to nowhere, so it is refused.
                .then(if (secret && !copyable) Modifier.onPreviewKeyEvent { it.isCut } else Modifier),
            label = { Text(label, color = if (highlighted) MaterialTheme.colorScheme.primary else Color.Unspecified) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            textStyle = textStyle,
            visualTransformation = when {
                !hidden -> visualTransformation
                focused -> PasswordVisualTransformation()
                else -> FixedMask
            },
            trailingIcon = trailingIcon,
            keyboardOptions = KeyboardOptions(
                capitalization = capitalization,
                autoCorrectEnabled = keyboardType == KeyboardType.Text && capitalization == KeyboardCapitalization.Sentences,
                keyboardType = keyboardType,
            ),
            shape = RoundedCornerShape(18.dp),
            colors = editorFieldColors(containerColor),
        )
    }
    val copySecret = LocalSecretCopy.current
    when {
        !secret -> field()
        copyable -> SecretTextMenu(copyable = true) { CopiedThroughTheApp(copySecret, field) }
        // Never copied (a recovery phrase): a keyboard's Ctrl+C or Copy, which the menu does not offer, goes nowhere.
        else -> SecretTextMenu(copyable = false) { CopiedThroughTheApp(onCopy = {}, content = field) }
    }
}

/** Cut as a key: the Cut key (which a keyboard's Cut sends too), or Ctrl+X / Meta+X. */
private val KeyEvent.isCut: Boolean
    get() = key == Key.Cut || (key == Key.X && (isCtrlPressed || isMetaPressed))

/** Where the editor's secret fields copy and cut to: the app's clipboard (null: none given, as in a preview). */
private val LocalSecretCopy = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** "note" or "entry", as the editor's title and its discard question say it. */
private val EntryEditor.noun: String get() = if (layout == EditorLayout.NOTE) "note" else "entry"

/**
 * A note's text and its Protect text under it, one block (the agreed mock-up; SPEC §6.4): the text shown as typed (the
 * window is never captured), larger than a field's; protected, a secret's text menu and copies. A new note opens on it
 * (keething-spec types.note AC4).
 */
@Composable
private fun NoteTextBlock(editor: EntryEditor, draft: EntryDraft, update: (EntryDraft) -> Unit) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val focus = remember { FocusRequester() }
    if (editor.isNew) LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(container)) {
        val field: @Composable () -> Unit = {
            TextField(
                value = draft.notes,
                onValueChange = { update(draft.copy(notes = it)) },
                modifier = Modifier.keepOutOfAutofill().fillMaxWidth().focusRequester(focus),
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Text", color = MaterialTheme.colorScheme.primary)
                        if (draft.notesProtected) Icon(Icons.Rounded.Lock, contentDescription = "protected", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                placeholder = { Text("What to keep") },
                minLines = 8,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
                colors = editorFieldColors(container),
            )
        }
        val copySecret = LocalSecretCopy.current
        // One place in the tree either way: Protect text switched keeps the cursor and the keyboard.
        SecretTextMenu(copyable = true, active = draft.notesProtected) { CopiedThroughTheApp(copySecret.takeIf { draft.notesProtected }, field) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(draft.notesProtected, role = Role.Switch) { update(editor.protecting(draft, on = it)) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Protect text", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("Hidden on the card until shown, and not searched", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = draft.notesProtected, onCheckedChange = null)
        }
    }
}

/** A masked value not being typed in: the card's dots, which do not tell its length; the cursor goes to its end. */
private val FixedMask = VisualTransformation { text ->
    val dots = if (text.isEmpty()) "" else RevealState.MASK
    TransformedText(
        AnnotatedString(dots),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = dots.length
            override fun transformedToOriginal(offset: Int) = text.length
        },
    )
}

@Composable
private fun editorFieldColors(container: Color) = TextFieldDefaults.colors(
    focusedContainerColor = container,
    unfocusedContainerColor = container,
    disabledContainerColor = container,
    errorContainerColor = container,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
)

/**
 * Masked, or shown monospaced with its digits and symbols coloured (SPEC §6.1); never learnt by the keyboard.
 * The wand opens the password generator, whose password then replaces this one, shown.
 */
@Composable
private fun PasswordField(password: String, onChange: (String) -> Unit, revealed: Boolean, onToggle: () -> Unit, onGenerate: () -> Unit) {
    EditorField(
        "Password",
        password,
        onChange,
        keyboardType = KeyboardType.Password,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily),
        visualTransformation = colouredSecretTransformation(),
        hidden = !revealed,
        secret = true,
        trailingIcon = {
            Row {
                RevealButton(revealed, "password", onToggle)
                IconButton(onClick = onGenerate) {
                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = "Generate a password", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
    )
}

@Composable
private fun RevealButton(revealed: Boolean, what: String, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            contentDescription = if (revealed) "Hide $what" else "Show $what",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun colouredSecretTransformation(): VisualTransformation {
    val digit = MaterialTheme.extraColors.digit
    val symbol = MaterialTheme.extraColors.symbol
    return remember(digit, symbol) {
        VisualTransformation { text -> TransformedText(colouredSecret(text.text, digit, symbol), OffsetMapping.Identity) }
    }
}

/** KeePass's own expiry: a switch, and the day it expires on (the time of day kept). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryRow(draft: EntryDraft, update: (EntryDraft) -> Unit) {
    val zone = ZoneId.systemDefault()
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Expires", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (draft.expires) {
            TextButton(onClick = { picking = true }) {
                Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(zone).format(draft.expiryTime))
            }
        }
        Switch(checked = draft.expires, onCheckedChange = { update(draft.copy(expires = it)) }, Modifier.semantics { contentDescription = "Expires" })
    }
    if (picking) {
        val day = draft.expiryTime.atZone(zone).toLocalDate()
        val state = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val chosen = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()
                        update(draft.expiringOn(chosen, zone))
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/**
 * A new entry's database while two or more are open (keething-spec databases.one-list AC5; the agreed mock-up): its
 * dot, label and folder over the group; a tap opens the sheet of the open databases, those not open named under them.
 * Another one moves the draft.
 */
@Composable
private fun SaveInto(chosen: NewEntryDatabase, offered: List<NewEntryDatabase>, notOpen: String?, enabled: Boolean, onChoose: (DatabaseId) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val colors = databaseColors(chosen.mark.colorIndex)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClickLabel = "Choose the database") { choosing = true }
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Save into", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colors.dot))
                Text(
                    chosen.mark.label.full,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                chosen.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (choosing) {
        SaveIntoSheet(
            offered = offered,
            chosen = chosen.id,
            notOpen = notOpen,
            onChoose = {
                choosing = false
                onChoose(it)
            },
            onDismiss = { choosing = false },
        )
    }
}

/**
 * Save into's sheet: the open databases (dot, label, folder; a check on the [chosen] one), those not open under them.
 * Also Groups & tags' New group while two or more are open, with its own [title] and [subtitle].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveIntoSheet(
    offered: List<NewEntryDatabase>,
    chosen: DatabaseId?,
    notOpen: String?,
    onChoose: (DatabaseId) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Save into",
    subtitle: String = "The group, tags and username follow the database chosen.",
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.heightIn(max = 362.dp).clip(RoundedCornerShape(20.dp)).verticalScroll(rememberScrollState())) {
                offered.forEachIndexed { index, database ->
                    val colors = databaseColors(database.mark.colorIndex)
                    Row(
                        Modifier
                            .padding(bottom = SegmentGap)
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clip(segmentShape(index, offered.size))
                            .background(MaterialTheme.extraColors.row)
                            .clickable { onChoose(database.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        DatabaseDot(database.mark.colorIndex)
                        Column(Modifier.weight(1f)) {
                            Text(
                                database.mark.label.full,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                            database.detail?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (database.id == chosen) Icon(Icons.Rounded.Check, contentDescription = "Chosen", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            notOpen?.let {
                Text(it, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The entry's group (keething-spec groups.entry-group AC1; plans/android-groups.md G3, the agreed mock-up), for a new
 * and an existing entry: its path from the top, wrapped, the new levels marked; under it what the save makes, or where a
 * moved entry comes from. A tap opens the group picker. [joined]: under Save into, as one block.
 */
@Composable
private fun GroupRow(tree: GroupTree, editor: EntryEditor, draft: EntryDraft, joined: Boolean, onChoose: (GroupChoice) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val levels = tree.levels(draft.group)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(if (joined) RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp) else RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = "Choose the group") { picking = true }
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Group", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Folder, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                when {
                    levels == null -> Text("No longer in the file: choose another", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    levels.isEmpty() -> Text(GroupTexts.TOP_LEVEL, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    else -> FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        // New levels in their own colour, one NEW after the last: one badge a level is too many on a long path.
                        levels.forEachIndexed { index, level ->
                            if (index > 0) Text(" › ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                level.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (level.isNew) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (level.isNew) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                            )
                        }
                        if (levels.last().isNew) NewBadge()
                    }
                }
            }
            GroupTexts.note(tree, draft.group, editor.originalGroupId.takeIf { editor.isMoved(draft) })?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (picking) {
        GroupPickerSheet(
            tree = tree,
            chosen = draft.group,
            made = "made when the entry is saved",
            onChoose = {
                picking = false
                onChoose(it)
            },
            onDismiss = { picking = false },
        )
    }
}

/** A level made with the save. */
@Composable
private fun NewBadge() {
    Text(
        "NEW",
        Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 7.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
}

/** The custom fields in file order; a list of codes folded into one row until it is opened (or has a problem). */
@Composable
private fun CustomFields(
    editor: EntryEditor,
    draft: EntryDraft,
    update: (EntryDraft) -> Unit,
    generator: () -> PasswordGeneratorState,
    focusing: Int?,
    onFocused: () -> Unit,
) {
    val guesses = editor.guessedTypes(draft)
    val lists = editor.codeLists(draft)
    val listOf = lists.flatMap { list -> list.fieldIds.map { it to list } }.toMap()
    val opened = rememberSaveable(saver = openedSaver) { mutableStateMapOf<String, Boolean>() }
    for (field in draft.fields) {
        val list = listOf[field.id]
        if (list != null) {
            val hasProblem = list.fieldIds.any { id -> draft.fields.first { it.id == id }.let { editor.fieldProblem(draft, it) != null } }
            val open = opened[list.title] == true || hasProblem
            val first = list.fieldIds.first() == field.id
            if (first) CodeListRow(list, open, onToggle = { opened[list.title] = !open })
            if (!open) continue
        }
        FieldEditor(
            field,
            code = remember(field.value) { editor.oneTimeCode(field) },
            problem = editor.fieldProblem(draft, field),
            warning = editor.fieldWarning(field),
            guessed = guesses[field.id] ?: FieldType.TEXT,
            generator = generator,
            focusNow = focusing == field.id,
            onFocused = onFocused,
            onChange = { changed -> update(draft.changeField(field.id) { changed }) },
            onType = { type -> update(draft.withType(field.id, type)) },
            onDate = { day -> update(draft.withDate(field.id, day)) },
            onRemove = { update(draft.removeField(field.id)) },
        )
    }
}

/** Which lists of codes are open, by title (names only: nothing secret is kept for a rotation). */
private val openedSaver = Saver<SnapshotStateMap<String, Boolean>, ArrayList<String>>(
    save = { map -> ArrayList(map.filterValues { it }.keys) },
    restore = { titles -> mutableStateMapOf<String, Boolean>().apply { titles.forEach { put(it, true) } } },
)

@Composable
private fun CodeListRow(list: CodeList, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.extraColors.row)
            .clickable(onClickLabel = if (open) "Fold the codes" else "Show the codes", onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(list.title, style = MaterialTheme.typography.titleSmall)
            val count = list.fieldIds.size
            Text(
                "$count codes" + if (list.allProtected) " · protected" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One custom field: its name above its value, the problem with it or a value typed as a date that is none, and
 * under them its type, protected and remove (the name has the row's whole width). A protected value or a password is masked (an eye shows it); a
 * password offers the generator, a date the calendar (Mac SPEC §3.12, §3.16).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditor(
    field: FieldDraft,
    /** The field holds a one-time code's link: its live code instead of the value, which is not edited by hand. */
    code: TotpConfig?,
    problem: String?,
    warning: String?,
    guessed: FieldType,
    generator: () -> PasswordGeneratorState,
    focusNow: Boolean,
    onFocused: () -> Unit,
    onChange: (FieldDraft) -> Unit,
    onType: (FieldType?) -> Unit,
    onDate: (LocalDate) -> Unit,
    onRemove: () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    var generating by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val type = field.type
    val neverCopied = CryptoWallet.isNeverCopied(type, field.isProtected, field.value)
    val masked = field.isProtected || type?.isPassword == true || neverCopied
    val valueFocus = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    if (focusNow) {
        LaunchedEffect(Unit) {
            valueFocus.requestFocus()
            // Again once the keyboard is up and the form has room above it.
            delay(350)
            bringIntoView.bringIntoView()
            onFocused()
        }
    }
    val transparent = Color.Transparent
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.extraColors.row)
            .then(if (problem != null) Modifier.border(2.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(18.dp)) else Modifier)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        EditorField(
            "Name",
            field.name,
            { onChange(field.copy(name = it)) },
            containerColor = transparent,
            textStyle = MaterialTheme.typography.titleSmall,
            capitalization = KeyboardCapitalization.Sentences,
        )
        if (code != null) {
            AnotherCode(code)
        } else EditorField(
            "Value",
            field.value,
            { onChange(field.copy(value = it)) },
            modifier = Modifier.focusRequester(valueFocus),
            containerColor = transparent,
            singleLine = '\n' !in field.value,
            // Digits never learnt by the keyboard for a card's number, CVC and PIN.
            keyboardType = when {
                type == FieldType.CARD_NUMBER || type == FieldType.CARD_CVC || type == FieldType.CARD_PIN -> KeyboardType.NumberPassword
                type == FieldType.SSH_PORT -> KeyboardType.Number
                masked -> KeyboardType.Password
                type == FieldType.URL -> KeyboardType.Uri
                else -> KeyboardType.Text
            },
            textStyle = if (masked) MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily) else MaterialTheme.typography.bodyLarge,
            visualTransformation = if (masked) colouredSecretTransformation() else VisualTransformation.None,
            hidden = masked && !revealed,
            secret = masked,
            // A recovery phrase, its passphrase, a private key: no Copy in the field's menu either.
            copyable = !neverCopied,
            trailingIcon = {
                Row {
                    if (masked) RevealButton(revealed, "value") { revealed = !revealed }
                    if (type?.isPassword == true) {
                        IconButton(onClick = { generating = true }) {
                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = "Generate a password", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (type?.isDate == true && !field.isProtected) {
                        IconButton(onClick = { picking = true }) {
                            Icon(Icons.Rounded.CalendarMonth, contentDescription = "Choose a day", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
        )
        val note = problem ?: warning
        if (note != null) {
            val colour = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.extraColors.warn
            Row(Modifier.padding(start = 16.dp, top = 2.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (problem != null) Icons.Rounded.ErrorOutline else Icons.Rounded.WarningAmber, contentDescription = null, Modifier.size(16.dp), tint = colour)
                Text(note, style = MaterialTheme.typography.bodySmall, color = colour)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // With a large font the chips go one under the other; the remove button keeps its place at the end.
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (code == null) FieldTypeChip(field, guessed, onType)
                ProtectedChip(field.isProtected) { onChange(field.copy(isProtected = it)) }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove field ${field.name}".trim(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (generating) {
        val state = remember { generator() }
        PasswordGeneratorSheet(
            state,
            GeneratorUse.IntoEntry { password ->
                onChange(field.copy(value = password))
                revealed = true
                generating = false
            },
            onDismiss = { generating = false },
        )
    }
    if (picking) {
        val start = field.date ?: LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/**
 * A field's type (Mac SPEC §3.12) as a chip: the type it has, or what its value is read as ("Text · auto"), or a
 * type this app does not know. The menu: *Automatic* (no type), the types of their own, a payment card's and a
 * Wi-Fi network's parts.
 */
@Composable
private fun FieldTypeChip(field: FieldDraft, guessed: FieldType, onType: (FieldType?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val type = field.type
    val shown = type ?: guessed
    val label = when {
        type != null -> type.fullTitle
        field.typeName != null -> "“${field.typeName}”"
        else -> "${guessed.fullTitle} · auto"
    }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(
                    shown.icon(),
                    contentDescription = null,
                    Modifier.size(AssistChipDefaults.IconSize),
                    tint = if (field.typeName == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
        )
        FieldTypeMenu(
            open,
            onDismiss = { open = false },
            basic = FieldType.BASIC,
            chosen = type,
            onChoose = { chosen ->
                onType(chosen)
                open = false
            },
            top = { close ->
                TypeItem("Automatic (${guessed.fullTitle})", chosen = field.typeName == null) {
                    onType(null)
                    close()
                }
                HorizontalDivider()
            },
            bottom = {
                val unknown = field.typeName?.takeIf { type == null }
                if (unknown != null) {
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("“$unknown” (not known here, kept)") }, onClick = {}, enabled = false, leadingIcon = { Icon(Icons.Rounded.Check, null) })
                }
            },
        )
    }
}

/** "Add field": a field of a type, named at once with the cursor in its value, a block of codes, or a one-time code. */
@Composable
private fun AddFieldButton(onAdd: (FieldType) -> Unit, onCodes: () -> Unit, anotherCode: Boolean, onOneTimeCode: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 4.dp)) {
        FilledTonalButton(onClick = { open = true }) {
            ButtonLabel("Add field", Icons.Rounded.Add, iconSize = 18.dp, gap = 8.dp)
        }
        FieldTypeMenu(
            open,
            onDismiss = { open = false },
            basic = listOf(FieldType.TEXT, FieldType.DATE, FieldType.UNTIL, FieldType.URL, FieldType.PASSWORD),
            chosen = null,
            onChoose = { type ->
                open = false
                type?.let(onAdd)
            },
            bottom = { close ->
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Codes…") },
                    leadingIcon = { Icon(FieldType.CODE.icon(), contentDescription = null) },
                    onClick = {
                        close()
                        onCodes()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (anotherCode) "Another one-time code…" else "One-time code…") },
                    leadingIcon = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
                    onClick = {
                        close()
                        onOneTimeCode()
                    },
                )
            },
        )
    }
}

/**
 * The types in a menu: [basic] ones, then *Card ›*, *Wi-Fi ›*, *Bank account ›*, *Document ›*, *SSH ›* and *Crypto ›*,
 * which show their parts in its place (with a way back). [top] and [bottom] add items around them; they get a way to
 * close the menu.
 */
@Composable
private fun FieldTypeMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    basic: List<FieldType>,
    chosen: FieldType?,
    onChoose: (FieldType?) -> Unit,
    top: (@Composable (close: () -> Unit) -> Unit)? = null,
    bottom: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    var group by remember { mutableStateOf<List<FieldType>?>(null) }
    val close = {
        group = null
        onDismiss()
    }
    DropdownMenu(expanded = expanded, onDismissRequest = close) {
        val parts = group
        if (parts != null) {
            DropdownMenuItem(
                text = { Text(FieldType.KINDS.first { it.second == parts }.first, style = MaterialTheme.typography.titleSmall) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") },
                onClick = { group = null },
            )
            HorizontalDivider()
            for (type in parts) {
                TypeItem(type.title, chosen = type == chosen, icon = type.icon()) {
                    group = null
                    onChoose(type)
                }
            }
        } else {
            top?.invoke(close)
            for (type in basic) {
                TypeItem(type.title, chosen = type == chosen, icon = type.icon()) { onChoose(type) }
            }
            HorizontalDivider()
            for ((title, parts) in FieldType.KINDS) {
                DropdownMenuItem(
                    text = { Text(title) },
                    leadingIcon = { Icon(parts.first().icon(), contentDescription = null) },
                    trailingIcon = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                    onClick = { group = parts },
                )
            }
            bottom?.invoke(close)
        }
    }
}

@Composable
private fun TypeItem(title: String, chosen: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, fontWeight = if (chosen) FontWeight.SemiBold else null) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        trailingIcon = if (chosen) ({ Icon(Icons.Rounded.Check, contentDescription = "Chosen", tint = MaterialTheme.colorScheme.primary) }) else null,
        onClick = onClick,
    )
}

/** The icon of a field type (in the editor; the card draws its own kinds). */
private fun FieldType.icon(): ImageVector = when {
    isCardPart -> Icons.Rounded.CreditCard
    isWiFiPart -> Icons.Rounded.Wifi
    isBankPart -> Icons.Rounded.AccountBalance
    isDocumentPart -> Icons.Rounded.Badge
    isSshPart -> Icons.Rounded.Terminal
    isCryptoPart -> Icons.Rounded.AccountBalanceWallet
    else -> when (this) {
        FieldType.DATE -> Icons.Rounded.Event
        FieldType.UNTIL -> Icons.Rounded.EventBusy
        FieldType.URL -> Icons.Rounded.Link
        FieldType.CODE -> Icons.Rounded.Pin
        FieldType.PASSWORD -> Icons.Rounded.Key
        else -> Icons.Rounded.TextFields
    }
}

/** KeePass's in-memory protection of the value: a chip that is on or off, a closed lock when on. */
@Composable
private fun ProtectedChip(checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text("Protected", maxLines = 1) },
        leadingIcon = {
            Icon(if (checked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize))
        },
    )
}

/**
 * The entry's own one-time code (keething-spec edit.one-time-code AC6; the Mac's `mainCode`): live, as on the card, and
 * said to be new or changed until saved; its settings (digits, period, algorithm: a site says when they are not the
 * usual) under the tune button; back to the code the entry had; removed. A code the entry had and the draft removed
 * leaves a line with Undo.
 */
@Composable
private fun OwnCodeRow(editor: EntryEditor, draft: EntryDraft, update: (EntryDraft) -> Unit) {
    val code = draft.code
    val had = editor.start.code
    if (code == null) {
        if (had == null) return
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("The one-time code is removed with the save.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { update(draft.copy(code = had)) }) { Text("Undo") }
        }
        return
    }
    var settings by rememberSaveable { mutableStateOf(false) }
    val config = remember(code) { code.config() }
    val now = rememberSecondsNow()
    val changed = editor.codeChanged(draft)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("One-time code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CodeValue(TotpDisplay.of(config, now.value))
            }
            IconButton(onClick = { settings = !settings }) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = if (settings) "Hide the code's settings" else "The code's settings",
                    tint = if (settings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (changed && had != null) {
                IconButton(onClick = { update(draft.copy(code = had)) }) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Back to the code the entry had", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = {
                settings = false
                update(draft.copy(code = null))
            }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove the one-time code", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (changed) {
            Text(
                (if (had == null) "New" else "Changed") + ": check that the site accepts this code before saving.",
                Modifier.padding(end = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (settings) {
            Column(Modifier.padding(end = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CodeChoice("Digits", (listOf(6, 7, 8) + code.digits).distinct().sorted(), code.digits, { "$it" }) {
                    update(draft.copy(code = code.copy(digits = it)))
                }
                CodeChoice("Period", (listOf(30, 60) + code.period).distinct().sorted(), code.period, { "$it s" }) {
                    update(draft.copy(code = code.copy(period = it)))
                }
                CodeChoice("Algorithm", TotpConfig.Algorithm.entries, code.algorithm, { it.title }) {
                    update(draft.copy(code = code.copy(algorithm = it)))
                }
                Text(
                    "Change them only when the site says so: a wrong one gives codes the site refuses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal val TotpConfig.Algorithm.title: String
    get() = when (this) {
        TotpConfig.Algorithm.SHA1 -> "SHA-1"
        TotpConfig.Algorithm.SHA256 -> "SHA-256"
        TotpConfig.Algorithm.SHA512 -> "SHA-512"
    }

/** One of a code's settings: its name over a segmented choice. */
@Composable
internal fun <T> CodeChoice(name: String, options: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == chosen,
                    onClick = { onChoose(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { FitText(label(option)) }
            }
        }
    }
}

/** A custom field holding a one-time code's link (AC7): its live code; the link is not edited by hand. */
@Composable
private fun AnotherCode(config: TotpConfig) {
    val now = rememberSecondsNow()
    Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
        CodeValue(TotpDisplay.of(config, now.value))
        Text(
            "A one-time code. To change it, remove the field and add the code again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Adding a one-time code (keething-spec edit.one-time-code AC1–AC5, AC8): the secret a site shows or its whole
 * `otpauth://` link, typed or pasted, or its QR code from an image, the clipboard's image or the camera ([onAddAny]:
 * the first that is a code); why it is not taken, under it.
 */
@Composable
private fun AddOneTimeCodeDialog(
    another: Boolean,
    onAdd: (String) -> String?,
    onAddAny: (List<String>) -> String?,
    onDone: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val add = { given: String -> onAdd(given)?.let { problem = it } ?: onDone() }
    val images = rememberCodeImages(
        onTexts = { texts -> onAddAny(texts)?.let { problem = it } ?: onDone() },
        onProblem = { problem = it },
        onCamera = { problem = null; scanning = true },
    )
    if (scanning) {
        CodeCameraDialog(
            onTexts = { texts -> onAddAny(texts).also { if (it == null) { scanning = false; onDone() } } },
            onClose = { scanning = false },
        )
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (another) "Another one-time code" else "One-time code") },
        text = {
            NoLearningKeyboard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "The secret the site shows when you turn on two-step sign-in (often under “Can’t scan it?”), or its otpauth:// link.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // The key of the code: a secret's text menu, copied through the app.
                    SecretTextMenu(copyable = true) {
                        CopiedThroughTheApp(LocalSecretCopy.current) {
                            TextField(
                                value = text,
                                onValueChange = { text = it; problem = null },
                                label = { Text("Secret or link") },
                                singleLine = true,
                                isError = problem != null,
                                supportingText = problem?.let { { Text(it) } },
                                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = { RevealButton(shown, "secret") { shown = !shown } },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily),
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                                shape = RoundedCornerShape(18.dp),
                                colors = editorFieldColors(MaterialTheme.colorScheme.surfaceContainerHigh),
                                modifier = Modifier.keepOutOfAutofill().fillMaxWidth(),
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The clipboard's text, else its image's QR codes (a screenshot copied).
                        FilledTonalButton(onClick = {
                            when (val clip = clipped(context)) {
                                is Clipped.Image -> images.pasteImage(clip.uri)
                                is Clipped.Text -> if (clip.text.isBlank()) problem = "The clipboard holds no text or image." else add(clip.text)
                            }
                        }) {
                            ButtonLabel("Paste", Icons.Rounded.ContentPaste, iconSize = 18.dp, gap = 8.dp)
                        }
                        FilledTonalButton(onClick = images.chooseFile) {
                            ButtonLabel("Image", Icons.Rounded.Image, iconSize = 18.dp, gap = 8.dp)
                        }
                        FilledTonalButton(onClick = images.openCamera) {
                            ButtonLabel("Scan", Icons.Rounded.QrCodeScanner, iconSize = 18.dp, gap = 8.dp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { add(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** A block of codes pasted or typed (backup codes), added as a numbered list of protected fields. */
@Composable
private fun AddCodesDialog(onAdd: (text: String, prefix: String) -> String?, onDone: () -> Unit) {
    var name by remember { mutableStateOf("Recovery codes") }
    var codes by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add codes") },
        text = {
            NoLearningKeyboard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Paste or type them, one per line. Each becomes a protected field; the card shows them as a numbered list.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    EditorField("Name of the list", name, { name = it; problem = null }, capitalization = KeyboardCapitalization.Sentences)
                    // Backup codes are secrets: copied through the app, no other apps' items.
                    SecretTextMenu(copyable = true) {
                        CopiedThroughTheApp(LocalSecretCopy.current) {
                            TextField(
                                value = codes,
                                onValueChange = { codes = it; problem = null },
                                label = { Text("Codes") },
                                minLines = 4,
                                maxLines = 8,
                                isError = problem != null,
                                supportingText = problem?.let { { Text(it) } },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily),
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                                shape = RoundedCornerShape(18.dp),
                                colors = editorFieldColors(MaterialTheme.colorScheme.surfaceContainerHigh),
                                modifier = Modifier.keepOutOfAutofill().fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(codes, name)?.let { problem = it } ?: onDone() }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** The mock-up's sheet over the editor: "Saving and checking…", then why it was not saved. Not dismissed by a tap. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SaveSheet(phase: SavePhase, onKeepEditing: () -> Unit) {
    val shown = phase == SavePhase.Saving || phase is SavePhase.Failed
    AnimatedVisibility(shown, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        )
    }
    // The last phase stays drawn while the sheet slides out.
    var last by remember { mutableStateOf(phase) }
    if (shown) last = phase
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(shown, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (val current = last) {
                        SavePhase.Editing, SavePhase.Saving, is SavePhase.Saved -> {
                            Text("Saving and checking…", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Writing a new file, reading it back, comparing every entry. The old file is kept as a backup.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        }
                        is SavePhase.Failed -> {
                            SheetTitle(Icons.Rounded.ErrorOutline, "Not saved", MaterialTheme.colorScheme.error)
                            Text(current.problem, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = onKeepEditing, modifier = Modifier.align(Alignment.End)) { Text("Back to editing") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, Modifier.size(28.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.headlineSmall)
    }
}
