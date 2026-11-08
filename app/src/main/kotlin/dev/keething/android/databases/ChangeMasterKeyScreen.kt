package dev.keething.android.databases

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.SecretTextMenu
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.secretToAccessibility
import dev.keething.android.ui.segmentShape
import dev.keething.android.databases.ChangeMasterKeyModel.KeyFileChoice
import dev.keething.android.databases.ChangeMasterKeyModel.PasswordChoice

/**
 * Change master key… (SPEC §6.2; keething-spec databases.change-master-key; agreed on the canvas "Database name, colour
 * and master key"): the key it is open with first, then the new password and key file, then what changed and what did
 * not. Back closes it (not while the change is written).
 */
@Composable
internal fun ChangeMasterKeyScreen(model: ChangeMasterKeyModel, onClose: () -> Unit) {
    BackHandler { onClose() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
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
                IconButton(onClick = onClose, enabled = !model.changing) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                Spacer(Modifier.width(4.dp))
                Text("Change master key", style = MaterialTheme.typography.titleLarge)
            }
            when (model.step) {
                ChangeMasterKeyModel.Step.CURRENT -> CurrentKey(model)
                ChangeMasterKeyModel.Step.NEW -> NewKey(model)
                ChangeMasterKeyModel.Step.DONE -> Changed(model, onClose)
            }
        }
    }
}

/** The key it is open with, typed (keething-spec databases.change-master-key AC1). */
@Composable
private fun CurrentKey(model: ChangeMasterKeyModel) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(model.label, style = MaterialTheme.typography.titleMedium)
        Text(model.fileText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        if (model.currentKeyFile != null) "First its master password as it is now, to show you know it. Nothing typed: its key file alone." else "First its master password as it is now, to show you know it.",
        Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
    SecretTextMenu(copyable = false) {
        SecureTextField(
            state = model.current,
            modifier = Modifier.keepOutOfAutofill().secretToAccessibility(model.current.text.length, shown = model.revealsCurrent).fillMaxWidth(),
            label = { Text("Current master password") },
            textStyle = if (model.revealsCurrent) MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily) else MaterialTheme.typography.bodyLarge,
            textObfuscationMode = if (model.revealsCurrent) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
            isError = model.problem != null,
            trailingIcon = {
                IconButton(onClick = { model.revealsCurrent = !model.revealsCurrent }) {
                    Icon(
                        if (model.revealsCurrent) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (model.revealsCurrent) "Hide the password" else "Show the password",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            onKeyboardAction = { model.confirmCurrent() },
            shape = RoundedCornerShape(20.dp),
            colors = fieldColors(),
        )
    }
    model.problem?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    model.currentKeyFile?.let { key ->
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Key, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("With its key file, ${key.name} in ${key.placeText}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { model.confirmCurrent() }, enabled = model.canConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        if (model.checking) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Checking…")
        } else {
            Text("Continue")
        }
    }
}

/** The new password and key file (AC2, AC3), and Change. */
@Composable
private fun NewKey(model: ChangeMasterKeyModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.lookAgain() }
    // Each place chosen is granted (keething-spec databases.saf AC11); what the form does not use is given back.
    val pickKeyFolder = rememberFolderPicker { model.chooseKeyFile(KeyFileChoice.New(model.folderGranted(GrantedPlace(it.id, it.place)))) }
    val pickKeyFile = rememberKeyFilePicker { model.chooseKeyFile(KeyFileChoice.Existing(model.keyFileGranted(it))) }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.extraColors.row).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Its current key, confirmed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SectionTitle("Password")
    val choices = listOf(PasswordChoice.KEEP to "Keep it", PasswordChoice.NEW to "New", PasswordChoice.NONE to "None")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (choice, label) ->
            SegmentedButton(
                selected = model.passwordChoice == choice,
                onClick = { model.choosePassword(choice) },
                enabled = choice != PasswordChoice.NONE || model.noneAllowed,
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
            ) { Text(label) }
        }
    }
    if (model.passwordChoice == PasswordChoice.NEW) {
        val fields = model.fields
        PasswordFields(fields, label = "New master password")
        fields.strength?.let { StrengthBar(it) }
        val suggestion = fields.suggestion
        if (suggestion == null) {
            TextButton(onClick = fields::suggest) { ButtonLabel("Suggest a passphrase", Icons.Rounded.Add, iconSize = 18.dp, gap = 8.dp) }
        } else {
            SuggestionCard(fields, suggestion)
        }
    }
    Text(
        when (model.passwordChoice) {
            PasswordChoice.KEEP -> if (model.keepsNoPassword) "It stays without a password: its key file alone opens it." else "The password stays as it is."
            PasswordChoice.NEW -> "Nobody can recover a forgotten master password, not even Keething."
            PasswordChoice.NONE -> "No password: the key file alone opens it. Whoever has the file and the key file has everything."
        },
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionTitle("Key file")
    KeyFileChoices(model, onMake = { pickKeyFolder(DOCUMENTS) }, onHave = { pickKeyFile(DOCUMENTS) })

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.extraColors.row).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Devices, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Every device that opens ${model.fileName} will need the new key. The file is backed up first; if anything fails, it stays as it is.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    blockers.firstOrNull()?.let {
        Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Button(
        onClick = { model.changeKey() },
        enabled = !model.changing && blockers.isEmpty(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 8.dp),
    ) {
        if (model.changing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Changing…")
        } else {
            Text("Change master key")
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** Keep, Make a new one…, Use one I have…, Remove it (None for one without), as one choice; the warnings under it. */
@Composable
private fun KeyFileChoices(model: ChangeMasterKeyModel, onMake: () -> Unit, onHave: () -> Unit) {
    val current = model.currentKeyFile
    val chosen = model.keyFile
    val rows = buildList {
        if (current != null) add(Triple("Keep ${current.name}", current.placeText, chosen == KeyFileChoice.Keep) to { model.chooseKeyFile(KeyFileChoice.Keep) })
        add(Triple("Make a new one…", if (chosen is KeyFileChoice.New) model.keyFileText ?: "…" else "A .keyx in a folder you pick, not beside the database", chosen is KeyFileChoice.New) to onMake)
        add(Triple("Use one I have…", if (chosen is KeyFileChoice.Existing) model.keyFileText ?: "…" else "Chosen from your files; never changed", chosen is KeyFileChoice.Existing) to onHave)
        add(Triple(if (current != null) "Remove it" else "None", if (current != null) "It opens with the password alone" else "It opens with the password alone, as now", chosen == KeyFileChoice.None) to { model.chooseKeyFile(KeyFileChoice.None) })
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, (row, onClick) ->
            val (title, detail, selected) = row
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(segmentShape(index, rows.size, outer = 18.dp))
                    .background(MaterialTheme.extraColors.row)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    model.keyFileProblem?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    model.keyFileWarning?.let {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.extraColors.warn)
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extraColors.warn)
        }
    }
    if (chosen is KeyFileChoice.New) {
        Text(
            "Lose the key file and the database never opens again. Keep a copy apart from it.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.extraColors.warn,
        )
    }
}

/** What changed, what the other devices do now, and the copies made before (AC6–AC8). */
@Composable
private fun Changed(model: ChangeMasterKeyModel, onClose: () -> Unit) {
    Box(
        Modifier.padding(top = 8.dp).size(72.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Text("Master key changed", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
    val password = when {
        !model.passwordAfter -> null
        model.passwordChoice == PasswordChoice.KEEP -> "its password"
        else -> "the new password"
    }
    val opensWith = listOfNotNull(password, model.keyFileAfter)
    Text("${model.fileName} now opens with ${opensWith.joinToString(" and ")}.", style = MaterialTheme.typography.bodyLarge)
    model.fingerprintProblem?.let {
        Text("Its new password could not be stored for the fingerprint: $it. It is stored at the next unlock with the password.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    Text(
        "Your other devices ask for the new key once the file reaches them; a fingerprint stored there stops working until the new password is typed.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.extraColors.warnContainer).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.extraColors.onWarnContainer)
        Text(
            "Copies of the file made before still open with the old key: the backups on this phone and elsewhere, other devices' copies, a sync's old versions (Syncthing's .stversions). After a leak, delete those you can.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.extraColors.onWarnContainer,
        )
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 8.dp)) { Text("Done") }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 4.dp, top = 12.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
