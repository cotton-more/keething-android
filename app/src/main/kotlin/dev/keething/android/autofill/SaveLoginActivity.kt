package dev.keething.android.autofill

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
import androidx.compose.runtime.mutableIntStateOf
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
import dev.keething.android.AppContainer
import dev.keething.android.KeethingApplication
import dev.keething.android.editor.EntryEditorScreen
import dev.keething.android.editor.deletedGroupsNote
import dev.keething.android.editor.NewEntryDatabases
import dev.keething.android.session.OpenDatabase
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.EntryRef
import dev.keething.android.theme.KeethingTheme
import dev.keething.android.ui.secureWindow
import dev.keething.android.unlock.LockScreen
import dev.keething.autofill.saving.SavePlan
import dev.keething.autofill.saving.SavePlanner
import dev.keething.autofill.saving.TypedLogin
import dev.keething.autofill.target.FillTarget

/** The login being saved; wiped when the screen is done with it. [asNew]: the user chose a new entry over the update. */
internal class SaveLoginModel(val save: PendingSave?) : ViewModel() {
    var asNew by mutableStateOf(false)

    override fun onCleared() {
        save?.wipe()
    }
}

/**
 * The save offer's screen (SPEC §7, Save): the unlock while no database is open, then the editor, prefilled —
 * the matching account's entry with the new password, in its own database, or a new entry named after the site or app
 * in the last new entry's database, else the first open one, with Save into while two or more are open (keething-spec
 * autofill.save AC7). Saving goes through the normal save path (§3.3). Opened by the system after its "Save to
 * Keething?"; not exported.
 */
class SaveLoginActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as KeethingApplication).container

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
            KeethingTheme {
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
    val open by container.sessions.open.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        val current = database ?: return@Surface NoDatabase(onClose)
        // Each unlock shows a fresh editor: a lock took the draft (and its password) with it.
        var showing by remember { mutableIntStateOf(0) }
        LaunchedEffect(open.isEmpty()) { if (open.isEmpty()) showing++ }
        if (open.isEmpty()) return@Surface LockScreen(container, current, onBack = null, onChose = {})
        // Decided once on the databases as they were when one was open: the save itself changes its vault.
        val plan = remember(model.asNew) { plan(container, open, save, label, model.asNew) }
        val password = remember { save.password.reveal() }
        when (plan) {
            is SavePlan.Unchanged -> LaunchedEffect(plan) { onDone("Already saved in Keething: ${plan.entry.displayTitle}") }
            is SavePlan.Update -> EntryEditorScreen(
                // The entry's own database; once it is not open (locked alone meanwhile), the editor and its password go.
                database = EntryRef.of(plan.entry.id).database.takeIf { id -> open.any { it.database.id == id } }?.let(container.sessions::session)
                    ?: return@Surface LaunchedEffect(Unit) { onClose() },
                entryId = EntryRef.of(plan.entry.id).entry,
                groupId = null,
                generator = container::passwordGenerator,
                copySecret = { container.clipboard.copy(it, sensitive = true) },
                onClose = onClose,
                onSaved = { _, deleted -> onDone(listOfNotNull("Password updated in Keething", deletedGroupsNote(deleted)).joinToString(". ")) },
                prefill = { it.copy(password = password) },
                banner = {
                    Banner(
                        "Update the password of “${plan.entry.displayTitle}”?",
                        "The password typed in $label is new for this account.",
                        action = "Save as a new entry instead" to { model.asNew = true },
                    )
                },
            )
            is SavePlan.New -> EntryEditorScreen(
                database = remember { newLoginDatabase(container, open) } ?: current,
                entryId = null,
                groupId = null,
                generator = container::passwordGenerator,
                copySecret = { container.clipboard.copy(it, sensitive = true) },
                onClose = onClose,
                onSaved = { _, _ -> onDone("Saved to Keething") },
                prefill = { it.copy(title = plan.title, url = plan.url, username = save.username.ifEmpty { it.username }, password = password) },
                banner = { Banner("Save the login typed in $label", "A new entry; check its name and group.") },
                places = remember { container.newEntryPlaces() },
                showing = showing,
            )
        }
    }
}

/** A new login's database: the last new entry's, else the first open one (keething-spec autofill.save AC7). */
private fun newLoginDatabase(container: AppContainer, open: List<OpenDatabase>): DatabaseSession? {
    val sessions = container.sessions
    val start = NewEntryDatabases.start(sessions.newEntryDatabases(open), query = "", openEntry = null, last = container.settings.lastNewEntryDatabase)
    return start?.let(sessions::session)
}

/** The matching entries of every open database, each by its ref. */
private fun plan(container: AppContainer, open: List<OpenDatabase>, save: PendingSave, label: String, asNew: Boolean): SavePlan {
    val typed = TypedLogin(save.username, save.password.reveal(), save.currentPassword?.reveal())
    val appName = label.takeIf { save.target is FillTarget.App }
    val vaults = open.map { OpenVault(it.database.id, it.unlocked.vault) }
    val candidates = if (asNew) emptyList() else container.autofillEntries.candidates(vaults, save.target).map { it.entry }
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
        Text("Open Keething and choose the database first.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onClose) { Text("Close") }
    }
}
