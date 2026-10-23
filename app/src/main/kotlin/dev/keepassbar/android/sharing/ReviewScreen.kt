package dev.keepassbar.android.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.EntryIcon
import dev.keepassbar.android.ui.NoLearningKeyboard
import dev.keepassbar.android.ui.segmentShape
import dev.keepassbar.card.EntryAvatar
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.FieldValue
import dev.keepassbar.sharing.message.ShareRequest
import dev.keepassbar.sharing.payload.AnswerPayload
import dev.keepassbar.sharing.payload.EntryPart
import dev.keepassbar.sharing.payload.Sending
import dev.keepassbar.sharing.requests.ShareRequests
import dev.keepassbar.vault.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An answer made: what the answer screen shows and sends. Not secret: the entries are sealed in [encoded]. */
internal data class MadeAnswer(val name: String, val reason: String, val entries: Int, val encoded: ByteArray) {
    override fun equals(other: Any?): Boolean = other is MadeAnswer && encoded.contentEquals(other.encoded)

    override fun hashCode(): Int = encoded.contentHashCode()
}

/** What of each chosen entry goes (ids and part names only, never a value), and sealing it for the request. */
internal class ReviewModel(private val database: DatabaseSession, private val requests: ShareRequests, private val id: String, entryIds: List<String>) : ViewModel() {
    val entryIds = entryIds

    /** Per entry, the parts left out: the one-time code to start with (SPEC §10). */
    val leftOut = mutableStateMapOf<String, Set<EntryPart>>().apply {
        val vault = (database.session.state.value as? SessionState.Unlocked)?.vault
        entryIds.forEach { id -> put(id, vault?.entries?.firstOrNull { it.id == id }?.let(Sending::leftOutByDefault).orEmpty()) }
    }

    var sealing by mutableStateOf(false)
        private set

    /** The answer was made: the request is gone because of it, not under this screen's feet. */
    var made by mutableStateOf(false)
        private set

    var problem by mutableStateOf<String?>(null)
        private set

    fun toggle(entryId: String, part: EntryPart) {
        val parts = leftOut[entryId].orEmpty()
        leftOut[entryId] = if (part in parts) parts - part else parts + part
    }

    /** Seals the entries as they are in the vault now; [onMade] gets the answer, the request is then gone. */
    fun seal(from: String, onMade: (MadeAnswer) -> Unit) {
        val vault = (database.session.state.value as? SessionState.Unlocked)?.vault ?: return
        val their = requests.book.value?.theirs?.firstOrNull { it.request.id == id } ?: return
        val entries = entryIds.mapNotNull { entryId -> vault.entries.firstOrNull { it.id == entryId } }
        if (entries.isEmpty()) {
            problem = "The entries are no longer in the database."
            return
        }
        sealing = true
        problem = null
        viewModelScope.launch {
            val answer = withContext(Dispatchers.Default) {
                val payload = AnswerPayload(from.trim(), entries.map { Sending.shared(it, leftOut[it.id].orEmpty()) })
                try {
                    runCatching { requests.answer(id, payload) }
                } finally {
                    payload.wipe()
                }
            }
            sealing = false
            answer.onSuccess { sealed ->
                if (sealed == null) {
                    problem = "The request has expired or was deleted. Ask for a new one."
                } else {
                    made = true
                    onMade(MadeAnswer(their.request.name, their.request.reason, entries.size, sealed.encoded()))
                }
            }.onFailure { problem = "The answer could not be made: ${it.message}." }
        }
    }
}

/**
 * What to send of the chosen entries (SPEC §10): each part with a switch, the one-time code off to start with and a
 * warning when on; the title, tags and expiry always go, attachments and history never. Encrypt seals them for the
 * request ([onMade]), which closes it.
 */
@Composable
internal fun ReviewScreen(
    container: AppContainer,
    database: DatabaseSession,
    id: String,
    entryIds: List<String>,
    onBack: () -> Unit,
    onMade: (MadeAnswer) -> Unit,
) {
    val model = viewModel { ReviewModel(database, container.shareRequests, id, entryIds) }
    val book by container.shareRequests.book.collectAsStateWithLifecycle()
    val session by database.session.state.collectAsStateWithLifecycle()
    val their = book?.theirs?.firstOrNull { it.request.id == id }
    val vault = (session as? SessionState.Unlocked)?.vault
    LaunchedEffect(their == null || vault == null) { if ((their == null || vault == null) && !model.sealing && !model.made) onBack() }
    if (their == null || vault == null) return
    var from by rememberSaveable { mutableStateOf(container.settings.requestName) }
    val entries = model.entryIds.mapNotNull { entryId -> vault.entries.firstOrNull { it.id == entryId } }
    val ready = ShareRequest.isLine(from.trim(), ShareRequest.MAX_NAME_LENGTH) && entries.isNotEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Send to ${their.request.name}")
                        Text(their.request.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                BottomActions {
                    model.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Button(
                        onClick = {
                            container.settings.requestName = from
                            model.seal(from, onMade)
                        },
                        enabled = ready && !model.sealing,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        if (model.sealing) {
                            LoadingIndicator(Modifier.size(28.dp))
                        } else {
                            Icon(Icons.Rounded.Lock, contentDescription = null)
                            Spacer(Modifier.size(10.dp))
                            Text("Encrypt for ${their.request.name}")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NoLearningKeyboard {
                val fill = MaterialTheme.colorScheme.surfaceContainerHigh
                TextField(
                    value = from,
                    onValueChange = { from = it.replace('\n', ' ').take(ShareRequest.MAX_NAME_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("From") },
                    placeholder = { Text("Your name, as ${their.request.name} knows you") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    shape = RoundedCornerShape(18.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = fill,
                        unfocusedContainerColor = fill,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
            for (entry in entries) EntryParts(entry, model.leftOut[entry.id].orEmpty(), their.request.name) { model.toggle(entry.id, it) }
            InfoBox(Icons.Rounded.Info, "Tags and the expiry date go along. Attachments and earlier versions never do.")
        }
    }
}

@Composable
private fun EntryParts(entry: Entry, leftOut: Set<EntryPart>, name: String, onToggle: (EntryPart) -> Unit) {
    val parts = Sending.parts(entry)
    val rows = parts.size + 1 + if (entry.attachments.isNotEmpty()) 1 else 0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(segmentShape(0, rows)).background(MaterialTheme.extraColors.row).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EntryIcon(EntryAvatar.of(entry.displayTitle), null, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(entry.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (entry.groupPath.joinToString(" › ").ifEmpty { "Top level" }) + " · the title always goes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        parts.forEachIndexed { index, part ->
            val on = part !in leftOut
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clip(segmentShape(index + 1, rows))
                    .background(MaterialTheme.extraColors.row)
                    .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle(part) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(part.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        part.preview(entry),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (on && Sending.isSecondStep(part)) {
                        Text(
                            "With it, $name can sign in without you: the second step is no longer yours alone.",
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.extraColors.warn,
                        )
                    }
                }
                Switch(checked = on, onCheckedChange = null)
            }
        }
        if (entry.attachments.isNotEmpty()) {
            val count = entry.attachments.size
            Text(
                if (count == 1) "1 attachment stays on this phone" else "$count attachments stay on this phone",
                Modifier.fillMaxWidth().clip(segmentShape(rows - 1, rows)).background(MaterialTheme.extraColors.row).padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun EntryPart.label(): String = when (this) {
    EntryPart.Username -> "Username"
    EntryPart.Password -> "Password"
    EntryPart.Website -> "Website"
    EntryPart.Notes -> "Notes"
    is EntryPart.Field -> name
    EntryPart.OneTimeCode -> "One-time code"
}

/** What the row shows of the part: never a protected value. */
private fun EntryPart.preview(entry: Entry): String = when (this) {
    EntryPart.Username -> entry.username
    EntryPart.Password -> "••••••••••••"
    EntryPart.Website -> entry.url
    EntryPart.Notes -> entry.notes.lineSequence().first()
    is EntryPart.Field -> when (val value = entry.customFields.firstOrNull { it.name == name }?.value) {
        is FieldValue.Plain -> value.text.lineSequence().first()
        else -> "••••••••"
    }
    EntryPart.OneTimeCode -> "The key that makes the sign-in codes"
}
