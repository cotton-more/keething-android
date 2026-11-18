package dev.keething.android.sharing

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.keething.android.AppContainer
import dev.keething.android.R
import dev.keething.android.ui.BarTitle
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.FullBrightness
import dev.keething.android.ui.Notice
import dev.keething.android.ui.NoticeEffect
import dev.keething.android.ui.NoticeHost
import dev.keething.android.ui.QrCodeImage
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.uiText
import dev.keething.sharing.message.ShareCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An answer made (SPEC §10): its code, or codes when it is too large for one, to scan in person or to send any way,
 * since only the asking phone opens it. Its request is closed already; the answer is kept until this screen goes
 * ([onDone], Back too).
 */
@Composable
internal fun AnswerScreen(container: AppContainer, answer: MadeAnswer, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    FullBrightness()
    val codes = remember(answer) { runCatching { ShareCode.qrTexts(answer.encoded) }.getOrNull() }
    val pictures = remember(answer, codes) { codes?.let { SharingTexts.answerPictures(answer.reason, it) } }
    var shown by rememberSaveable { mutableIntStateOf(0) }
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    BarTitle(
                        stringResource(R.string.sharing_for, answer.name),
                        pluralStringResource(R.plurals.sharing_answer_subtitle, answer.entries, answer.entries, answer.reason),
                    )
                },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.sharing_done)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            BottomActions {
                if (pictures != null) {
                    Button(onClick = { send { container.codePictures.sendIntent(pictures) } }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        ButtonLabel(pluralStringResource(R.plurals.sharing_send_pictures, pictures.size, pictures.size), Icons.AutoMirrored.Rounded.Send)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { send { container.codePictures.sendTextIntent(SharingTexts.answerMessage(answer.reason, answer.encoded)) } },
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
                                        .onFailure { notices.send(Notice(uiText(R.string.sharing_pictures_not_saved, it.message.toString()), isProblem = true)) }
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
            if (codes == null) {
                Explanation(stringResource(R.string.sharing_too_large_title), stringResource(R.string.sharing_too_large_text))
            } else {
                val index = shown.coerceIn(codes.indices)
                QrCodeImage(codes[index], stringResource(R.string.sharing_answer_qr, index + 1, codes.size), Modifier.widthIn(max = 300.dp).fillMaxWidth())
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
                    Text(
                        if (codes.size == 2) stringResource(R.string.sharing_answer_both) else pluralStringResource(R.plurals.sharing_answer_all, codes.size, codes.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            InfoBox(
                Icons.Rounded.Lock,
                stringResource(R.string.sharing_answer_info, answer.name),
            )
        }
    }
}
