package dev.keething.android.sharing

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
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
import dev.keething.android.AppContainer
import dev.keething.android.session.OpenDatabase
import kotlinx.coroutines.flow.StateFlow
import dev.keething.android.session.entries
import dev.keething.vault.databases.EntryRef
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.BarTitle
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.EntryIcon
import dev.keething.android.ui.NoLearningKeyboard
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.segmentShape
import dev.keething.card.EntryAvatar
import dev.keething.card.WalletSecrets
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.FieldValue
import dev.keething.sharing.message.ShareRequest
import dev.keething.sharing.payload.AnswerPayload
import dev.keething.sharing.payload.EntryPart
import dev.keething.sharing.payload.Sending
import dev.keething.sharing.requests.ShareRequests
import java.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An answer made: what the answer screen shows and sends. Not secret: the entries are sealed in [encoded]. */
internal data class MadeAnswer(val name: String, val reason: String, val entries: Int, val encoded: ByteArray) {
    override fun equals(other: Any?): Boolean = other is MadeAnswer && encoded.contentEquals(other.encoded)

    override fun hashCode(): Int = encoded.contentHashCode()
}

/**
 * What of each chosen entry goes (ids and part names only, never a value), and sealing it: for their request [id], or in
 * an envelope when [id] is null.
 */
internal class ReviewModel(
    /** The open databases: each entry is read from its own, by its ref. */
    private val open: StateFlow<List<OpenDatabase>>,
    private val requests: ShareRequests,
    private val id: String?,
    entryIds: List<String>,
    private val validity: Duration,
) : ViewModel() {
    val entryIds = entryIds

    /** Per entry, the parts left out: the one-time code and an extended public key to start with (SPEC §10). */
    val leftOut = mutableStateMapOf<String, Set<EntryPart>>().apply {
        val entries = open.value.entries(entryIds).associateBy { it.id }
        entryIds.forEach { id -> put(id, entries[id]?.let(Sending::leftOutByDefault).orEmpty()) }
    }

    var sealing by mutableStateOf(false)
        private set

    /** The answer or envelope was made: a request is gone because of it, not under this screen's feet. */
    var made by mutableStateOf(false)
        private set

    var problem by mutableStateOf<String?>(null)
        private set

    fun toggle(entryId: String, part: EntryPart) {
        val parts = leftOut[entryId].orEmpty()
        leftOut[entryId] = if (part in parts) parts - part else parts + part
    }

    /**
     * Seals the entries as they are in the vault now. For a request [onMade] gets the answer, the request is then gone;
     * else [onSent] gets the new envelope's id, for [recipient] (kept on this phone only).
     */
    fun seal(from: String, recipient: String, onMade: (MadeAnswer) -> Unit, onSent: (envelopeId: String) -> Unit) {
        if (open.value.isEmpty()) return
        val their = id?.let { id -> requests.book.value?.theirs?.firstOrNull { it.request.id == id } ?: return }
        val entries = open.value.entries(entryIds)
        if (entries.isEmpty()) {
            problem = "The entries are no longer in the database."
            return
        }
        sealing = true
        problem = null
        viewModelScope.launch {
            if (their == null) {
                sendEnvelope(from, recipient, entries, onSent)
                return@launch
            }
            val answer = withContext(Dispatchers.Default) {
                val payload = AnswerPayload(from.trim(), entries.map { Sending.shared(it, leftOut[it.id].orEmpty()) })
                try {
                    runCatching { requests.answer(their.request.id, payload) }
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

    private suspend fun sendEnvelope(from: String, recipient: String, entries: List<Entry>, onSent: (String) -> Unit) {
        val started = TimeSource.Monotonic.markNow()
        val sent = withContext(Dispatchers.Default) {
            val payload = AnswerPayload(from.trim(), entries.map { Sending.shared(it, leftOut[it.id].orEmpty()) })
            try {
                runCatching { requests.send(payload, recipient.trim(), entries.map { it.displayTitle }, validity) }
            } finally {
                payload.wipe()
            }
        }
        sealing = false
        sent.onSuccess { envelope ->
            if (envelope == null) {
                problem = "The phone was locked. Unlock it and try again."
            } else {
                Log.i("Sharing", "envelope made in ${started.elapsedNow()}")
                made = true
                onSent(envelope.envelope.id)
            }
        }.onFailure { problem = "The entries could not be sealed: ${it.message}." }
    }
}

/**
 * What to send of the chosen entries (SPEC §10): each part with a switch, the one-time code and a wallet's extended
 * public key off to start with and a warning when on; the title, tags and expiry always go, attachments, history and
 * a wallet's phrase or private key never (locked rows, SPEC §10.1). Encrypt seals them for their
 * request [id] ([onMade]), which closes it; with no [id], in an envelope opened by ten pictures ([onSent]).
 */
@Composable
internal fun ReviewScreen(
    container: AppContainer,
    id: String?,
    entryIds: List<String>,
    onBack: () -> Unit,
    onMade: (MadeAnswer) -> Unit,
    onSent: (envelopeId: String) -> Unit,
) {
    val validity = Duration.ofDays(container.settings.requestValidity.value.inWholeDays)
    val model = viewModel { ReviewModel(container.sessions.open, container.shareRequests, id, entryIds, validity) }
    val book by container.shareRequests.book.collectAsStateWithLifecycle()
    val open by container.sessions.open.collectAsStateWithLifecycle()
    val their = id?.let { id -> book?.theirs?.firstOrNull { it.request.id == id } }
    // A chosen entry's database locked meanwhile: the review goes rather than send fewer than were chosen.
    val gone = book == null || (id != null && their == null) ||
        model.entryIds.any { ref -> open.none { it.database.id == EntryRef.of(ref).database } }
    LaunchedEffect(gone) { if (gone && !model.sealing && !model.made) onBack() }
    if (gone) return
    var from by rememberSaveable { mutableStateOf(container.settings.requestName) }
    var recipient by rememberSaveable { mutableStateOf("") }
    val entries = open.entries(model.entryIds)
    val ready = ShareRequest.isLine(from.trim(), ShareRequest.MAX_NAME_LENGTH) && entries.isNotEmpty() &&
        (recipient.isBlank() || ShareRequest.isLine(recipient.trim(), ShareRequest.MAX_NAME_LENGTH))
    // Who the warnings name: the asker, or whom the envelope is for.
    val name = their?.request?.name ?: recipient.trim().ifEmpty { "whoever opens it" }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    BarTitle(
                        if (their != null) "Send to ${their.request.name}" else "Send without a request",
                        their?.request?.reason ?: "It opens with ten pictures you give another way",
                    )
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
                            model.seal(from, recipient, onMade, onSent)
                        },
                        enabled = ready && !model.sealing,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        if (model.sealing) {
                            LoadingIndicator(Modifier.size(28.dp))
                        } else {
                            ButtonLabel(if (their != null) "Encrypt for ${their.request.name}" else "Seal with ten pictures", Icons.Rounded.Lock)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = readableInset(), vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NoLearningKeyboard {
                if (their == null) {
                    NameField(recipient, { recipient = it }, "For", "Whom it is for: only this phone keeps it")
                }
                NameField(from, { from = it }, "From", if (their != null) "Your name, as ${their.request.name} knows you" else "Your name, as they know you")
            }
            for (entry in entries) EntryParts(entry, model.leftOut[entry.id].orEmpty(), name) { model.toggle(entry.id, it) }
            InfoBox(Icons.Rounded.Info, "Tags and the expiry date go along. Attachments and earlier versions never do.")
        }
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, label: String, placeholder: String) {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = value,
        onValueChange = { onChange(it.replace('\n', ' ').take(ShareRequest.MAX_NAME_LENGTH)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
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

@Composable
private fun EntryParts(entry: Entry, leftOut: Set<EntryPart>, name: String, onToggle: (EntryPart) -> Unit) {
    val parts = remember(entry) { Sending.parts(entry) }
    val never = remember(entry) { Sending.neverSent(entry) }
    val rows = parts.size + never.size + 1 + if (entry.attachments.isNotEmpty()) 1 else 0
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
                    val warning = when {
                        !on -> null
                        Sending.isSecondStep(part) -> "With it, $name can sign in without you: the second step is no longer yours alone."
                        Sending.isWalletKey(entry, part) ->
                            "With it, $name sees every address and balance of the wallet, past and future, and one leaked key of any " +
                                "of its addresses opens them all."
                        else -> null
                    }
                    warning?.let {
                        Text(
                            it,
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
        never.entries.forEachIndexed { index, (part, kind) ->
            val (what, why) = kind.words()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clip(segmentShape(parts.size + index + 1, rows))
                    .background(MaterialTheme.extraColors.row)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(part.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$what · stays on this phone", style = MaterialTheme.typography.bodyLarge)
                    Text(why, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.Lock, contentDescription = "Never sent", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** What a part that never goes is, and why (SPEC §10.1). */
private fun WalletSecrets.Kind.words(): Pair<String, String> = when (this) {
    WalletSecrets.Kind.PHRASE ->
        "Recovery phrase" to "Whoever has these words has the wallet, on every network. To give someone coins, send them to their address."
    WalletSecrets.Kind.PASSPHRASE -> "Passphrase" to "With the recovery phrase it opens the wallet."
    WalletSecrets.Kind.PRIVATE_KEY ->
        "Private key" to "Whoever has it can spend the wallet's coins. To give someone coins, send them to their address."
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
