package dev.keething.android.sharing

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keething.android.AppContainer
import dev.keething.android.R
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.BarTitle
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.FullBrightness
import dev.keething.android.ui.Notice
import dev.keething.android.ui.NoticeEffect
import dev.keething.android.ui.NoticeHost
import dev.keething.android.ui.QrCodeImage
import dev.keething.android.ui.dotsAtLineEnd
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.uiText
import dev.keething.sharing.message.ShareCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A request of mine (SPEC §10): its code to scan in person or to send, and its pictures, which the one who answers
 * asks for by a call. The screen is at full brightness for a camera. It closes when the request is gone (answered and
 * saved, deleted, expired, or a lock).
 */
@Composable
internal fun MyRequestScreen(container: AppContainer, id: String, onClose: () -> Unit) {
    val book by container.shareRequests.book.collectAsStateWithLifecycle()
    val mine = book?.mine?.firstOrNull { it.request.id == id }
    LaunchedEffect(mine == null) { if (mine == null) onClose() }
    if (mine == null) return
    val request = mine.request
    FullBrightness()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notices = remember { Channel<Notice>(Channel.BUFFERED) }
    NoticeEffect(remember { notices.receiveAsFlow() }, snackbar)
    var deleting by rememberSaveable { mutableStateOf(false) }
    val send: (suspend () -> android.content.Intent) -> Unit = { make ->
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { make() } }
                .onSuccess { intent ->
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        notices.send(Notice(uiText(R.string.sharing_no_app), isProblem = true))
                    }
                }
                .onFailure { notices.send(Notice(uiText(R.string.sharing_picture_not_made, it.message.toString()), isProblem = true)) }
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.sharing_my_delete_title)) },
            text = { Text(stringResource(R.string.sharing_my_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    container.shareRequests.delete(id)
                }) { Text(stringResource(R.string.sharing_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.sharing_keep_it)) } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { BarTitle(stringResource(R.string.sharing_my_title), request.reason) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.sharing_close)) } },
                actions = {
                    IconButton(onClick = { deleting = true }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.sharing_delete_request), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            BottomActions {
                Button(
                    onClick = { send { container.codePictures.sendIntent(listOf(SharingTexts.requestPicture(request))) } },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    ButtonLabel(stringResource(R.string.sharing_send_picture), Icons.AutoMirrored.Rounded.Send)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { send { container.codePictures.sendTextIntent(SharingTexts.requestMessage(request)) } },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        ButtonLabel(
                            stringResource(R.string.sharing_send_as_text),
                            Icons.AutoMirrored.Rounded.TextSnippet,
                            short = stringResource(R.string.sharing_send_as_text_short),
                            iconSize = 20.dp,
                            gap = 8.dp,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { container.codePictures.save(listOf(SharingTexts.requestPicture(request))) } }
                                    .onSuccess { notices.send(Notice(uiText(R.string.sharing_saved_to_pictures))) }
                                    .onFailure { notices.send(Notice(uiText(R.string.sharing_picture_not_saved, it.message.toString()), isProblem = true)) }
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        ButtonLabel(
                            stringResource(R.string.sharing_save_picture),
                            Icons.Rounded.Download,
                            short = stringResource(R.string.sharing_save_short),
                            iconSize = 20.dp,
                            gap = 8.dp,
                        )
                    }
                }
            }
        },
        snackbarHost = { NoticeHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = readableInset(), vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QrCodeImage(ShareCode.text(request.encoded()), stringResource(R.string.sharing_my_qr), Modifier.widthIn(max = 280.dp).fillMaxWidth())
            Text(
                stringResource(R.string.sharing_my_in_person, SharingTexts.dateTime(request.expires)).dotsAtLineEnd(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.sharing_my_pictures), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium)
                PictureGrid(mine.pictures)
                Text(
                    stringResource(R.string.sharing_my_pictures_text),
                    Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
