package dev.keething.android.recyclebin

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.keething.android.R
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Delete for good, asked first (keething-spec edit.delete-for-good AC2), from the card's ⋮ or a row's long press: it says it cannot be undone. Only the red
 * button deletes, and it comes alive after a second, so a tap meant for the menu or the sheet does not; Cancel, Back or a tap
 * outside keep the entry.
 */
@Composable
internal fun DeleteForGoodDialog(title: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(DELETE_ARMS_AFTER)
        armed = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null) },
        title = { Text(stringResource(R.string.recyclebin_delete_for_good_title, title)) },
        text = {
            Text(stringResource(R.string.recyclebin_delete_for_good_text))
        },
        confirmButton = {
            TextButton(
                onClick = onDelete,
                enabled = armed,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.recyclebin_delete_for_good_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.recyclebin_cancel)) } },
    )
}

/** How long Delete for good's button waits before it takes a tap. */
private val DELETE_ARMS_AFTER = 1.seconds
