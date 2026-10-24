package dev.keething.android.sharing

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keething.android.AppContainer
import dev.keething.android.ui.NoLearningKeyboard
import dev.keething.android.ui.readableInset
import dev.keething.sharing.message.ShareRequest
import java.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asking for an entry (SPEC §10): who asks and what for, both sent as they are; the request's key pair and pictures
 * are made on Create (the pictures take a moment), then its screen replaces this one ([onCreated]).
 */
@Composable
internal fun AskScreen(container: AppContainer, onClose: () -> Unit, onCreated: (id: String) -> Unit) {
    val validity by container.settings.requestValidity.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(container.settings.requestName) }
    var reason by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ready = ShareRequest.isLine(name.trim(), ShareRequest.MAX_NAME_LENGTH) && ShareRequest.isLine(reason.trim(), ShareRequest.MAX_REASON_LENGTH)
    val create = {
        creating = true
        problem = null
        container.settings.requestName = name
        scope.launch {
            val started = TimeSource.Monotonic.markNow()
            val made = runCatching {
                withContext(Dispatchers.Default) { container.shareRequests.ask(name, reason, Duration.ofDays(validity.inWholeDays)) }
            }
            Log.i("Sharing", "request made in ${started.elapsedNow()}")
            creating = false
            made.onSuccess { mine -> mine?.let { onCreated(it.request.id) } ?: onClose() }
                .onFailure { problem = "The request could not be kept on this phone: ${it.message}." }
        }
        Unit
    }
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
            Column(Modifier.imePadding()) {
                BottomActions {
                    Button(onClick = create, enabled = ready && !creating, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        if (creating) LoadingIndicator(Modifier.size(28.dp)) else Text("Create the request")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = readableInset(), vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TitleBlock("Ask for an entry", "The one you ask answers with a code that only this phone can open.")
            NoLearningKeyboard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AskField("Your name", name, { name = it.take(ShareRequest.MAX_NAME_LENGTH) }, "As they know you", KeyboardCapitalization.Words)
                    AskField("What for", reason, { reason = it.take(ShareRequest.MAX_REASON_LENGTH) }, "e.g. Netflix for the TV", KeyboardCapitalization.Sentences)
                }
            }
            Text(
                "Both go with the request as they are: whoever carries it can read them. Keep secrets out of them.",
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val days = validity.inWholeDays
            InfoBox(
                Icons.Rounded.Schedule,
                "Valid for ${if (days == 1L) "1 day" else "$days days"}, then this phone forgets its key and the answer can no longer be opened. " +
                    "Settings › Sharing changes how long.",
            )
            problem?.let { Text(it, Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AskField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, capitalization: KeyboardCapitalization) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = value,
        onValueChange = { text -> onChange(text.replace('\n', ' ')) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}
