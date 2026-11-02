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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.keething.android.AppContainer
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
        Text("Welcome to Keething", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Your passwords live in a KeePass database: one encrypted file that stays yours. Keep it in a folder that " +
                "syncs, and the same file opens on your computer too.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = container.newDatabases::open, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                ButtonLabel("Create a new database…", Icons.Rounded.Add, iconSize = 20.dp)
            }
            FilledTonalButton(onClick = files.open, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                ButtonLabel("Open a database…", Icons.Rounded.FolderOpen, iconSize = 20.dp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Already use KeePass, KeePassXC, KeePassDX or Strongbox? Open your database: it stays theirs too, and " +
                "Keething writes it in their format. Keething asks for the folder it is in, and opens nothing else.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
