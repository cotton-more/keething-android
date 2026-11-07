package dev.keething.android.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.keething.android.databases.databaseColors
import dev.keething.android.editor.Clipped
import dev.keething.android.editor.CodeCameraDialog
import dev.keething.android.editor.CodeChoice
import dev.keething.android.editor.NewEntryDatabase
import dev.keething.android.editor.clipped
import dev.keething.android.editor.rememberCodeImages
import dev.keething.android.editor.title
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.CodeValue
import dev.keething.android.ui.NoLearningKeyboard
import dev.keething.android.ui.SecretTextMenu
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.card.TotpDisplay
import dev.keething.card.editing.CodeDraft
import dev.keething.card.editing.CodeEdits
import dev.keething.card.editing.ReadCode
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.Entry
import dev.keething.vault.databases.DatabaseId
import java.time.Instant

/**
 * Edit code (keething-spec app.codes-view AC4; the canvas's "Edit code"): the code live as it would be, a further code's
 * name, its secret kept or replaced (pasted, typed, read from an image or the camera, as the editor takes one), its
 * digits, period and algorithm. [onSave] says why it cannot be saved, or null once it is under way. The secret itself is
 * never shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditCodeSheet(tile: CodeTile, now: State<Instant>, onSave: (TotpConfig, name: String?) -> String?, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(CodeDraft.of(tile.totp)) }
    var name by remember { mutableStateOf(tile.fieldName.orEmpty()) }
    var replaced by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val take = { read: ReadCode ->
        when (read) {
            is ReadCode.Code -> {
                draft = CodeDraft.of(read.config)
                read.config.secret.wipe()
                replaced = true
                null
            }
            is ReadCode.Refused -> read.problem
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Edit code", style = MaterialTheme.typography.headlineSmall)
                Text("${tile.title} · ${tile.subtitle}".trimEnd(' ', '·'), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LiveCode(draft, now)
            if (tile.further) {
                NoLearningKeyboard {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; problem = null },
                        label = { Text("Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.keepOutOfAutofill().fillMaxWidth(),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (replaced) "Secret · replaced: the code above is the new one" else "Secret · kept as it is, or replaced from",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CodeInput(large = false, onText = { take(CodeEdits.read(it)) }, onTexts = { take(CodeEdits.read(it)) })
            }
            CodeChoice("Digits", (listOf(6, 7, 8) + draft.digits).distinct().sorted(), draft.digits, { "$it" }) { draft = draft.copy(digits = it) }
            CodeChoice("Period", (listOf(30, 60) + draft.period).distinct().sorted(), draft.period, { "$it s" }) { draft = draft.copy(period = it) }
            CodeChoice("Algorithm", TotpConfig.Algorithm.entries, draft.algorithm, { it.title }) { draft = draft.copy(algorithm = it) }
            problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = {
                    val config = draft.config()
                    problem = onSave(config, name.takeIf { tile.further })
                    config.secret.wipe()
                    if (problem == null) onDismiss()
                }) { Text("Save") }
            }
        }
    }
}

/**
 * ＋ Add a code (AC7, AC8; the canvas's "Add a code"): first the code, from the camera, an image, the clipboard or typed;
 * then where it goes — the database (asked only while two or more take entries), the entry of that title when the
 * database has one, else a new entry with the link's issuer and account as its title and username. Nothing is written
 * until Add code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddCodeSheet(
    places: List<NewEntryDatabase>,
    start: DatabaseId?,
    now: State<Instant>,
    onAdd: (database: NewEntryDatabase, into: Entry?, title: String, username: String, config: TotpConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var read by remember { mutableStateOf<ReadCode.Code?>(null) }
    // A code read and not added: its key goes with the sheet.
    DisposableEffect(Unit) { onDispose { read?.config?.secret?.wipe() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val code = read
            if (code == null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Add a code", style = MaterialTheme.typography.headlineSmall)
                    Text("Scan the QR code the site shows, or paste its setup key.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val take = { given: ReadCode ->
                    when (given) {
                        is ReadCode.Code -> null.also { read = given }
                        is ReadCode.Refused -> given.problem
                    }
                }
                CodeInput(large = true, onText = { take(CodeEdits.read(it)) }, onTexts = { take(CodeEdits.read(it)) })
                Text("Read on the phone; nothing is sent anywhere.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("Cancel") } }
            } else {
                WhereTheCodeGoes(code, places, start, now, onAdd = { database, into, title, username ->
                    onAdd(database, into, title, username, code.config)
                    onDismiss()
                }, onDismiss = onDismiss)
            }
        }
    }
}

/** The second step of Add a code: the code read, its database, the entry it goes into or the new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhereTheCodeGoes(
    code: ReadCode.Code,
    places: List<NewEntryDatabase>,
    start: DatabaseId?,
    now: State<Instant>,
    onAdd: (database: NewEntryDatabase, into: Entry?, title: String, username: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(code.label.issuer) }
    var username by remember { mutableStateOf(code.label.account) }
    var chosen by remember { mutableStateOf(start ?: places.first().id) }
    val database = places.firstOrNull { it.id == chosen } ?: places.first()
    val match = CodeEdits.sameTitle(database.vault.entries, title)
    // An entry of that title is offered first (AC7); the choice is the user's once made.
    var newEntry by remember(match?.id, database.id) { mutableStateOf(match == null) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Add a code", style = MaterialTheme.typography.headlineSmall)
        val whom = listOf(code.label.issuer, code.label.account).filter { it.isNotEmpty() }
        Text(
            if (whom.isEmpty()) "A setup key without a name: give the entry a title." else "Read: ${whom.joinToString(", ")}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LiveCode(remember(code) { CodeDraft.of(code.config) }, now)
    // AC8: with two or more databases taking entries, which one; with one, nothing asked.
    if (places.size >= 2) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Into", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (place in places) {
                    FilterChip(
                        selected = place.id == database.id,
                        onClick = { chosen = place.id },
                        label = { Text(place.mark.label.full, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(databaseColors(place.mark.colorIndex).dot)) },
                    )
                }
            }
        }
    }
    if (match != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val where = if (places.size >= 2) database.mark.label.full else "the database"
            Text("“${match.displayTitle}” is already in $where", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val into = if (match.totp == null) "it has no code yet" else "as its next code"
            Choice("Add to “${match.displayTitle}”", listOf(match.username, into).filter { it.isNotEmpty() }.joinToString(" · "), selected = !newEntry) { newEntry = false }
            Choice("A new entry", "Title, username and this code", selected = newEntry) { newEntry = true }
        }
    }
    if (newEntry || match == null) {
        NoLearningKeyboard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.keepOutOfAutofill().fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.keepOutOfAutofill().fillMaxWidth(),
                )
            }
        }
    }
    val intoMatch = match != null && !newEntry
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Button(
            onClick = { onAdd(database, match.takeIf { intoMatch }, title, username) },
            enabled = intoMatch || title.isNotBlank(),
        ) { Text("Add code") }
    }
}

/** A radio row: where the code goes. */
@Composable
private fun Choice(label: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.extraColors.row)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The code a draft makes, live; never its secret. */
@Composable
private fun LiveCode(draft: CodeDraft, now: State<Instant>) {
    val config = draft.config()
    val display = TotpDisplay.of(config, now.value)
    config.secret.wipe()
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.extraColors.row).padding(horizontal = 16.dp, vertical = 12.dp)) {
        CodeValue(display)
    }
}

/**
 * Where a code comes from (edit.one-time-code AC1–AC4, as the editor takes one): the camera, an image (SVG too), the
 * clipboard (its text, or its image's QR codes) or typed. [onText] and [onTexts] take it, or say why not. [large]: the
 * Add sheet's three tiles; else a row of buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeInput(large: Boolean, onText: (String) -> String?, onTexts: (List<String>) -> String?) {
    var typed by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val images = rememberCodeImages(
        onTexts = { texts -> problem = onTexts(texts) },
        onProblem = { problem = it },
        onCamera = {
            problem = null
            scanning = true
        },
    )
    if (scanning) {
        CodeCameraDialog(onTexts = { texts -> onTexts(texts).also { if (it == null) scanning = false } }, onClose = { scanning = false })
    }
    val paste = {
        when (val clip = clipped(context)) {
            is Clipped.Image -> images.pasteImage(clip.uri)
            is Clipped.Text -> problem = if (clip.text.isBlank()) "The clipboard holds no text or image." else onText(clip.text)
        }
    }
    val sources = listOf(
        Triple("Camera", Icons.Rounded.QrCodeScanner, images.openCamera),
        Triple("Image", Icons.Rounded.Image, images.chooseFile),
        Triple("Paste", Icons.Rounded.ContentPaste, paste),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (large) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, icon, act) in sources) SourceTile(label, icon, Modifier.weight(1f), act)
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, icon, act) in sources) {
                    Surface(onClick = act, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.height(40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(icon, contentDescription = null, Modifier.size(18.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        // The key of the code: never learnt by the keyboard, never offered to copy.
        NoLearningKeyboard {
            SecretTextMenu(copyable = false) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = {
                        typed = it
                        problem = null
                    },
                    label = { Text(if (large) "Or type the setup key or otpauth:// link" else "Or type the secret or link") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { shown = !shown }) {
                                Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (shown) "Hide the secret" else "Show the secret")
                            }
                            TextButton(onClick = { problem = onText(typed).also { if (it == null) typed = "" } }, enabled = typed.isNotBlank()) { Text("Use") }
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.keepOutOfAutofill().fillMaxWidth(),
                )
            }
        }
    }
}

/** One of the Add sheet's three sources, large in thumb reach. */
@Composable
private fun SourceTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer, textAlign = TextAlign.Center)
    }
}
