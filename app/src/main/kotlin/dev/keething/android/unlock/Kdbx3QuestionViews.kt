package dev.keething.android.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** What the KDBX 3.1 question says (keething-spec kdbx.kdbx3 AC5): what KDBX 4 means for other apps, and the copy. */
private val kdbx3Lines = listOf(
    "Keething changes databases only in KDBX 4: upgrade this one, or open it to view only.",
    "KeePass 2.35 and later and KeePassXC 2.3 and later read KDBX 4; older apps do not. The KDBX 3.1 file is kept as a copy beside it.",
)

/** On a KDBX 3.1 database's lock screen, in place of the password until answered (AC5): View only, or Upgrade. */
@Composable
internal fun Kdbx3QuestionCard(onAnswer: (upgrade: Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This database is KDBX 3.1", style = MaterialTheme.typography.titleMedium)
            kdbx3Lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("View only") }
                Button(onClick = { onAnswer(true) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Upgrade") }
            }
        }
    }
}

/**
 * Over the list, for a KDBX 3.1 database open and viewed only (AC5, AC6): opened without its lock screen's question,
 * or a change tried on it. Put away, it is viewed only.
 */
@Composable
internal fun Kdbx3QuestionDialog(name: String, onAnswer: (upgrade: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        icon = { Icon(Icons.Rounded.Upgrade, contentDescription = null) },
        title = { Text("Upgrade “$name” to KDBX 4?", textAlign = TextAlign.Center) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { kdbx3Lines.forEach { Text(it) } } },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Upgrade") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("View only") } },
    )
}
