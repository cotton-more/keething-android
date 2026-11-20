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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keething.android.AppContainer
import dev.keething.android.R
import dev.keething.android.ui.NoLearningKeyboard
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.ui.readableInset
import dev.keething.sharing.message.ShareRequest
import dev.keething.sharing.requests.ShareRequests
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
    /** Why the request could not be kept, as said under the fields. */
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val resources = LocalResources.current
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
            made.onSuccess { asked ->
                when (asked) {
                    is ShareRequests.Asked.Made -> onCreated(asked.request.request.id)
                    ShareRequests.Asked.NotKept -> problem = resources.getString(R.string.sharing_not_kept)
                    ShareRequests.Asked.Closed ->
                        if (container.shareRequests.unreadable.value) problem = resources.getString(R.string.sharing_unreadable) else onClose()
                }
            }.onFailure { problem = resources.getString(R.string.sharing_ask_not_kept, it.message.toString()) }
        }
        Unit
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.sharing_close)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                BottomActions {
                    Button(onClick = create, enabled = ready && !creating, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        if (creating) LoadingIndicator(Modifier.size(28.dp)) else Text(stringResource(R.string.sharing_ask_create))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = readableInset(), vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TitleBlock(stringResource(R.string.sharing_ask_for_entry), stringResource(R.string.sharing_ask_subtitle))
            NoLearningKeyboard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AskField(
                        stringResource(R.string.sharing_ask_name),
                        name,
                        { name = ShareRequest.cut(it, ShareRequest.MAX_NAME_LENGTH) },
                        stringResource(R.string.sharing_ask_name_hint),
                        KeyboardCapitalization.Words,
                        ShareRequest.MAX_NAME_LENGTH,
                    )
                    AskField(
                        stringResource(R.string.sharing_ask_reason),
                        reason,
                        { reason = ShareRequest.cut(it, ShareRequest.MAX_REASON_LENGTH) },
                        stringResource(R.string.sharing_ask_reason_hint),
                        KeyboardCapitalization.Sentences,
                        ShareRequest.MAX_REASON_LENGTH,
                    )
                }
            }
            Text(
                stringResource(R.string.sharing_ask_public),
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val days = validity.inWholeDays.toInt()
            InfoBox(Icons.Rounded.Schedule, pluralStringResource(R.plurals.sharing_ask_valid, days, days))
            problem?.let { ProblemLine(it) }
        }
    }
}

@Composable
private fun AskField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, capitalization: KeyboardCapitalization, max: Int) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val refused = value.isNotBlank() && !ShareRequest.isLine(value.trim(), max)
    TextField(
        value = value,
        onValueChange = { text -> onChange(text.replace('\n', ' ')) },
        modifier = Modifier.keepOutOfAutofill().fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = if (refused) ({ Text(stringResource(R.string.sharing_not_a_line)) }) else null,
        isError = refused,
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
