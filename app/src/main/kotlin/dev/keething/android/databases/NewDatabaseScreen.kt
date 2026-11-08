package dev.keething.android.databases

import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.ui.secretToAccessibility
import dev.keething.domain.storage.StoragePlace
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.SecretTextMenu
import dev.keething.android.ui.readableInset
import dev.keething.domain.encryption.DatabaseEncryption
import dev.keething.domain.encryption.DatabaseEncryption.Cipher
import dev.keething.domain.encryption.DatabaseEncryption.KeyDerivation
import dev.keething.generator.Strength
import kotlin.math.roundToInt

/**
 * New database (SPEC §6.2, agreed on the canvas, rows "Databases: Settings, a database's page, New database"): the form,
 * its Encryption page, and Creating…. Back leaves the Encryption page, then the form (not while the file is written).
 */
@Composable
internal fun NewDatabaseScreen(model: NewDatabaseModel, onClose: () -> Unit) {
    BackHandler {
        when {
            model.isWriting -> Unit
            model.page == NewDatabaseModel.Page.ENCRYPTION -> model.showPage(NewDatabaseModel.Page.BASIC)
            else -> onClose()
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        if (model.page == NewDatabaseModel.Page.ENCRYPTION) EncryptionPage(model) else Form(model, onClose)
    }
    if (model.phase is NewDatabaseModel.Phase.Creating) CreatingDialog(model)
}

@Composable
private fun Form(model: NewDatabaseModel, onClose: () -> Unit) {
    // Coming back from another app: a file made or moved.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.lookAgain() }
    // Each place chosen is granted (keething-spec databases.saf AC11); what the form does not use is given back.
    val pickFolder = rememberFolderPicker { model.chooseFolder(model.folderGranted(GrantedPlace(it.id, it.place))) }
    val pickKeyFolder = rememberFolderPicker { model.chooseKeyFile(NewDatabaseModel.KeyFileChoice.New(model.folderGranted(GrantedPlace(it.id, it.place)))) }
    val pickKeyFile = rememberKeyFilePicker { model.chooseKeyFile(NewDatabaseModel.KeyFileChoice.Existing(model.keyFileGranted(it))) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = readableInset()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
            Spacer(Modifier.width(4.dp))
            Text("New database", style = MaterialTheme.typography.titleLarge)
        }

        // The name and where it goes.
        FieldTextField(
            value = model.name,
            onValueChange = model::changeName,
            label = "Name",
            placeholder = "Personal, Work, Family…",
            isError = model.nameIsTaken,
        )
        SaveIn(model) { pickFolder(model.folder?.let(StoragePlace::ofPath)) }
        Text(
            model.pathText,
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (model.nameIsTaken) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
        )

        Spacer(Modifier.height(4.dp))
        PasswordFields(model.fields)
        model.strength?.let { StrengthBar(it) }
        val suggestion = model.suggestion
        if (suggestion == null) {
            TextButton(onClick = model::suggest) { ButtonLabel("Suggest a passphrase", Icons.Rounded.Add, iconSize = 18.dp, gap = 8.dp) }
        } else {
            SuggestionCard(model.fields, suggestion)
        }
        Text(
            "Nobody can recover a forgotten master password, not even Keething.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))
        // A key file is kept apart from the database: the pickers start in Documents.
        KeyFileSection(model, onMake = { pickKeyFolder(DOCUMENTS) }, onHave = { pickKeyFile(DOCUMENTS) })
        if (model.offersFingerprint) {
            SwitchRow(Icons.Rounded.Fingerprint, "Unlock it with the fingerprint", null, model.useFingerprint) { model.useFingerprint = it }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.extraColors.row).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(model.encryptionLine, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { model.showPage(NewDatabaseModel.Page.ENCRYPTION) }) { Text("Change…") }
        }

        model.problem?.let { problem ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        // Read once a frame: each one looks at the files.
        val blockers = model.blockers
        val why = blockers.firstOrNull()?.takeIf { it != model.problem }
        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                why.orEmpty(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { model.create() }, enabled = model.phase == NewDatabaseModel.Phase.Editing && blockers.isEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Create") }
        }
    }
}

@Composable
private fun FieldTextField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String, isError: Boolean) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.keepOutOfAutofill().fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(20.dp),
        colors = fieldColors(),
    )
}

@Composable
internal fun fieldColors() = TextFieldDefaults.colors(
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    errorContainerColor = MaterialTheme.colorScheme.errorContainer,
)

/** Save in: the list's granted folders (the current one's first), Other folder…; with none yet, Choose a folder…. */
@Composable
private fun SaveIn(model: NewDatabaseModel, onOther: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = model.folders.firstOrNull { it.path == model.folder }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                // Nothing granted yet: the picker at once.
                .clickable { if (model.folders.isEmpty()) onOther() else open = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text("Save in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(chosen?.title ?: model.folder ?: "Choose a folder…", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            Icon(Icons.Rounded.ExpandMore, contentDescription = "Choose the folder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            model.folders.forEach { folder ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(folder.title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            folder.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                    onClick = {
                        open = false
                        model.chooseFolder(folder.path)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Other folder…") },
                leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                onClick = {
                    open = false
                    onOther()
                },
            )
        }
    }
}

/** The master password twice, one eye for both (else a typo goes unseen and the database is lost). */
@Composable
internal fun PasswordFields(model: MasterPasswordFields, label: String = "Master password") {
    val keyboard = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Next,
    )
    val style = if (model.reveals) MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily) else MaterialTheme.typography.bodyLarge
    val mode = if (model.reveals) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped
    SecretTextMenu(copyable = false) {
        SecureTextField(
            state = model.password,
            modifier = Modifier.keepOutOfAutofill().secretToAccessibility(model.password.text.length, shown = model.reveals).fillMaxWidth(),
            label = { Text(label) },
            textStyle = style,
            textObfuscationMode = mode,
            trailingIcon = {
                IconButton(onClick = { model.reveals = !model.reveals }) {
                    Icon(
                        if (model.reveals) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (model.reveals) "Hide both passwords" else "Show both passwords",
                    )
                }
            },
            keyboardOptions = keyboard,
            shape = RoundedCornerShape(20.dp),
            colors = fieldColors(),
        )
    }
    SecretTextMenu(copyable = false) {
        SecureTextField(
            state = model.again,
            modifier = Modifier.keepOutOfAutofill().secretToAccessibility(model.again.text.length, shown = model.reveals).fillMaxWidth(),
            label = { Text("Again") },
            textStyle = style,
            textObfuscationMode = mode,
            trailingIcon = {
                when (model.match) {
                    MasterPasswordFields.Match.MATCHES -> Icon(Icons.Rounded.CheckCircle, contentDescription = "The passwords match", tint = MaterialTheme.colorScheme.primary)
                    MasterPasswordFields.Match.DIFFERS -> Icon(Icons.Rounded.Cancel, contentDescription = "The passwords differ", tint = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
            },
            keyboardOptions = keyboard.copy(imeAction = ImeAction.Done),
            shape = RoundedCornerShape(20.dp),
            colors = fieldColors(),
        )
    }
}

/** Four segments and the words: weak and fair in the warning colour, strong in the good one. */
@Composable
internal fun StrengthBar(strength: MasterPasswordFields.StrengthLine) {
    val lit = if (strength.verdict == Strength.STRONG) MaterialTheme.colorScheme.primary else MaterialTheme.extraColors.warn
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (i < strength.level) lit else MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Text(
            strength.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (strength.verdict == Strength.WEAK) MaterialTheme.extraColors.warn else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The passphrase shown once in clear, its words and bits; fewer, more, Another, Use this. */
@Composable
internal fun SuggestionCard(model: MasterPasswordFields, suggestion: String) {
    val bits = dev.keething.generator.PasswordGenerator.masterPassphraseEntropy(model.passphraseWords).toInt()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(suggestion, style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonospaceFamily), color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            "EFF words, one with capitals, a digit and a symbol: about $bits bits. Shown once: write it down.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.changeWords(model.passphraseWords - 1) }) { Icon(Icons.Rounded.Remove, contentDescription = "Fewer words") }
            Text("${model.passphraseWords} words", style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { model.changeWords(model.passphraseWords + 1) }) { Icon(Icons.Rounded.Add, contentDescription = "More words") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = model::suggest) { Text("Another") }
            FilledTonalButton(onClick = model::useSuggestion) { Text("Use this") }
        }
    }
}

/** Also require a key file: a new one (made at Create) or one the user has; the warnings and what losing it costs. */
@Composable
private fun KeyFileSection(model: NewDatabaseModel, onMake: () -> Unit, onHave: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.extraColors.row)) {
        SwitchRow(Icons.Rounded.Key, "Also require a key file", "Optional", model.requiresKeyFile, inCard = true, onChange = model::requireKeyFile)
        if (model.requiresKeyFile) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val name = model.keyFileName
                if (name == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onMake) { ButtonLabel("Make a new one…", Icons.Rounded.NoteAdd, iconSize = 18.dp, gap = 8.dp) }
                        TextButton(onClick = onHave) { Text("Use one I have…") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            model.keyFileDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        TextButton(onClick = model::forgetKeyFile) { Text("Change…") }
                    }
                }
                model.keyFileProblem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                model.keyFileWarning?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.extraColors.warn)
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extraColors.warn)
                    }
                }
                Text(
                    "Lose the key file and the database never opens again. Keep a copy apart from it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    note: String?,
    checked: Boolean,
    inCard: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (inCard) Modifier else Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.extraColors.row))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Change…: format, cipher, key derivation, memory and lanes (Argon2), time to open; measured on this phone. */
@Composable
private fun EncryptionPage(model: NewDatabaseModel) {
    val e = model.encryption
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = readableInset()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.showPage(NewDatabaseModel.Page.BASIC) }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to New database")
            }
            Spacer(Modifier.width(4.dp))
            Text("Encryption", style = MaterialTheme.typography.titleLarge)
        }
        Labelled("Format") { Text("KDBX 4.0", style = MaterialTheme.typography.bodyLarge) }
        Labelled("Cipher") {
            Choices(Cipher.entries, e.cipher, { it.label }) { model.changeEncryption(e.copy(cipher = it)) }
        }
        Labelled("Key derivation") {
            Choices(KeyDerivation.entries, e.keyDerivation, { it.label }) { model.changeEncryption(e.copy(keyDerivation = it)) }
        }
        if (e.keyDerivation.isArgon2) {
            Stepper("Memory", "${e.memoryMiB} MiB", "Less memory", "More memory",
                onLess = { model.changeEncryption(e.copy(memoryMiB = (e.memoryMiB / 2).coerceIn(DatabaseEncryption.MEMORY_RANGE))) },
                onMore = { model.changeEncryption(e.copy(memoryMiB = (e.memoryMiB * 2).coerceIn(DatabaseEncryption.MEMORY_RANGE))) },
            )
            Stepper("Lanes", "${e.lanes}", "Fewer lanes", "More lanes",
                onLess = { model.changeEncryption(e.copy(lanes = (e.lanes - 1).coerceIn(DatabaseEncryption.LANE_RANGE))) },
                onMore = { model.changeEncryption(e.copy(lanes = (e.lanes + 1).coerceIn(DatabaseEncryption.LANE_RANGE))) },
            )
        }
        Labelled("Time to open") {
            Text(NewDatabaseModel.seconds(e.seconds), style = MaterialTheme.typography.titleMedium)
            Slider(
                value = (e.seconds * 10).toFloat(),
                onValueChange = { model.changeEncryption(e.copy(seconds = it.roundToInt() / 10.0)) },
                valueRange = 1f..50f,
                steps = 48,
            )
            Row {
                Text("0.1 s", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("5 s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (model.isMeasuring) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(model.measuredLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = model::recommend, enabled = !model.isRecommended, modifier = Modifier.padding(bottom = 24.dp)) {
            Text("Back to recommended")
        }
    }
}

@Composable
private fun Labelled(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun <T> Choices(options: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == chosen,
                onClick = { onChoose(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

@Composable
private fun Stepper(title: String, value: String, less: String, more: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        IconButton(onClick = onLess) { Icon(Icons.Rounded.Remove, contentDescription = less) }
        Text(value, Modifier.width(88.dp), style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onMore) { Icon(Icons.Rounded.Add, contentDescription = more) }
    }
}

/** Creating…: measuring can be cancelled, writing cannot (the dialog stays until it is done). */
@Composable
private fun CreatingDialog(model: NewDatabaseModel) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { CircularProgressIndicator(Modifier.size(32.dp)) },
        title = { Text(model.creatingTitle) },
        text = { Text(model.creatingDetail) },
        confirmButton = {},
        dismissButton = {
            if (model.canCancel) TextButton(onClick = model::cancelCreating) { Text("Cancel") }
        },
    )
}

/** Where the key file pickers start: the phone's Documents folder. */
internal val DOCUMENTS = StoragePlace(StoragePlace.PRIMARY, "Documents")
