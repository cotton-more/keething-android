package dev.keepassbar.android.sharing

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.ui.FullBrightness
import dev.keepassbar.android.ui.Notice
import dev.keepassbar.android.ui.NoticeEffect
import dev.keepassbar.android.ui.NoticeHost
import dev.keepassbar.android.ui.QrCodeImage
import dev.keepassbar.sharing.message.ShareCode
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
                        notices.send(Notice("No app can take it", isProblem = true))
                    }
                }
                .onFailure { notices.send(Notice("Could not make the picture: ${it.message}", isProblem = true)) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("For ${answer.name}")
                        Text(
                            "${answer.reason} · ${if (answer.entries == 1) "1 entry" else "${answer.entries} entries"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, contentDescription = "Done") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            BottomActions {
                if (pictures != null) {
                    Button(onClick = { send { container.codePictures.sendIntent(pictures) } }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                        Spacer(Modifier.size(10.dp))
                        Text(if (pictures.size == 1) "Send the picture" else "Send the ${pictures.size} pictures")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { send { container.codePictures.sendTextIntent(SharingTexts.answerMessage(answer.reason, answer.encoded)) } },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.TextSnippet, contentDescription = null, Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Send as text")
                    }
                    if (pictures != null) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { container.codePictures.save(pictures) } }
                                        .onSuccess { notices.send(Notice("Saved to Pictures › Keething")) }
                                        .onFailure { notices.send(Notice("Could not save the pictures: ${it.message}", isProblem = true)) }
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, Modifier.size(20.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(if (pictures.size == 1) "Save picture" else "Save pictures")
                        }
                    }
                }
            }
        },
        snackbarHost = { NoticeHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (codes == null) {
                Explanation("Too large for QR codes", "Send it as text: a messenger or mail carries it whole.")
            } else {
                val index = shown.coerceIn(codes.indices)
                QrCodeImage(codes[index], "QR code of the answer, ${index + 1} of ${codes.size}", Modifier.widthIn(max = 300.dp).fillMaxWidth())
                if (codes.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(onClick = { shown = (index - 1 + codes.size) % codes.size }) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous code")
                        }
                        Text("Code ${index + 1} of ${codes.size}", Modifier.widthIn(min = 120.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                        FilledTonalIconButton(onClick = { shown = (index + 1) % codes.size }) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next code")
                        }
                    }
                    Text(
                        "Too much for one code: ${if (codes.size == 2) "both are" else "all ${codes.size} are"} needed, in any order.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            InfoBox(
                Icons.Rounded.Lock,
                "Only the phone that made ${answer.name}'s request can open it, so it may travel any way. The request is closed " +
                    "now: nothing more can be sent to it.",
            )
        }
    }
}
