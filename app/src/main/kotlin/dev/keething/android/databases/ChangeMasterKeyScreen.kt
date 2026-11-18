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
import androidx.compose.ui.res.stringResource
import dev.keething.android.R
import dev.keething.android.ui.resolve

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
                IconButton(onClick = onClose, enabled = !model.changing) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.databases_close)) }
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.databases_change_key_title), style = MaterialTheme.typography.titleLarge)
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
        stringResource(if (model.currentKeyFile != null) R.string.databases_change_key_first_with_file else R.string.databases_change_key_first),
        Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
    SecretTextMenu(copyable = false) {
        SecureTextField(
            state = model.current,
            modifier = Modifier.keepOutOfAutofill().secretToAccessibility(model.current.text.length, shown = model.revealsCurrent).fillMaxWidth(),
            label = { Text(stringResource(R.string.databases_change_key_current_password)) },
            textStyle = if (model.revealsCurrent) MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily) else MaterialTheme.typography.bodyLarge,
            textObfuscationMode = if (model.revealsCurrent) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
            isError = model.problem != null,
            trailingIcon = {
                IconButton(onClick = { model.revealsCurrent = !model.revealsCurrent }) {
                    Icon(
                        if (model.revealsCurrent) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (model.revealsCurrent) R.string.databases_hide_password else R.string.databases_show_password),
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
    model.problem?.let { Text(it.resolve(), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    model.currentKeyFile?.let { key ->
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Key, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.databases_change_key_with_key_file, key.name, key.placeText), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { model.confirmCurrent() }, enabled = model.canConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        if (model.checking) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.databases_change_key_checking))
        } else {
            Text(stringResource(R.string.databases_continue))
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
        Text(stringResource(R.string.databases_change_key_confirmed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SectionTitle(stringResource(R.string.databases_change_key_password))
    val choices = listOf(
        PasswordChoice.KEEP to R.string.databases_change_key_password_keep,
        PasswordChoice.NEW to R.string.databases_change_key_password_new,
        PasswordChoice.NONE to R.string.databases_change_key_password_none,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (choice, label) ->
            SegmentedButton(
                selected = model.passwordChoice == choice,
                onClick = { model.choosePassword(choice) },
                enabled = choice != PasswordChoice.NONE || model.noneAllowed,
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
            ) { Text(stringResource(label)) }
        }
    }
    if (model.passwordChoice == PasswordChoice.NEW) {
        val fields = model.fields
        PasswordFields(fields, label = stringResource(R.string.databases_new_master_password))
        fields.strength?.let { StrengthBar(it) }
        val suggestion = fields.suggestion
        if (suggestion == null) {
            TextButton(onClick = fields::suggest) { ButtonLabel(stringResource(R.string.databases_suggest_passphrase), Icons.Rounded.Add, iconSize = 18.dp, gap = 8.dp) }
        } else {
            SuggestionCard(fields, suggestion)
        }
    }
    Text(
        stringResource(
            when (model.passwordChoice) {
                PasswordChoice.KEEP -> if (model.keepsNoPassword) R.string.databases_change_key_keeps_none else R.string.databases_change_key_keeps_password
                PasswordChoice.NEW -> R.string.databases_password_not_recoverable
                PasswordChoice.NONE -> R.string.databases_change_key_no_password
            },
        ),
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionTitle(stringResource(R.string.databases_change_key_key_file))
    KeyFileChoices(model, onMake = { pickKeyFolder(DOCUMENTS) }, onHave = { pickKeyFile(DOCUMENTS) })

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.extraColors.row).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Devices, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.databases_change_key_every_device, model.fileName),
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
            Text(problem.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    // Read once a frame: each one looks at the files.
    val blockers = model.blockers
    blockers.firstOrNull()?.let {
        Text(it.resolve(), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Button(
        onClick = { model.changeKey() },
        enabled = !model.changing && blockers.isEmpty(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 8.dp),
    ) {
        if (model.changing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.databases_change_key_changing))
        } else {
            Text(stringResource(R.string.databases_change_key_title))
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** Keep, Make a new one…, Use one I have…, Remove it (None for one without), as one choice; the warnings under it. */
@Composable
private fun KeyFileChoices(model: ChangeMasterKeyModel, onMake: () -> Unit, onHave: () -> Unit) {
    val current = model.currentKeyFile
    val chosen = model.keyFile
    val keyFileText = model.keyFileText?.resolve() ?: "…"
    val keep = current?.let { stringResource(R.string.databases_change_key_file_keep, it.name) }
    val make = stringResource(R.string.databases_change_key_file_make)
    val makeDetail = if (chosen is KeyFileChoice.New) keyFileText else stringResource(R.string.databases_change_key_file_make_detail)
    val have = stringResource(R.string.databases_change_key_file_have)
    val haveDetail = if (chosen is KeyFileChoice.Existing) keyFileText else stringResource(R.string.databases_change_key_file_have_detail)
    val none = stringResource(if (current != null) R.string.databases_change_key_file_remove else R.string.databases_change_key_file_none)
    val noneDetail = stringResource(if (current != null) R.string.databases_change_key_file_remove_detail else R.string.databases_change_key_file_none_detail)
    val rows = buildList {
        if (current != null && keep != null) add(Triple(keep, current.placeText, chosen == KeyFileChoice.Keep) to { model.chooseKeyFile(KeyFileChoice.Keep) })
        add(Triple(make, makeDetail, chosen is KeyFileChoice.New) to onMake)
        add(Triple(have, haveDetail, chosen is KeyFileChoice.Existing) to onHave)
        add(Triple(none, noneDetail, chosen == KeyFileChoice.None) to { model.chooseKeyFile(KeyFileChoice.None) })
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
    model.keyFileProblem?.let { Text(it.resolve(), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    model.keyFileWarning?.let {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.extraColors.warn)
            Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extraColors.warn)
        }
    }
    if (chosen is KeyFileChoice.New) {
        Text(
            stringResource(R.string.databases_key_file_lose),
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
    Text(stringResource(R.string.databases_change_key_done_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
    val keyFile = model.keyFileAfter
    val kept = model.passwordChoice == PasswordChoice.KEEP
    val opensWith = when {
        !model.passwordAfter -> stringResource(R.string.databases_change_key_opens_with_key_file, model.fileName, keyFile.orEmpty())
        keyFile == null -> stringResource(if (kept) R.string.databases_change_key_opens_with_its_password else R.string.databases_change_key_opens_with_new_password, model.fileName)
        else -> stringResource(
            if (kept) R.string.databases_change_key_opens_with_its_password_and_key_file else R.string.databases_change_key_opens_with_new_password_and_key_file,
            model.fileName,
            keyFile,
        )
    }
    Text(opensWith, style = MaterialTheme.typography.bodyLarge)
    model.fingerprintProblem?.let {
        Text(stringResource(R.string.databases_change_key_fingerprint_problem, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    Text(
        stringResource(R.string.databases_change_key_other_devices),
        style = MaterialTheme.typography.bodyLarge,
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.extraColors.warnContainer).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.extraColors.onWarnContainer)
        Text(
            stringResource(R.string.databases_change_key_old_copies),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.extraColors.onWarnContainer,
        )
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 8.dp)) { Text(stringResource(R.string.databases_done)) }
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
