package dev.keething.android.databases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.keething.android.AppContainer
import dev.keething.android.R
import dev.keething.android.ui.AppLogo
import dev.keething.android.ui.ButtonLabel

/**
 * The first start, an empty list (SPEC §6.2; keething-spec databases.list AC1): no database is guessed, one is made or
 * opened. The large icon is the app's logo, as the lock screen's.
 */
@Composable
internal fun WelcomeScreen(container: AppContainer) {
    val files = rememberFileActions(container)
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .wrapContentWidth()
            .widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        AppLogo(Modifier.size(104.dp))
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.databases_welcome_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        // keething-spec app.upgrade-safety AC3: the list kept before could not be read.
        if (container.sessions.list.setAside) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.databases_welcome_list_unreadable),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.databases_welcome_lead),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = container.newDatabases::open, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                ButtonLabel(stringResource(R.string.databases_welcome_create), Icons.Rounded.Add, iconSize = 20.dp)
            }
            FilledTonalButton(onClick = files.open, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                ButtonLabel(stringResource(R.string.databases_welcome_open), Icons.Rounded.FolderOpen, iconSize = 20.dp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Hint(
            stringResource(R.string.databases_welcome_hint_keepass),
        )
        // Before any picker: why a folder, and what works for a cloud drive (keething-spec app.first-start AC1, AC2).
        Spacer(Modifier.height(12.dp))
        Hint(
            stringResource(R.string.databases_welcome_hint_folder),
        )
        Spacer(Modifier.height(12.dp))
        Hint(
            stringResource(R.string.databases_welcome_hint_cloud),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
