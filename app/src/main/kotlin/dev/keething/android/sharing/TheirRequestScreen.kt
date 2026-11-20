package dev.keething.android.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keething.android.AppContainer
import dev.keething.android.R
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.rememberSecondsNow
import dev.keething.android.ui.resolve
import dev.keething.sharing.pictures.Picture
import dev.keething.sharing.pictures.Pictures
import dev.keething.sharing.requests.ShareRequests

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
    PurgeExpired(container.shareRequests)
    LaunchedEffect(their == null) { if (their == null) onBack() }
    if (their == null) return
    val request = their.request
    val now by rememberSecondsNow()
    val haptics = LocalHapticFeedback.current
    var heard by rememberSaveable { mutableStateOf(intArrayOf()) }
    var mismatched by rememberSaveable { mutableStateOf(false) }
    /** The book could not be written: the check or the delete did not happen. */
    var notKept by rememberSaveable { mutableStateOf(false) }
    val delete = { notKept = !container.shareRequests.delete(id) }
    val tap = { picture: Int ->
        if (heard.size < Pictures.COUNT) {
            heard += picture
            if (heard.size == Pictures.COUNT) {
                val checked = container.shareRequests.check(id, heard.toList())
                mismatched = checked == ShareRequests.Checked.Mismatched
                notKept = checked == ShareRequests.Checked.NotKept
                // Not kept: tapped again once the file can be written.
                if (notKept) heard = intArrayOf()
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.sharing_back)) } },
                actions = {
                    IconButton(onClick = delete) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.sharing_delete_request), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            BottomActions {
                if (their.checked) {
                    Button(onClick = onChoose, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.sharing_choose_entries)) }
                } else if (mismatched) {
                    Button(onClick = {
                        heard = intArrayOf()
                        mismatched = false
                    }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.sharing_tap_again)) }
                    TextButton(onClick = delete, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.sharing_their_differ), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = readableInset(), vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonIcon(request.name, 56.dp)
                TitleBlock(
                    request.reason,
                    stringResource(R.string.sharing_their_read, SharingTexts.moment(their.received, now).resolve(), SharingTexts.expires(request.expires, now).resolve()),
                    Modifier.padding(horizontal = 0.dp),
                    overline = stringResource(R.string.sharing_their_asks, request.name),
                )
            }
            if (notKept) ProblemLine(stringResource(R.string.sharing_not_kept))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sharing_their_check), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (their.checked) CheckedChip(checked = true)
                }
                if (their.checked) {
                    Text(
                        stringResource(R.string.sharing_their_checked, request.name),
                        Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Call, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.sharing_their_call, request.name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HeardPictures(heard.toList(), Pictures.COUNT, mismatched, onUndo = undo)
                    if (mismatched) {
                        Text(
                            stringResource(R.string.sharing_their_mismatch),
                            Modifier.padding(horizontal = 4.dp).announced(),
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

/**
 * The [count] places the pictures heard go into, in order, six in a row or ten in two rows of five, with a way back for
 * a mistap.
 */
@Composable
internal fun HeardPictures(heard: List<Int>, count: Int, mismatched: Boolean, onUndo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until count).chunked(if (count > 6) 5 else count).forEach { places ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (place in places) HeardPlace(heard.getOrNull(place)?.let(Pictures.ALL::get), place, mismatched)
                }
            }
        }
        IconButton(onClick = onUndo, enabled = heard.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = stringResource(R.string.sharing_take_back))
        }
    }
}

@Composable
private fun RowScope.HeardPlace(picture: Picture?, place: Int, mismatched: Boolean) {
    // A picture's name comes from core, in English for now (keething-spec plans T13b).
    val description = picture?.name ?: stringResource(R.string.sharing_place_empty, place + 1)
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
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(picture?.emoji ?: "${place + 1}", fontSize = if (picture != null) 24.sp else 16.sp, lineHeight = 30.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(picture?.name.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** All 64 pictures, eight a row, in Matrix's order (animals, plants, food, things, travel); a tap adds one. */
@Composable
internal fun PictureKeyboard(enabled: Boolean, onTap: (Int) -> Unit) {
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
