package dev.keething.android.sharing

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.ChevronRight
import dev.keething.android.groups.GroupPickerSheet
import dev.keething.android.groups.GroupTexts
import dev.keething.android.ui.PathText
import dev.keething.android.ui.spokenMasked
import dev.keething.card.editing.GroupTree
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.AppContainer
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import dev.keething.android.databases.DatabaseDot
import dev.keething.android.databases.databaseColors
import dev.keething.android.editor.NewEntryDatabase
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.EntryIcon
import dev.keething.android.ui.NoticeEffect
import dev.keething.android.ui.NoticeHost
import dev.keething.android.ui.colouredSecret
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.rememberSecondsNow
import dev.keething.android.ui.segmentShape
import dev.keething.card.EntryAvatar
import dev.keething.domain.vault.FieldValue
import dev.keething.sharing.payload.Receiving
import dev.keething.sharing.payload.SharedEntry
import dev.keething.sharing.pictures.Pictures
import dev.keething.sharing.requests.ShareRequests
import dev.keething.vault.session.SessionState

/**
 * An answer to a request of mine, or an envelope (SPEC §10): the entries it brings, to look at and copy, and to save as
 * new entries into a group; saving them closes the request. Anyone who saw the request could have made the answer, and
 * the sender's name is theirs to choose: the screen says so. An envelope first asks for its ten pictures, tapped on the
 * grid of 64 or pasted from a message. [onOpenEntry]: the first saved entry's card.
 */
@Composable
internal fun ReceivedScreen(
    container: AppContainer,
    codeText: String,
    onClose: () -> Unit,
    onOpenEntry: (EntryRef) -> Unit,
) {
    val model = viewModel { ReceivedModel(container.newEntryPlaces(), container.shareRequests, container.clipboard, codeText) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    NoticeEffect(model.notices, snackbar)
    var closing by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (model.opening is ReceivedModel.Opening.Sealed) {
                BottomActions {
                    if (model.wrongPictures) {
                        Button(onClick = model::tapAgain, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Tap them again") }
                    } else {
                        FilledTonalButton(
                            onClick = {
                                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                                model.paste(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString())
                            },
                            enabled = !model.unsealing,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            ButtonLabel("Paste the pictures", Icons.Rounded.ContentPaste)
                        }
                    }
                }
            }
            val opened = model.opening as? ReceivedModel.Opening.Opened ?: return@Scaffold
            BottomActions {
                when (val saving = model.saving) {
                    is ReceivedModel.Saving.Saved -> {
                        saving.refs.firstOrNull()?.let { first ->
                            Button(onClick = { onOpenEntry(first) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                Text(if (saving.refs.size == 1) "Open the entry" else "Open the first entry")
                            }
                        }
                        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Done") }
                    }
                    else -> {
                        (saving as? ReceivedModel.Saving.Failed)?.let {
                            Text(
                                (if (it.saved > 0) "Saved ${it.saved} of ${opened.payload.entries.size}; then: " else "") + it.problem,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        val busy = saving as? ReceivedModel.Saving.Busy
                        Button(onClick = model::save, enabled = busy == null && model.group != null, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            if (busy != null) {
                                LoadingIndicator(Modifier.size(28.dp))
                                Spacer(Modifier.size(10.dp))
                                Text(if (busy.of == 1) "Saving and checking…" else "Saving and checking ${busy.done + 1} of ${busy.of}…")
                            } else {
                                val count = opened.payload.entries.size
                                Text(if (count == 1) "Save to the database" else "Save the $count entries")
                            }
                        }
                        if (opened.request != null) {
                            TextButton(onClick = { closing = true }, enabled = busy == null, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                Text("Close the request without saving")
                            }
                        } else {
                            // Nothing to close: until it expires, the envelope opens again with its pictures.
                            TextButton(onClick = onClose, enabled = busy == null, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                Text("Not now")
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { NoticeHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val opening = model.opening) {
                ReceivedModel.Opening.NotHere -> Explanation(
                    "Not for a request on this phone",
                    "It answers a request that was closed, expired, or made on another phone. Ask again.",
                )
                ReceivedModel.Opening.Broken -> Explanation(
                    "This code does not open",
                    "It was changed on the way, or it is not really for your request. Ask for it again, and check the pictures by a call.",
                )
                is ReceivedModel.Opening.Unavailable -> Unavailable(opening.arrival)
                is ReceivedModel.Opening.Sealed -> EnvelopePictures(model, opening)
                is ReceivedModel.Opening.Opened -> when (val saving = model.saving) {
                    is ReceivedModel.Saving.Saved -> Saved(saving, fromRequest = opening.request != null)
                    else -> Opened(model, opening)
                }
            }
        }
    }

    if (closing) {
        AlertDialog(
            onDismissRequest = { closing = false },
            title = { Text("Close without saving?") },
            text = { Text("This phone forgets the request's key: the answer can no longer be opened.") },
            confirmButton = {
                TextButton(onClick = {
                    closing = false
                    model.close()
                    onClose()
                }) { Text("Close it") }
            },
            dismissButton = { TextButton(onClick = { closing = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun Opened(model: ReceivedModel, opened: ReceivedModel.Opening.Opened) {
    val payload = opened.payload
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = readableInset(), vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val count = payload.entries.size
        val entries = if (count == 1) "1 entry" else "$count entries"
        val request = opened.request
        if (request != null) {
            TitleBlock("From ${payload.from}", "Answer to “${request.request.reason}” · $entries")
            InfoBox(
                Icons.Rounded.Info,
                "Only this phone could open it, but anyone who saw your request could have made it, and the name is theirs to " +
                    "choose. Save it if it is what you expected: saved entries are tagged #${Receiving.TAG}.",
            )
        } else {
            TitleBlock("From ${payload.from}", "Sent without a request · $entries")
            InfoBox(
                Icons.Rounded.Info,
                "Only these pictures open it, so it comes from whoever gave you them. Anyone who had both the code and the " +
                    "pictures could open it too. Save it if it is what you expected: saved entries are tagged #${Receiving.TAG}.",
            )
        }
        // An address in an answer may be anyone's who saw the request; an envelope's comes from whoever gave its pictures.
        val sender = payload.from.takeIf { request != null }
        payload.entries.forEachIndexed { index, entry -> EntryView(index, entry, model, sender) }
        GroupChoice(model)
    }
}

/** An envelope's ten places over the grid of 64: the pictures the sender gives, in order. */
@Composable
private fun EnvelopePictures(model: ReceivedModel, sealed: ReceivedModel.Opening.Sealed) {
    val now by rememberSecondsNow()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = readableInset(), vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TitleBlock(
            "Entries sent to you",
            "Made ${SharingTexts.moment(sealed.envelope.created, now)} · ${SharingTexts.expires(sealed.envelope.expires, now)}",
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("The ten pictures", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Call, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "The sender gives them to you another way than the code: by a call, or in another app. Tap them below in " +
                        "their order, or copy their message and paste it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HeardPictures(model.heard, Pictures.ENVELOPE_COUNT, model.wrongPictures, onUndo = model::undo)
            when {
                model.unsealing -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                model.wrongPictures -> Text(
                    "These pictures do not open it. A picture misheard? Take the last ones back, or tap them again. Check " +
                        "with the sender that these pictures belong to this code.",
                    Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> PictureKeyboard(enabled = model.heard.size < Pictures.ENVELOPE_COUNT, onTap = model::tap)
            }
        }
    }
}

@Composable
private fun Unavailable(arrival: ShareRequests.Arrival) {
    when (arrival) {
        ShareRequests.Arrival.Own -> Explanation("These are your own", "This phone sent these entries. Send the code to the one they are for.")
        ShareRequests.Arrival.Expired -> Explanation("These entries have expired", "Ask the sender to send them again.")
        ShareRequests.Arrival.BadDates -> Explanation(
            "The dates are wrong",
            "It says it was made later than now, or lasts longer than a week. Check both phones' clocks, or ask for it again.",
        )
        ShareRequests.Arrival.Saved -> Explanation("Saved already", "These entries were saved on this phone: look for #${Receiving.TAG}.")
        ShareRequests.Arrival.Closed, ShareRequests.Arrival.ToOpen -> Explanation("Not now", "Unlock the database and read the code again.")
    }
}

/**
 * A row of a received entry; [note] warns under the value, a value [copyable] false is only shown. A [secret] is masked
 * until shown and copied as one; shown, it is a password's coloured characters, or plain lines when it is [text].
 */
internal data class ReceivedRow(
    val label: String,
    val value: String,
    val secret: Boolean,
    val copyable: Boolean = true,
    val note: String? = null,
    val text: Boolean = false,
)

/** [sender]: set for an answer, whose addresses are to be checked with them (SPEC §10.1). */
internal fun receivedRows(entry: SharedEntry, sender: String?): List<ReceivedRow> = buildList {
    if (entry.username.isNotEmpty()) add(ReceivedRow("Username", entry.username, false))
    if (!entry.password.isEmpty()) add(ReceivedRow("Password", entry.password.reveal(), true))
    if (entry.url.isNotEmpty()) add(ReceivedRow("Website", entry.url, false))
    if (entry.notes.isNotEmpty()) {
        // As on the card: a note's text is its "Note"; Notes sent protected stay masked until shown.
        val label = if (entry.isNote) "Note" else "Notes"
        add(ReceivedRow(label, entry.notes, entry.notesProtected, text = true))
    }
    entry.customFields.forEach { field ->
        val copyable = !Receiving.isNeverCopied(entry, field)
        when (val value = field.value) {
            is FieldValue.Plain -> {
                val note = if (sender != null && Receiving.isAddress(entry, field)) "Check it with $sender another way before paying to it." else null
                add(ReceivedRow(field.name, value.text, false, copyable, note))
            }
            is FieldValue.Protected -> add(ReceivedRow(field.name, value.secret.reveal(), true, copyable))
        }
    }
}

/** [sender]: set for an answer, whose addresses are to be checked with them (SPEC §10.1). */
@Composable
private fun EntryView(index: Int, entry: SharedEntry, model: ReceivedModel, sender: String?) {
    val rows = receivedRows(entry, sender)
    val summary = listOfNotNull(
        if (rows.size == 1) "1 field" else "${rows.size} fields",
        entry.totp?.let { "a one-time code" },
        entry.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
    ).joinToString(" · ")
    val total = rows.size + 1
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(segmentShape(0, total)).background(MaterialTheme.extraColors.row).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EntryIcon(EntryAvatar.of(entry.title.ifEmpty { entry.url }), null, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(entry.title.ifEmpty { "(Untitled)" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        rows.forEachIndexed { row, (label, value, secret, copyable, note, text) ->
            val key = "$index/$row"
            val empty = value.isEmpty()
            val shown = !secret || empty || key in model.revealed
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(segmentShape(row + 1, total)).background(MaterialTheme.extraColors.row).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        empty -> Text("empty", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        !shown -> Text("••••••••••••", Modifier.spokenMasked("••••••••••••"), style = MaterialTheme.typography.bodyLarge)
                        secret && !text -> Text(colouredSecret(value), fontFamily = MonospaceFamily, style = MaterialTheme.typography.bodyLarge)
                        else -> Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    note?.let {
                        Text(
                            it,
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.extraColors.warn,
                        )
                    }
                }
                if (secret && !empty) {
                    IconButton(onClick = { model.toggle(key) }) {
                        Icon(
                            if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (shown) "Hide $label" else "Show $label",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (copyable) {
                    IconButton(onClick = { model.copy(label, value, sensitive = secret) }, enabled = !empty) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy $label", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/**
 * Where the entries are saved: while two or more are open, the database (the last new entry's to start with,
 * keething-spec sharing.receiving AC8), then its group (the top level to start with, any group outside the recycle bin).
 */
@Composable
private fun GroupChoice(model: ReceivedModel) {
    val vault = model.database?.vault
    val tree = remember(vault) { vault?.let(GroupTree::of) }
    var open by remember { mutableStateOf(false) }
    val chosen = model.group
    val offered = model.offered
    val into = model.database?.takeIf { offered.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Save into", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (into != null) DatabaseChoice(into, offered, model.notOpen, model::saveInto)
        Surface(onClick = { open = true }, enabled = tree != null && model.groupChangeable, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    val levels = chosen?.let { tree?.levels(it) }?.map { it.name }
                    when {
                        levels == null -> Text("Choose a group", maxLines = 1)
                        levels.isEmpty() -> Text(GroupTexts.TOP_LEVEL, maxLines = 1)
                        else -> PathText(levels)
                    }
                    if (tree != null && chosen != null) {
                        GroupTexts.note(tree, chosen, movedFrom = null, saved = "the entries are saved")?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
        }
    }
    if (open && tree != null) {
        GroupPickerSheet(
            tree = tree,
            chosen = chosen,
            made = "made when the entries are saved",
            onChoose = {
                open = false
                model.chooseGroup(it)
            },
            onDismiss = { open = false },
        )
    }
}

/** The open databases to save into (dot, label, folder), those not open named under them. */
@Composable
private fun DatabaseChoice(chosen: NewEntryDatabase, offered: List<NewEntryDatabase>, notOpen: String?, onChoose: (DatabaseId) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { open = true }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DatabaseDot(chosen.mark.colorIndex)
                DatabaseName(chosen, Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            offered.forEach { database ->
                DropdownMenuItem(
                    leadingIcon = { DatabaseDot(database.mark.colorIndex) },
                    text = { DatabaseName(database) },
                    trailingIcon = if (database.id == chosen.id) ({ Icon(Icons.Rounded.Check, contentDescription = "Chosen") }) else null,
                    onClick = {
                        onChoose(database.id)
                        open = false
                    },
                )
            }
            notOpen?.let { DropdownMenuItem(text = { Text(it, style = MaterialTheme.typography.bodySmall) }, onClick = {}, enabled = false) }
        }
    }
}

@Composable
private fun DatabaseName(database: NewEntryDatabase, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            database.mark.label.full,
            color = databaseColors(database.mark.colorIndex).text,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        database.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Saved(saved: ReceivedModel.Saving.Saved, fromRequest: Boolean) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text("Saved and checked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            (if (saved.refs.size == 1) "The entry is" else "The ${saved.refs.size} entries are") + " in ${saved.group}, tagged " +
                "#${Receiving.TAG}. " + if (fromRequest) {
                    "The request is closed: this phone has forgotten its key, so the code can't be opened again."
                } else {
                    "This phone will not save ${if (saved.refs.size == 1) "it" else "them"} again from the same code."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
