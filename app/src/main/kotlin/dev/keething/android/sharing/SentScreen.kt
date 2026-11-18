package dev.keething.android.sharing

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keething.android.AppContainer
import dev.keething.android.R
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.BarTitle
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.FitText
import dev.keething.android.ui.FullBrightness
import dev.keething.android.ui.Notice
import dev.keething.android.ui.NoticeEffect
import dev.keething.android.ui.NoticeHost
import dev.keething.android.ui.QrCodeImage
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.rememberSecondsNow
import dev.keething.android.ui.resolve
import dev.keething.android.ui.uiText
import dev.keething.sharing.message.ShareCode
import dev.keething.sharing.pictures.Pictures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An envelope sent from here (SPEC §10): its code, or codes, to scan in person or to send any way, or its ten
 * pictures, to read out by a call or to send another way than the code; never both on the screen at once, so that
 * one photo of it does not hold both. The share sheet of one leaves out the app the other went through. Kept until it expires or its pictures are forgotten; the screen
 * closes when it goes ([onDone], Back too).
 */
@Composable
internal fun SentScreen(container: AppContainer, id: String, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val book by container.shareRequests.book.collectAsStateWithLifecycle()
    val sent = book?.sent?.firstOrNull { it.envelope.id == id }
    LaunchedEffect(sent == null) { if (sent == null) onDone() }
    if (sent == null) return
    val now by rememberSecondsNow()
    val encoded = remember(sent.envelope) { sent.envelope.encoded() }
    val codes = remember(encoded) { runCatching { ShareCode.qrTexts(encoded) }.getOrNull() }
    val pictures = remember(sent.from, codes) { codes?.let { SharingTexts.envelopePictures(sent.from, it) } }
    var shown by rememberSaveable { mutableIntStateOf(0) }
    var showPictures by rememberSaveable { mutableStateOf(false) }
    var forgetting by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notices = remember { Channel<Notice>(Channel.BUFFERED) }
    NoticeEffect(remember { notices.receiveAsFlow() }, snackbar)
    val send: (suspend () -> Intent) -> Unit = { make ->
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
    val codeRoute = CodePictures.Route(id, pictures = false, leaveOut = sent.picturesVia)
    val picturesRoute = CodePictures.Route(id, pictures = true, leaveOut = sent.codeVia)

    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(stringResource(R.string.sharing_sent_forget_title)) },
            text = { Text(stringResource(R.string.sharing_sent_forget_text)) },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = false
                    container.shareRequests.delete(id)
                }) { Text(stringResource(R.string.sharing_sent_forget_them)) }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text(stringResource(R.string.sharing_sent_keep_them)) } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    BarTitle(
                        if (sent.recipient.isNotEmpty()) stringResource(R.string.sharing_for, sent.recipient) else stringResource(R.string.sharing_sent_without),
                        stringResource(R.string.sharing_two_parts, SharingTexts.titles(sent.titles).resolve(), SharingTexts.expires(sent.envelope.expires, now).resolve()),
                    )
                },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.sharing_done)) } },
                actions = {
                    IconButton(onClick = { forgetting = true }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.sharing_sent_forget), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            BottomActions {
                if (showPictures) {
                    Button(
                        onClick = { send { container.codePictures.sendTextIntent(SharingTexts.picturesMessage(sent.pictures), picturesRoute) } },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        ButtonLabel(stringResource(R.string.sharing_sent_send_pictures), Icons.AutoMirrored.Rounded.Send)
                    }
                    val picturesCopied = stringResource(R.string.sharing_sent_pictures_copied)
                    FilledTonalButton(
                        onClick = {
                            container.clipboard.copy(SharingTexts.picturesLine(sent.pictures), sensitive = true)
                            notices.trySend(Notice.copied(picturesCopied))
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        ButtonLabel(stringResource(R.string.sharing_copy), Icons.Rounded.ContentCopy, iconSize = 20.dp, gap = 8.dp)
                    }
                    return@BottomActions
                }
                if (pictures != null) {
                    Button(onClick = { send { container.codePictures.sendIntent(pictures, codeRoute) } }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        ButtonLabel(pluralStringResource(R.plurals.sharing_sent_send_codes, pictures.size, pictures.size), Icons.AutoMirrored.Rounded.Send)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { send { container.codePictures.sendTextIntent(SharingTexts.envelopeMessage(sent.from, encoded), codeRoute) } },
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
                    if (pictures != null) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { container.codePictures.save(pictures) } }
                                        .onSuccess { notices.send(Notice(uiText(R.string.sharing_saved_to_pictures))) }
                                        .onFailure { notices.send(Notice(uiText(R.string.sharing_picture_not_saved, it.message.toString()), isProblem = true)) }
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            ButtonLabel(
                                pluralStringResource(R.plurals.sharing_save_pictures, pictures.size),
                                Icons.Rounded.Download,
                                short = stringResource(R.string.sharing_save_short),
                                iconSize = 20.dp,
                                gap = 8.dp,
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { NoticeHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = readableInset(), vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(stringResource(R.string.sharing_sent_tab_code), stringResource(R.string.sharing_sent_tab_pictures)).forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = showPictures == (index == 1),
                        onClick = { showPictures = index == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) {
                        FitText(label)
                    }
                }
            }
            if (showPictures) {
                NumberedPictures(sent.pictures)
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Call, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.sharing_sent_pictures_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sent.codeVia.isNotEmpty()) LeftOut(container, sent.codeVia, codeWent = true)
                return@Column
            }
            FullBrightness()
            if (codes == null) {
                Explanation(stringResource(R.string.sharing_too_large_title), stringResource(R.string.sharing_too_large_text))
            } else {
                val index = shown.coerceIn(codes.indices)
                QrCodeImage(codes[index], stringResource(R.string.sharing_sent_qr, index + 1, codes.size), Modifier.widthIn(max = 280.dp).fillMaxWidth())
                if (codes.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(onClick = { shown = (index - 1 + codes.size) % codes.size }) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.sharing_previous_code))
                        }
                        Text(stringResource(R.string.sharing_code_of, index + 1, codes.size), Modifier.widthIn(min = 120.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                        FilledTonalIconButton(onClick = { shown = (index + 1) % codes.size }) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.sharing_next_code))
                        }
                    }
                }
                Text(
                    pluralStringResource(R.plurals.sharing_sent_in_person, codes.size, codes.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                stringResource(R.string.sharing_sent_never_both),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (sent.picturesVia.isNotEmpty()) LeftOut(container, sent.picturesVia, codeWent = false)
        }
    }
}

/** "Slack is left out of the share sheet: the code went that way" ([codeWent]), or the pictures. */
@Composable
private fun LeftOut(container: AppContainer, component: String, codeWent: Boolean) {
    val app = remember(component) { container.codePictures.appName(component) }
    Text(
        when {
            app == null && codeWent -> stringResource(R.string.sharing_sent_left_out_code_unnamed)
            app == null -> stringResource(R.string.sharing_sent_left_out_pictures_unnamed)
            codeWent -> stringResource(R.string.sharing_sent_left_out_code, app)
            else -> stringResource(R.string.sharing_sent_left_out_pictures, app)
        },
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.extraColors.warn,
    )
}

/** An envelope's ten pictures to read out: numbered, 1 to 5 down the left, 6 to 10 down the right. */
@Composable
private fun NumberedPictures(indices: List<Int>) {
    val pictures = Pictures.named(indices)
    val half = (pictures.size + 1) / 2
    // The pictures' names come from core, in English for now (keething-spec plans T13b).
    val description = stringResource(R.string.sharing_pictures_description, pictures.joinToString(", ") { it.name })
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(pictures.take(half), pictures.drop(half)).forEachIndexed { column, list ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEachIndexed { row, picture ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${column * half + row + 1}",
                            Modifier.width(22.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(picture.emoji, fontSize = 26.sp, lineHeight = 34.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(picture.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
