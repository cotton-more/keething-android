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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import dev.keething.android.R
import dev.keething.android.session.OpenDatabase
import dev.keething.android.ui.UiText
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.ui.resolve
import dev.keething.android.ui.spokenMasked
import dev.keething.android.ui.ui
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiText
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

    var problem by mutableStateOf<UiText?>(null)
        private set

    fun toggle(entryId: String, part: EntryPart) {
        val parts = leftOut[entryId].orEmpty()
        leftOut[entryId] = if (part in parts) parts - part else parts + part
    }

    /**
     * Seals the entries as they are in the vault now. For a request [onMade] gets the answer, the request is then gone;
     * else [onSent] gets the new envelope's id, for [recipient] (kept on this phone only).
     */
    fun seal(from: String, recipient: String, title: (Entry) -> String, onMade: (MadeAnswer) -> Unit, onSent: (envelopeId: String) -> Unit) {
        if (open.value.isEmpty()) return
        val their = id?.let { id -> requests.book.value?.theirs?.firstOrNull { it.request.id == id } ?: return }
        val entries = open.value.entries(entryIds)
        if (entries.isEmpty()) {
            problem = uiText(R.string.sharing_review_no_entries)
            return
        }
        sealing = true
        problem = null
        viewModelScope.launch {
            if (their == null) {
                sendEnvelope(from, recipient, entries.map(title), entries, onSent)
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
                    problem = uiText(R.string.sharing_review_request_gone)
                } else {
                    made = true
                    onMade(MadeAnswer(their.request.name, their.request.reason, entries.size, sealed.encoded()))
                }
            }.onFailure { problem = uiText(R.string.sharing_review_not_made, it.message.toString()) }
        }
    }

    private suspend fun sendEnvelope(from: String, recipient: String, titles: List<String>, entries: List<Entry>, onSent: (String) -> Unit) {
        val started = TimeSource.Monotonic.markNow()
        val sent = withContext(Dispatchers.Default) {
            val payload = AnswerPayload(from.trim(), entries.map { Sending.shared(it, leftOut[it.id].orEmpty()) })
            try {
                runCatching { requests.send(payload, recipient.trim(), titles, validity) }
            } finally {
                payload.wipe()
            }
        }
        sealing = false
        sent.onSuccess { envelope ->
            if (envelope == null) {
                problem = uiText(R.string.sharing_review_locked)
            } else {
                Log.i("Sharing", "envelope made in ${started.elapsedNow()}")
                made = true
                onSent(envelope.envelope.id)
            }
        }.onFailure { problem = uiText(R.string.sharing_review_not_sealed, it.message.toString()) }
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
    val resources = LocalResources.current
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
    // Who the warnings name: the asker, or whom the envelope is for; null: whoever opens it.
    val name = their?.request?.name ?: recipient.trim().ifEmpty { null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    BarTitle(
                        if (their != null) stringResource(R.string.sharing_review_send_to, their.request.name) else stringResource(R.string.sharing_review_send_without),
                        their?.request?.reason ?: stringResource(R.string.sharing_review_without_subtitle),
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.sharing_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                BottomActions {
                    model.problem?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Button(
                        onClick = {
                            container.settings.requestName = from
                            model.seal(from, recipient, { it.displayTitle.ui().resolve(resources) }, onMade, onSent)
                        },
                        enabled = ready && !model.sealing,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        if (model.sealing) {
                            LoadingIndicator(Modifier.size(28.dp))
                        } else {
                            ButtonLabel(
                                if (their != null) stringResource(R.string.sharing_review_encrypt_for, their.request.name) else stringResource(R.string.sharing_review_seal),
                                Icons.Rounded.Lock,
                            )
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
                    NameField(recipient, { recipient = it }, stringResource(R.string.sharing_review_for), stringResource(R.string.sharing_review_for_hint))
                }
                NameField(
                    from,
                    { from = it },
                    stringResource(R.string.sharing_review_from),
                    if (their != null) stringResource(R.string.sharing_review_from_hint_request, their.request.name) else stringResource(R.string.sharing_review_from_hint),
                )
            }
            for (entry in entries) EntryParts(entry, model.leftOut[entry.id].orEmpty(), name) { model.toggle(entry.id, it) }
            InfoBox(Icons.Rounded.Info, stringResource(R.string.sharing_review_info))
        }
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, label: String, placeholder: String) {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = value,
        onValueChange = { onChange(it.replace('\n', ' ').take(ShareRequest.MAX_NAME_LENGTH)) },
        modifier = Modifier.keepOutOfAutofill().fillMaxWidth(),
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
private fun EntryParts(entry: Entry, leftOut: Set<EntryPart>, name: String?, onToggle: (EntryPart) -> Unit) {
    val parts = remember(entry) { Sending.parts(entry) }
    val never = remember(entry) { Sending.neverSent(entry) }
    val rows = parts.size + never.size + 1 + if (entry.attachments.isNotEmpty()) 1 else 0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(segmentShape(0, rows)).background(MaterialTheme.extraColors.row).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EntryIcon(EntryAvatar.of(entry.sortTitle), null, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(entry.displayTitle.resolve(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.sharing_review_title_goes, entry.groupPath.joinToString(" › ").ifEmpty { stringResource(R.string.sharing_top_level) }),
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
                    val preview = part.preview(entry).resolve()
                    Text(
                        preview,
                        Modifier.spokenMasked(preview),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val warning = when {
                        !on -> null
                        Sending.isSecondStep(part) ->
                            name?.let { stringResource(R.string.sharing_review_second_step, it) } ?: stringResource(R.string.sharing_review_second_step_anyone)
                        Sending.isWalletKey(entry, part) ->
                            name?.let { stringResource(R.string.sharing_review_wallet_key, it) } ?: stringResource(R.string.sharing_review_wallet_key_anyone)
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
            val (what, why) = kind.words().let { (what, why) -> stringResource(what) to stringResource(why) }
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
                    Text(what, style = MaterialTheme.typography.bodyLarge)
                    Text(why, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.sharing_review_never_sent), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (entry.attachments.isNotEmpty()) {
            val count = entry.attachments.size
            Text(
                pluralStringResource(R.plurals.sharing_review_attachments, count, count),
                Modifier.fillMaxWidth().clip(segmentShape(rows - 1, rows)).background(MaterialTheme.extraColors.row).padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What a part that never goes is ("Recovery phrase · stays on this phone"), and why (SPEC §10.1). */
private fun WalletSecrets.Kind.words(): Pair<Int, Int> = when (this) {
    WalletSecrets.Kind.PHRASE -> R.string.sharing_review_phrase to R.string.sharing_review_phrase_why
    WalletSecrets.Kind.PASSPHRASE -> R.string.sharing_review_passphrase to R.string.sharing_review_passphrase_why
    WalletSecrets.Kind.PRIVATE_KEY -> R.string.sharing_review_private_key to R.string.sharing_review_private_key_why
}

@Composable
private fun EntryPart.label(): String = when (this) {
    EntryPart.Username -> stringResource(R.string.sharing_field_username)
    EntryPart.Password -> stringResource(R.string.sharing_field_password)
    EntryPart.Website -> stringResource(R.string.sharing_field_website)
    EntryPart.Notes -> stringResource(R.string.sharing_field_notes)
    is EntryPart.Field -> name
    EntryPart.OneTimeCode -> stringResource(R.string.sharing_field_one_time_code)
}

/** What the row shows of the part: never a protected value. */
internal fun EntryPart.preview(entry: Entry): UiText = when (this) {
    EntryPart.Username -> uiPlain(entry.username)
    EntryPart.Password -> uiPlain("••••••••••••")
    EntryPart.Website -> uiPlain(entry.url)
    EntryPart.Notes -> uiPlain(if (entry.notesProtected) "••••••••" else entry.notes.lineSequence().first())
    is EntryPart.Field -> when (val value = entry.customFields.firstOrNull { it.name == name }?.value) {
        is FieldValue.Plain -> uiPlain(value.text.lineSequence().first())
        else -> uiPlain("••••••••")
    }
    EntryPart.OneTimeCode -> uiText(R.string.sharing_review_otp_preview)
}
