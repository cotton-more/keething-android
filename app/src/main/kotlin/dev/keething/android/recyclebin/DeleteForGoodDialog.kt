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
        title = { Text("Delete “$title” for good?") },
        text = {
            Text(
                "It leaves the database with its history and the attachments nothing else uses. This cannot be " +
                    "undone; copies that sync with this file delete it too.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDelete,
                enabled = armed,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete for good") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** How long Delete for good's button waits before it takes a tap. */
private val DELETE_ARMS_AFTER = 1.seconds
