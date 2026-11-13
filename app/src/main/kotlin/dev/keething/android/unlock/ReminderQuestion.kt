package dev.keething.android.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.keething.android.databases.fieldColors
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.ui.SecretTextMenu
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.ui.secretToAccessibility
import dev.keething.domain.secret.Secret
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.session.KeyConfirmation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the reminder asks about: the database's key as it is made (keething-spec unlock.reminder-not-a-lock AC1, AC7, AC8). */
internal sealed interface ReminderKind {
    /** A master password alone: "Do you still remember it?", with Check it. */
    data object Password : ReminderKind

    /** Its key file alone ([keyFile]: its name): whether it is kept safe; nothing can check it. */
    data class KeyFile(val keyFile: String) : ReminderKind

    /** A password and a key file: the password's question, with a line about the key file. */
    data class PasswordAndKeyFile(val keyFile: String) : ReminderKind

    companion object {
        fun of(passwordless: Boolean, keyFile: String?): ReminderKind = when {
            passwordless && keyFile != null -> KeyFile(keyFile)
            keyFile != null -> PasswordAndKeyFile(keyFile)
            else -> Password
        }
    }
}

/** The reminder's words (the canvas "Password reminder", agreed 2026-11-13). [name]: the database's. */
internal object ReminderTexts {
    fun title(kind: ReminderKind): String = if (kind is ReminderKind.KeyFile) "Is the key file safe?" else "Do you still remember it?"

    fun text(kind: ReminderKind, name: String, days: Long): String = when (kind) {
        is ReminderKind.KeyFile -> "It has been ${daysText(days)}. Is the key file ${kind.keyFile} kept somewhere safe, besides this phone?"
        else -> "It has been ${daysText(days)} since the master password of “$name” was typed."
    }

    /** The one line about the key file in the password's question (AC8). */
    fun keyFileLine(kind: ReminderKind): String? =
        (kind as? ReminderKind.PasswordAndKeyFile)?.let { "Keep the key file ${it.keyFile} somewhere safe too: without it the password opens nothing." }

    fun check(name: String): String = "Type the master password of “$name”. If it does not match, nothing changes: $name stays open."

    fun wrong(left: Int): String = "It does not match. $left ${if (left == 1) "try" else "tries"} left."

    fun failed(name: String): String =
        "$name stays open, and nothing has changed. If its master password is saved in $name, look it up there. Or set a new one with Change master key."

    const val AGAIN_TOMORROW: String = "Keething asks again tomorrow."

    /** Said at the bottom once Yes or a matching check started the days again; [interval]: the days chosen in Settings. */
    fun answered(matched: Boolean, interval: Int?): String {
        val again = interval?.let { "Keething asks again in $it days." } ?: "Keething does not ask again."
        return if (matched) "It matches. $again" else again
    }

    private fun daysText(days: Long): String = if (days == 1L) "1 day" else "$days days"
}

/**
 * The reminder's question over one database's list (keething-spec unlock.reminder-not-a-lock AC1–AC4, AC7, AC8): Yes,
 * Check it (the password typed, at most [TRIES] tries, each a key derivation), or put away. [said] hears what to say at
 * the bottom; [failed] is told when three tries did not match, the question then put off. Call it on the main thread;
 * [scope] runs the check.
 */
internal class ReminderQuestionModel(
    val database: DatabaseSession,
    val name: String,
    val kind: ReminderKind,
    val days: Long,
    private val interval: Int?,
    private val file: String,
    private val scope: CoroutineScope,
    private val said: (String) -> Unit,
    private val failed: () -> Unit,
) {
    enum class Step { ASK, CHECK }

    var step: Step by mutableStateOf(Step.ASK)
        private set
    val password = TextFieldState()
    var reveals: Boolean by mutableStateOf(false)
    var checking: Boolean by mutableStateOf(false)
        private set

    /** Why the last try did not do: it did not match, or the file could not be read; null before one. */
    var problem: String? by mutableStateOf(null)
        private set
    var tries: Int by mutableIntStateOf(0)
        private set

    val canCheck: Boolean get() = step == Step.CHECK && !checking && password.text.isNotEmpty()

    /** Yes (AC2, AC7): believed. */
    fun yes() {
        database.biometric.remembered()
        said(ReminderTexts.answered(matched = false, interval))
    }

    /** Check it: the password asked for; not for a key file alone (AC7). */
    fun checkIt() {
        if (kind !is ReminderKind.KeyFile) step = Step.CHECK
    }

    /** Put away, Not now, Cancel: no answer, asked again a day later at the earliest (AC4). */
    fun putAway() {
        if (checking) return
        clear()
        database.biometric.putOff()
    }

    /** Check: the typed password with its key file against the key it is open with (AC2, AC3). */
    fun check(): Job? {
        if (!canCheck) return null
        val typed = Secret.of(password.text.toString())
        checking = true
        problem = null
        return scope.launch {
            try {
                when (val result = database.biometric.check(typed)) {
                    is KeyConfirmation.Confirmed -> {
                        clear()
                        said(ReminderTexts.answered(matched = true, interval))
                    }
                    KeyConfirmation.Wrong -> {
                        tries++
                        if (tries < TRIES) {
                            problem = ReminderTexts.wrong(TRIES - tries)
                        } else {
                            clear()
                            database.biometric.putOff()
                            failed()
                        }
                    }
                    is KeyConfirmation.Failed -> problem = result.failure.describe(file)
                    // Locked meanwhile: the question went with it.
                    KeyConfirmation.Locked -> clear()
                }
            } finally {
                typed.wipe()
                checking = false
            }
        }
    }

    /** What was typed goes: answered, put away, or the dialog gone. */
    fun clear() {
        password.clearText()
        reveals = false
    }

    companion object {
        const val TRIES: Int = 3
    }
}

/** The question, or Check it (the canvas "Password reminder"). Announced by itself as a dialog when it appears. */
@Composable
internal fun ReminderQuestion(model: ReminderQuestionModel) {
    when (model.step) {
        ReminderQuestionModel.Step.ASK -> AlertDialog(
            onDismissRequest = model::putAway,
            icon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            title = { Text(ReminderTexts.title(model.kind), textAlign = TextAlign.Center) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(ReminderTexts.text(model.kind, model.name, model.days))
                    ReminderTexts.keyFileLine(model.kind)?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = model::yes) { Text("Yes") } },
            dismissButton = {
                if (model.kind is ReminderKind.KeyFile) {
                    TextButton(onClick = model::putAway) { Text("Not now") }
                } else {
                    TextButton(onClick = model::checkIt) { Text("Check it") }
                }
            },
        )
        ReminderQuestionModel.Step.CHECK -> AlertDialog(
            onDismissRequest = model::putAway,
            icon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            title = { Text("Check the master password", textAlign = TextAlign.Center) },
            text = { CheckField(model) },
            confirmButton = {
                if (model.checking) {
                    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Checking…", Modifier.padding(start = 10.dp))
                    }
                } else {
                    TextButton(onClick = { model.check() }, enabled = model.canCheck) { Text("Check") }
                }
            },
            dismissButton = { TextButton(onClick = model::putAway, enabled = !model.checking) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CheckField(model: ReminderQuestionModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ReminderTexts.check(model.name))
        SecretTextMenu(copyable = false) {
            SecureTextField(
                state = model.password,
                modifier = Modifier.keepOutOfAutofill().secretToAccessibility(model.password.text.length, shown = model.reveals).fillMaxWidth(),
                enabled = !model.checking,
                label = { Text("Master password") },
                textStyle = if (model.reveals) MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily) else MaterialTheme.typography.bodyLarge,
                textObfuscationMode = if (model.reveals) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                isError = model.problem != null,
                trailingIcon = {
                    IconButton(onClick = { model.reveals = !model.reveals }) {
                        Icon(
                            if (model.reveals) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (model.reveals) "Hide the password" else "Show the password",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                onKeyboardAction = { model.check() },
                shape = RoundedCornerShape(20.dp),
                colors = fieldColors(),
            )
        }
        model.problem?.let {
            // Said once by TalkBack as it appears, as the field's error.
            Text(
                it,
                Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Three tries did not match (AC3): nothing changed; look it up, or Change master key…. */
@Composable
internal fun ReminderNotMatched(name: String, onChangeMasterKey: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Rounded.ErrorOutline, contentDescription = null) },
        title = { Text("It does not match", textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ReminderTexts.failed(name))
                Text(ReminderTexts.AGAIN_TOMORROW)
            }
        },
        confirmButton = { TextButton(onClick = onChangeMasterKey) { Text("Change master key…") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
