package dev.keepassbar.android.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.KeePassBarApplication
import dev.keepassbar.android.editor.EntryEditorScreen
import dev.keepassbar.android.theme.KeePassBarTheme
import dev.keepassbar.android.ui.secureWindow
import dev.keepassbar.android.unlock.LockScreen
import dev.keepassbar.autofill.saving.SavePlan
import dev.keepassbar.autofill.saving.SavePlanner
import dev.keepassbar.autofill.saving.TypedLogin
import dev.keepassbar.autofill.target.FillTarget
import dev.keepassbar.domain.vault.Vault
import dev.keepassbar.vault.session.SessionState

/** The login being saved; wiped when the screen is done with it. [asNew]: the user chose a new entry over the update. */
internal class SaveLoginModel(val save: PendingSave?) : ViewModel() {
    var asNew by mutableStateOf(false)

    override fun onCleared() {
        save?.wipe()
    }
}

/**
 * The save offer's screen (SPEC §7, Save): the unlock when the vault is locked, then the editor, prefilled —
 * the matching account's entry with the new password, or a new entry named after the site or app. Saving goes
 * through the normal save path (§3.3). Opened by the system after its "Save to KeePassBar?"; not exported.
 */
class SaveLoginActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as KeePassBarApplication).container

    private val model: SaveLoginModel by viewModels {
        viewModelFactory { initializer { SaveLoginModel(container.pendingSaves.take(intent.getStringExtra(TOKEN))) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val save = model.save
        if (save == null) {
            Toast.makeText(this, "Nothing to save: the login was typed too long ago", Toast.LENGTH_LONG).show()
            return finish()
        }
        secureWindow()
        enableEdgeToEdge()
        val label = container.autofillEntries.label(save.target)
        setContent {
            KeePassBarTheme {
                SaveLoginScreen(
                    container, model, save, label,
                    onDone = { text ->
                        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
    }

    companion object {
        private const val TOKEN = "token"

        /** The screen for the login kept under [token]; the intent carries the token only. */
        internal fun sender(context: Context, token: String): IntentSender {
            val intent = Intent(context, SaveLoginActivity::class.java).putExtra(TOKEN, token)
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getActivity(context, token.hashCode(), intent, flags).intentSender
        }
    }
}

@Composable
private fun SaveLoginScreen(
    container: AppContainer,
    model: SaveLoginModel,
    save: PendingSave,
    label: String,
    onDone: (String) -> Unit,
    onClose: () -> Unit,
) {
    val database by container.sessions.current.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        val current = database ?: return@Surface NoDatabase(onClose)
        val state by current.session.state.collectAsStateWithLifecycle()
        val vault = (state as? SessionState.Unlocked)?.vault ?: return@Surface LockScreen(container, current, onChooseDatabase = {})
        // Decided once on the vault as it was when unlocked: the save itself changes the vault.
        val plan = remember(current.number, model.asNew) { plan(container, vault, save, label, model.asNew) }
        val password = remember { save.password.reveal() }
        when (plan) {
            is SavePlan.Unchanged -> LaunchedEffect(plan) { onDone("Already saved in KeePassBar: ${plan.entry.displayTitle}") }
            is SavePlan.Update -> EntryEditorScreen(
                database = current,
                entryId = plan.entry.id,
                groupId = null,
                generator = container::passwordGenerator,
                onClose = onClose,
                onSaved = { onDone("Password updated in KeePassBar") },
                prefill = { it.copy(password = password) },
                banner = {
                    Banner(
                        "Update the password of “${plan.entry.displayTitle}”?",
                        "The password typed in $label is new for this account.",
                        action = "Save as a new entry instead" to { model.asNew = true },
                    )
                },
                doneLabel = "Done",
            )
            is SavePlan.New -> EntryEditorScreen(
                database = current,
                entryId = null,
                groupId = null,
                generator = container::passwordGenerator,
                onClose = onClose,
                onSaved = { onDone("Saved to KeePassBar") },
                prefill = { it.copy(title = plan.title, url = plan.url, username = save.username.ifEmpty { it.username }, password = password) },
                banner = { Banner("Save the login typed in $label", "A new entry; check its name and group.") },
                doneLabel = "Done",
            )
        }
    }
}

private fun plan(container: AppContainer, vault: Vault, save: PendingSave, label: String, asNew: Boolean): SavePlan {
    val typed = TypedLogin(save.username, save.password.reveal(), save.currentPassword?.reveal())
    val appName = label.takeIf { save.target is FillTarget.App }
    val candidates = if (asNew) emptyList() else container.autofillEntries.candidates(vault, save.target).map { it.entry }
    return SavePlanner.plan(candidates, typed, save.target, appName)
}

@Composable
private fun Banner(title: String, text: String, action: Pair<String, () -> Unit>? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = if (action == null) 14.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            action?.let { (label, run) -> TextButton(onClick = run, modifier = Modifier.align(Alignment.End)) { Text(label) } }
        }
    }
}

@Composable
private fun NoDatabase(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No database chosen yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Open KeePassBar and choose the database first.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onClose) { Text("Close") }
    }
}
