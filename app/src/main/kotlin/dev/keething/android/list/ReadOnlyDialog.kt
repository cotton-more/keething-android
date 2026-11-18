package dev.keething.android.list

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.keething.android.R

/**
 * A change tried on a database too large to save, before anything is typed (keething-spec app.large-database AC3): why
 * it is read-only, over whichever screen tried it, until OK.
 */
@Composable
internal fun ReadOnlyDialog(text: String, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        icon = { Icon(Icons.Rounded.EditOff, contentDescription = null) },
        title = { Text(stringResource(R.string.list_read_only), textAlign = TextAlign.Center) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onOk) { Text(stringResource(R.string.list_ok)) } },
    )
}
