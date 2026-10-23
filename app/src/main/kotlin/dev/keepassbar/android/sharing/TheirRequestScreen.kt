package dev.keepassbar.android.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.rememberSecondsNow
import dev.keepassbar.sharing.pictures.Pictures
import dev.keepassbar.sharing.requests.ShareRequests

/**
 * A request someone sent (SPEC §10): who asks for what, and the check of its pictures. This phone does not show them:
 * the asker reads out the six their phone shows, by a call or in person, and the user taps them here in that order;
 * the request is checked only when they are its own. Then the entries are chosen ([onChoose]). Six that differ mean a
 * picture misheard, or a request swapped on the way, which goes. The screen closes when the request is gone.
 */
@Composable
internal fun TheirRequestScreen(container: AppContainer, id: String, onBack: () -> Unit, onChoose: () -> Unit) {
    val book by container.shareRequests.book.collectAsStateWithLifecycle()
    val their = book?.theirs?.firstOrNull { it.request.id == id }
    LaunchedEffect(their == null) { if (their == null) onBack() }
    if (their == null) return
    val request = their.request
    val now by rememberSecondsNow()
    val haptics = LocalHapticFeedback.current
    var heard by rememberSaveable { mutableStateOf(intArrayOf()) }
    var mismatched by rememberSaveable { mutableStateOf(false) }
    val tap = { picture: Int ->
        if (heard.size < Pictures.COUNT) {
            heard += picture
            if (heard.size == Pictures.COUNT) {
                val checked = container.shareRequests.check(id, heard.toList())
                mismatched = checked == ShareRequests.Checked.Mismatched
                haptics.performHapticFeedback(if (mismatched) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
            }
        }
    }
    val undo = {
        heard = heard.copyOf((heard.size - 1).coerceAtLeast(0))
        mismatched = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { container.shareRequests.delete(id) }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete the request", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            BottomActions {
                if (their.checked) {
                    Button(onClick = onChoose, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Choose entries") }
                } else if (mismatched) {
                    Button(onClick = {
                        heard = intArrayOf()
                        mismatched = false
                    }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Tap them again") }
                    TextButton(onClick = { container.shareRequests.delete(id) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("They really differ · delete it", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonIcon(request.name, 56.dp)
                TitleBlock(
                    request.reason,
                    "Read ${SharingTexts.moment(their.received, now)} · ${SharingTexts.expires(request.expires, now)}",
                    Modifier.padding(horizontal = 0.dp),
                    overline = "${request.name} asks for",
                )
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Check the pictures", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (their.checked) CheckedChip(checked = true)
                }
                if (their.checked) {
                    Text(
                        "The six ${request.name} read out are this request's own.",
                        Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Call, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Call ${request.name} or ask in person, not in the chat the code came through. Ask them to read out " +
                                "the six pictures their phone shows, and tap them below in that order. In person: tap what their " +
                                "screen shows.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HeardPictures(heard, mismatched, onUndo = undo)
                    if (mismatched) {
                        Text(
                            "These are not this request's pictures. A picture misheard? Tap them again. If they really " +
                                "differ, the request was swapped on the way: delete it.",
                            Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        PictureKeyboard(enabled = heard.size < Pictures.COUNT, onTap = tap)
                    }
                }
            }
        }
    }
}

/** The six places the pictures heard go into, in order, with a way back for a mistap. */
@Composable
private fun HeardPictures(heard: IntArray, mismatched: Boolean, onUndo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (place in 0 until Pictures.COUNT) {
            val picture = heard.getOrNull(place)?.let(Pictures.ALL::get)
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        when {
                            mismatched -> MaterialTheme.colorScheme.errorContainer
                            picture != null -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainer
                        },
                    )
                    .padding(vertical = 6.dp)
                    .semantics { contentDescription = picture?.name ?: "Picture ${place + 1}, not tapped yet" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(picture?.emoji ?: "${place + 1}", fontSize = if (picture != null) 24.sp else 16.sp, lineHeight = 30.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(picture?.name.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onUndo, enabled = heard.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Take the last picture back")
        }
    }
}

/** All 64 pictures, eight a row, in Matrix's order (animals, plants, food, things, travel); a tap adds one. */
@Composable
private fun PictureKeyboard(enabled: Boolean, onTap: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Pictures.ALL.indices.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { index ->
                    val picture = Pictures.ALL[index]
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(enabled = enabled, role = Role.Button, onClickLabel = picture.name) { onTap(index) }
                            .semantics { contentDescription = picture.name },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(picture.emoji, fontSize = 24.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
