package dev.keepassbar.android.sharing

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
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.theme.MonospaceFamily
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.ButtonLabel
import dev.keepassbar.android.ui.EntryIcon
import dev.keepassbar.android.ui.NoticeEffect
import dev.keepassbar.android.ui.NoticeHost
import dev.keepassbar.android.ui.colouredSecret
import dev.keepassbar.android.ui.rememberSecondsNow
import dev.keepassbar.android.ui.segmentShape
import dev.keepassbar.card.EntryAvatar
import dev.keepassbar.domain.vault.FieldValue
import dev.keepassbar.sharing.payload.Receiving
import dev.keepassbar.sharing.payload.SharedEntry
import dev.keepassbar.sharing.pictures.Pictures
import dev.keepassbar.sharing.requests.ShareRequests
import dev.keepassbar.vault.session.SessionState

/**
 * An answer to a request of mine, or an envelope (SPEC §10): the entries it brings, to look at and copy, and to save as
 * new entries into a group; saving them closes the request. Anyone who saw the request could have made the answer, and
 * the sender's name is theirs to choose: the screen says so. An envelope first asks for its ten pictures, tapped on the
 * grid of 64 or pasted from a message. [onOpenEntry]: the first saved entry's card.
 */
@Composable
internal fun ReceivedScreen(
    container: AppContainer,
    database: DatabaseSession,
    codeText: String,
    onClose: () -> Unit,
    onOpenEntry: (id: String) -> Unit,
) {
    val model = viewModel { ReceivedModel(database, container.shareRequests, container.clipboard, codeText) }
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
                        saving.entryIds.firstOrNull()?.let { first ->
                            Button(onClick = { onOpenEntry(first) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                Text(if (saving.entryIds.size == 1) "Open the entry" else "Open the first entry")
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
                        Button(onClick = model::save, enabled = busy == null && model.groupId != null, modifier = Modifier.fillMaxWidth().height(56.dp)) {
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
                    else -> Opened(database, model, opening)
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
private fun Opened(database: DatabaseSession, model: ReceivedModel, opened: ReceivedModel.Opening.Opened) {
    val payload = opened.payload
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
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
        payload.entries.forEachIndexed { index, entry -> EntryView(index, entry, model) }
        GroupChoice(database, model)
    }
}

/** An envelope's ten places over the grid of 64: the pictures the sender gives, in order. */
@Composable
private fun EnvelopePictures(model: ReceivedModel, sealed: ReceivedModel.Opening.Sealed) {
    val now by rememberSecondsNow()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
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

@Composable
private fun EntryView(index: Int, entry: SharedEntry, model: ReceivedModel) {
    val rows = buildList {
        if (entry.username.isNotEmpty()) add(Triple("Username", entry.username, false))
        if (!entry.password.isEmpty()) add(Triple("Password", entry.password.reveal(), true))
        if (entry.url.isNotEmpty()) add(Triple("Website", entry.url, false))
        if (entry.notes.isNotEmpty()) add(Triple("Notes", entry.notes, false))
        entry.customFields.forEach { field ->
            when (val value = field.value) {
                is FieldValue.Plain -> add(Triple(field.name, value.text, false))
                is FieldValue.Protected -> add(Triple(field.name, value.secret.reveal(), true))
            }
        }
    }
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
        rows.forEachIndexed { row, (label, value, secret) ->
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
                        !shown -> Text("••••••••••••", style = MaterialTheme.typography.bodyLarge)
                        secret -> Text(colouredSecret(value), fontFamily = MonospaceFamily, style = MaterialTheme.typography.bodyLarge)
                        else -> Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
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
                IconButton(onClick = { model.copy(label, value, sensitive = secret) }, enabled = !empty) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy $label", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** The group the entries are saved into: the top level to start with, any group outside the recycle bin. */
@Composable
private fun GroupChoice(database: DatabaseSession, model: ReceivedModel) {
    val session by database.session.state.collectAsStateWithLifecycle()
    val groups = (session as? SessionState.Unlocked)?.vault?.groups.orEmpty()
    var open by remember { mutableStateOf(false) }
    val chosen = groups.firstOrNull { it.id == model.groupId }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Save into", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Box {
            Surface(onClick = { open = true }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(chosen?.let { if (it.isRoot) "Top level" else it.path.joinToString(" › ") } ?: "Choose a group", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ExpandMore, contentDescription = null)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                groups.forEach { group ->
                    DropdownMenuItem(
                        text = { Text(if (group.isRoot) "Top level" else group.path.joinToString(" › ")) },
                        onClick = {
                            model.groupId = group.id
                            open = false
                        },
                    )
                }
            }
        }
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
            (if (saved.entryIds.size == 1) "The entry is" else "The ${saved.entryIds.size} entries are") + " in ${saved.group}, tagged " +
                "#${Receiving.TAG}. " + if (fromRequest) {
                    "The request is closed: this phone has forgotten its key, so the code can't be opened again."
                } else {
                    "This phone will not save ${if (saved.entryIds.size == 1) "it" else "them"} again from the same code."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
