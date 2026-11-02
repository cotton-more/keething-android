package dev.keething.android.quicknote

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.keething.android.AppContainer
import dev.keething.android.KeethingApplication
import dev.keething.android.editor.EntryEditorScreen
import dev.keething.android.quicknote.QuickNoteModel.Showing
import dev.keething.android.theme.KeethingTheme
import dev.keething.android.ui.secureWindow
import dev.keething.android.unlock.LockScreen
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.QuickNoteTarget
import dev.keething.vault.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keething note in the share sheet and the text selection menu, the Quick note tile and the launcher's Note from
 * clipboard (SPEC §6.4; keething-spec app.quick-note AC3, AC4, AC7): the text another app hands over, or the clipboard's
 * once this screen has the window focus, goes into the quick note's editor, in a sheet over that app, after the quick
 * notes' database's lock screen when it is locked ([QuickNoteModel]). Saved or given up, the screen goes and the other app is
 * in front again. It goes too when it stops, unless a screen it opened itself is in front (a key file picker, Locate…);
 * the text is in the intent and in memory only, never in saved state, a log or the settings.
 */
class QuickNoteActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as KeethingApplication).container

    /** This screen came back with a saved state: a configuration change keeps its model, a process that died does not. */
    private var restored = false

    /** The model was made for a screen that came back with a saved state: the process died since, and it gives up. */
    private var recreated = false

    private val model: QuickNoteModel by viewModels {
        viewModelFactory {
            initializer {
                recreated = restored
                val sessions = container.sessions
                QuickNoteModel(
                    target = { QuickNoteTarget.resolve(container.settings.quickNoteDatabase.value, sessions.list.state.value) },
                    isOpen = { id -> sessions.openNow().any { it.database.id == id } },
                    state = { id -> sessions.window.map { window -> window.firstOrNull { it.id == id }?.session } },
                    intoWindow = { id -> sessions.list.setInWindow(id, true) },
                    started = container.quickNotes,
                    locks = sessions.locks.counted,
                    awaited = { sessions.quickNoteAwaited += if (it) 1 else -1 },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                )
            }
        }
    }

    /** A screen this one started is in front (a key file picker, Locate…'s folder picker): not the app left. */
    private var ownInFront = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the model is first asked for.
        restored = savedInstanceState != null
        super.onCreate(savedInstanceState)
        secureWindow()
        enableEdgeToEdge()
        setContent {
            KeethingTheme {
                QuickNoteSheet(container, model)
            }
        }
        lifecycleScope.launch {
            // What it showed, by name only (never the text, the database or the group).
            model.showing.collect { Log.i(TAG, it.javaClass.simpleName + if (it is Showing.Gone && it.message != null) " with a notice" else "") }
        }
        lifecycleScope.launch {
            val gone = model.showing.first { it is Showing.Gone } as Showing.Gone
            gone.message?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show() }
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        ownInFront = false
    }

    override fun onResume() {
        super.onResume()
        // In front: nothing it started is (a start that never showed a screen leaves nothing behind).
        ownInFront = false
        // After the app's own start, where a stay away past the timeout locks first (SPEC §4): an open database is
        // open, and that lock is not counted against this quick note.
        // The sending or calling app as the system names it (a share's sender, not the chooser; the selection menu's
        // caller, Firefox's too, which does not start it for a result: plans/done/android-notes.md AQ0).
        // The clipboard's waits for the window focus (onWindowFocusChanged).
        model.start(recreated) {
            QuickNoteIntent.source(intent.action, intent::getCharSequenceExtra, { QuickNoteApp.name(referrer?.toString(), packageName, ::appLabel) }) {
                container.systemClipboard
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        model.focus(hasFocus)
    }

    override fun onPause() {
        super.onPause()
        // A rotation removes the window without always saying it lost the focus; the new one says when it has it.
        model.focus(false)
    }

    override fun onStop() {
        super.onStop()
        // The fingerprint prompt is the system's window over this one: it does not stop it.
        if (!isChangingConfigurations) model.stopped(ownInFront)
    }

    /** An installed app's label as the launcher shows it; null when it is not installed (or not visible). */
    private fun appLabel(packageName: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Every screen this one starts comes through here (`startActivity` too). */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        ownInFront = true
        super.startActivityForResult(intent, requestCode, options)
    }

    private companion object {
        const val TAG = "QuickNote"
    }
}

/** The sheet over the other app (the agreed mock-up): the lock screen, titled "Keething note", or the editor. */
@Composable
private fun QuickNoteSheet(container: AppContainer, model: QuickNoteModel) {
    val showing by model.showing.collectAsStateWithLifecycle()
    val shown = showing
    if (shown !is Showing.Unlock && shown !is Showing.Editor) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
        Surface(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 24.dp),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            when (shown) {
                is Showing.Unlock -> Locked(container, shown.database, onClose = model::back)
                is Showing.Editor -> Editor(container, shown, model)
                else -> {}
            }
        }
    }
}

@Composable
private fun Locked(container: AppContainer, database: DatabaseId, onClose: () -> Unit) {
    // Brought into the window when it was not; once it leaves, the model gives the quick note up.
    val window by container.sessions.window.collectAsStateWithLifecycle()
    val session = remember(window) { container.sessions.session(database) } ?: return
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
            Text("Keething note", style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LockScreen(container, session, onBack = null, onChose = {}, purpose = "Unlock to write the quick note")
        }
    }
}

@Composable
private fun Editor(container: AppContainer, editor: Showing.Editor, model: QuickNoteModel) {
    val window by container.sessions.window.collectAsStateWithLifecycle()
    val session = remember(window) { container.sessions.session(editor.database) } ?: return
    // No places: no Save into, and the next new entry's database stays where it was (lastNewEntryDatabase).
    EntryEditorScreen(
        database = session,
        entryId = null,
        groupId = null,
        generator = container::passwordGenerator,
        onClose = model::editorClosed,
        onSaved = { ref, _ -> model.saved((session.session.state.value as? SessionState.Unlocked)?.vault, ref.entry) },
        note = editor.note,
        copySecret = { container.clipboard.copy(it, sensitive = true) },
    )
}
