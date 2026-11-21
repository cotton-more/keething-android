package dev.keething.android.databases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.keething.android.R
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.ui.SecretTextMenu
import dev.keething.android.ui.UiText
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.ui.resolve
import dev.keething.android.ui.secretToAccessibility
import dev.keething.android.ui.uiText
import dev.keething.android.unlock.describe
import dev.keething.domain.secret.Secret
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.biometric.PasswordTurnOn
import kotlinx.coroutines.launch

/**
 * Use fingerprint turned on while the database is open (keething-spec unlock.biometric AC1, AC11): its master password,
 * checked against the key it is open with and stored, so the next unlock is the fingerprint's. A wrong one is said at
 * the field and changes nothing; Cancel leaves the switch off. What was typed goes when the dialog does.
 */
@Composable
internal fun FingerprintPasswordDialog(biometric: BiometricUnlock, name: String, onDone: (failure: String?) -> Unit, onCancel: () -> Unit) {
    val password = rememberTextFieldState()
    var reveals by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<UiText?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { password.clearText() } }
    val canCheck = !checking && password.text.isNotEmpty()

    fun check() {
        if (!canCheck) return
        val typed = Secret.of(password.text.toString())
        checking = true
        problem = null
        scope.launch {
            try {
                when (val result = biometric.turnOn(typed)) {
                    PasswordTurnOn.TurnedOn -> onDone(null)
                    PasswordTurnOn.Wrong -> problem = uiText(R.string.databases_page_fingerprint_wrong_password, name)
                    is PasswordTurnOn.NotChecked -> problem = result.failure.describe(name)
                    is PasswordTurnOn.Failed -> onDone(result.reason)
                    // Locked meanwhile: nothing to check against; the switch stays off.
                    PasswordTurnOn.Locked -> onCancel()
                }
            } finally {
                checking = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!checking) onCancel() },
        icon = { Icon(Icons.Rounded.Fingerprint, contentDescription = null) },
        title = { Text(stringResource(R.string.databases_page_fingerprint_password_title), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.databases_page_fingerprint_password_text, name))
                SecretTextMenu(copyable = false) {
                    SecureTextField(
                        state = password,
                        modifier = Modifier.keepOutOfAutofill().secretToAccessibility(password.text.length, shown = reveals).fillMaxWidth(),
                        enabled = !checking,
                        label = { Text(stringResource(R.string.unlock_reminder_master_password)) },
                        textStyle = if (reveals) MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily) else MaterialTheme.typography.bodyLarge,
                        textObfuscationMode = if (reveals) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                        isError = problem != null,
                        trailingIcon = {
                            IconButton(onClick = { reveals = !reveals }) {
                                Icon(
                                    if (reveals) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = stringResource(if (reveals) R.string.unlock_reminder_hide_password else R.string.unlock_reminder_show_password),
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        onKeyboardAction = { check() },
                        shape = RoundedCornerShape(20.dp),
                        colors = fieldColors(),
                    )
                }
                problem?.let {
                    // Said once by TalkBack as it appears, as the field's error.
                    Text(
                        it.resolve(),
                        Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (checking) {
                Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.unlock_reminder_checking), Modifier.padding(start = 10.dp))
                }
            } else {
                TextButton(onClick = ::check, enabled = canCheck) { Text(stringResource(R.string.databases_page_use_fingerprint)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !checking) { Text(stringResource(R.string.unlock_reminder_cancel)) } },
    )
}
